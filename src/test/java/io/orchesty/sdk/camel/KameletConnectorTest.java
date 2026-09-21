package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.process.Headers;
import io.orchesty.sdk.process.Json;
import io.orchesty.sdk.process.ProcessDto;
import io.orchesty.sdk.process.ResultCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.model.ModelCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the whole Kamelet path offline using the self-contained
 * {@code uppercase-action} kamelet (classpath:/kamelets/uppercase-action.kamelet.yaml).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KameletConnectorTest {

    private static final String SUCCESS = Integer.toString(ResultCode.SUCCESS.code());
    private static final String STOP = Integer.toString(ResultCode.STOP_AND_FAILED.code());

    private CamelRuntime runtime;
    private KameletConnector connector;

    @BeforeAll
    void setUp() {
        runtime = new CamelRuntime();
        connector = new KameletConnector(runtime, "camel-uppercase", "uppercase-action", null);
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void runsActionKameletWithDynamicParam() throws Exception {
        ProcessDto out = connector.process(withSuffix("!!!"));

        assertEquals(SUCCESS, out.getHeader(Headers.RESULT_CODE), out.getHeader(Headers.RESULT_MESSAGE));
        assertEquals("HELLO!!!", out.getJsonData().get("body"));
    }

    @Test
    void usesKameletDefaultWhenParamMissing() throws Exception {
        ProcessDto dto = new ProcessDto().setData("{\"body\":\"hello\"}");
        ProcessDto out = connector.process(dto);

        assertEquals("HELLO", out.getJsonData().get("body"));
    }

    @Test
    void buildsUriFromSettingsAndPayload() {
        UriExposed exposed = new UriExposed(runtime, "camel-uppercase", "uppercase-action", "app");
        ApplicationInstall install = new ApplicationInstall()
                .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("suffix", "X", "unrelated", "skipped")));

        String uri = exposed.uri(new ProcessDto().setData("{}"), install);
        assertEquals("kamelet:uppercase-action?suffix=RAW{X}", uri, "values verbatim, undeclared install keys skipped");
    }

    @Test
    void payloadNeverOverridesTheInstall() {
        UriExposed exposed = new UriExposed(runtime, "camel-uppercase", "uppercase-action", "app");
        ApplicationInstall install =
                new ApplicationInstall().addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("suffix", "X")));

        assertEquals("kamelet:uppercase-action?suffix=RAW{X}", exposed.uri(withSuffix("Y"), install));
    }

    @Test
    void passesSpecialCharactersVerbatim() throws Exception {
        for (String suffix : new String[] {
            " a&b=c", " x+y", " 50%", " ü/é:?", " (p)", " {q}", " a}&b", " x)}&templateId=pwn&location=http://evil"
        }) {
            assertEquals(
                    "HELLO" + suffix,
                    connector.process(withSuffix(suffix)).getJsonData().get("body"),
                    suffix);
        }
        assertNull(((ModelCamelContext) runtime.context()).getRouteTemplateDefinition("pwn"), "no template injected");
    }

    @Test
    void rejectsUndeclaredAndReservedParameters() throws Exception {
        for (String key : new String[] {"range", "templateId", "location", "routeId", "uuid"}) {
            ProcessDto dto =
                    new ProcessDto().setData("{\"body\":\"hello\",\"kameletParameters\":{\"" + key + "\":\"x\"}}");
            assertEquals(STOP, connector.process(dto).getHeader(Headers.RESULT_CODE), key);
        }
    }

    @Test
    void rejectsValuesThatCannotBePassedVerbatim() throws Exception {
        for (String suffix : new String[] {"a}&b)&templateId=pwn", "x}&y)", "{{sys:user.home}}"}) {
            assertEquals(STOP, connector.process(withSuffix(suffix)).getHeader(Headers.RESULT_CODE), suffix);
        }
        assertThrows(IllegalArgumentException.class, () -> KameletConnector.raw("k", "a}&b)&c"));
        assertEquals("RAW(a}&b)", KameletConnector.raw("k", "a}&b"));
        assertEquals("RAW{a)&b}", KameletConnector.raw("k", "a)&b"));
    }

    @Test
    void rejectsSimpleExpressionsFromPayloadAndInstall() throws Exception {
        for (String suffix : new String[] {
            "${sys.user.home}",
            "x${body}",
            "${bean:java.lang.System?method=exit(1)}",
            "$simple{env:CRYPT_SECRET}",
            "#class:java.lang.Object",
            "RAW(x)"
        }) {
            assertEquals(STOP, connector.process(withSuffix(suffix)).getHeader(Headers.RESULT_CODE), suffix);
        }

        UriExposed exposed = new UriExposed(runtime, "camel-uppercase", "uppercase-action", "app");
        for (String suffix : new String[] {"${header.x}", "$simple{env:CRYPT_SECRET}", "#bean:x"}) {
            ApplicationInstall install =
                    new ApplicationInstall().addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("suffix", suffix)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> exposed.uri(new ProcessDto().setData("{}"), install),
                    "tenant values run in the shared worker too: " + suffix);
        }
    }

    @Test
    void oauthInstallIsMappedToKameletOptions() throws Exception {
        KameletConnector tokens = new KameletConnector(runtime, "camel-token", "token-action", null);
        UriExposed exposed = new UriExposed(runtime, "camel-token", "token-action", "app");
        ApplicationInstall install = new ApplicationInstall()
                .addSettings(Map.of(
                        CoreForm.AUTHORIZATION_FORM,
                        Map.of(
                                "client_id", "cid",
                                "token", Map.of("accessToken", "AT"),
                                "oauth_state_nonce", "nonce")));

        String uri = exposed.uri(new ProcessDto().setData("{}"), install);
        assertTrue(uri.contains("clientId=RAW{cid}"), uri);
        assertTrue(uri.contains("accessToken=RAW{AT}"), uri);
        assertFalse(uri.contains("token="), uri);
        assertFalse(uri.contains("nonce"), uri);

        Exchange out = runtime.sendKamelet(uri, runtime.newExchange());
        assertEquals("cid:AT", out.getMessage().getBody(String.class));
        assertEquals(SUCCESS, tokens.process(new ProcessDto().setData("{}")).getHeader(Headers.RESULT_CODE));
    }

    @Test
    void leastRecentlyUsedKameletRoutesAreRemoved() throws Exception {
        try (CamelRuntime bounded = new CamelRuntime().setMaxKameletRoutes(2)) {
            KameletConnector small = new KameletConnector(bounded, "camel-uppercase", "uppercase-action", null);
            small.process(withSuffix("1"));
            small.process(withSuffix("2"));
            int endpoints = bounded.context().getEndpoints().size();

            for (int i = 3; i < 10; i++) {
                assertEquals(
                        "HELLO" + i,
                        small.process(withSuffix(Integer.toString(i)))
                                .getJsonData()
                                .get("body"));
            }

            assertEquals(2, bounded.kameletRouteCount());
            assertEquals(2, bounded.context().getRoutes().size());
            assertEquals(endpoints, bounded.context().getEndpoints().size(), "endpoints do not grow");
            assertEquals("HELLO1", small.process(withSuffix("1")).getJsonData().get("body"), "recreated");
        }
    }

    @Test
    void uriPlacedParametersCannotAddEndpointOptions(@TempDir Path dir) throws Exception {
        KameletConnector files = new KameletConnector(runtime, "camel-file-sink", "file-sink", null);

        ProcessDto ok = new ProcessDto()
                .setData("{\"body\":\"hello\",\"kameletParameters\":"
                        + Json.toString(Map.of("directory", dir.toString(), "fileName", "out.txt", "note", "Q&A: "))
                        + "}");
        assertEquals(SUCCESS, files.process(ok).getHeader(Headers.RESULT_CODE), "expression parameters may hold &");
        assertEquals("Q&A: hello", Files.readString(dir.resolve("out.txt")));

        ProcessDto percent = new ProcessDto()
                .setData("{\"body\":\"p\",\"kameletParameters\":"
                        + Json.toString(Map.of("directory", dir.toString(), "fileName", "50%off.txt")) + "}");
        assertEquals(SUCCESS, files.process(percent).getHeader(Headers.RESULT_CODE), "a lone % is not an escape");

        for (Map<String, String> params : List.of(
                Map.of("directory", dir + "?delete=true", "fileName", "a.txt"),
                Map.of("directory", dir.toString(), "fileName", "a.txt&fileExist=Override"),
                Map.of("directory", dir + "%3Fdelete%3Dtrue", "fileName", "a.txt"),
                Map.of("directory", dir.toString(), "fileName", "a.txt%26fileExist%3DOverride"))) {
            ProcessDto dto =
                    new ProcessDto().setData("{\"body\":\"x\",\"kameletParameters\":" + Json.toString(params) + "}");
            assertEquals(STOP, files.process(dto).getHeader(Headers.RESULT_CODE), params.toString());
        }

        UriExposed exposed = new UriExposed(runtime, "camel-file-sink", "file-sink", "app");
        ApplicationInstall install = new ApplicationInstall()
                .addSettings(Map.of(
                        CoreForm.AUTHORIZATION_FORM, Map.of("directory", "in?delete=true", "fileName", "a.txt")));
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> exposed.uri(new ProcessDto().setData("{}"), install));
        assertTrue(e.getMessage().contains("endpoint URI"), e.getMessage());
        assertFalse(e.getMessage().contains("delete"), "the value is not echoed: " + e.getMessage());
    }

    @Test
    void recipientListParametersCannotAddEndpoints(@TempDir Path dir) throws Exception {
        KameletConnector list = new KameletConnector(runtime, "camel-list-sink", "list-sink", null);

        ProcessDto ok = new ProcessDto()
                .setData("{\"body\":\"listed\",\"kameletParameters\":"
                        + Json.toString(Map.of("directory", dir.toString())) + "}");
        assertEquals(SUCCESS, list.process(ok).getHeader(Headers.RESULT_CODE));
        assertEquals("listed", Files.readString(dir.resolve("list.txt")));

        for (String directory : new String[] {dir + ",file:/tmp/other", dir + "?delete=true", dir + ";x", dir + " x"}) {
            ProcessDto dto = new ProcessDto()
                    .setData("{\"body\":\"x\",\"kameletParameters\":" + Json.toString(Map.of("directory", directory))
                            + "}");
            assertEquals(STOP, list.process(dto).getHeader(Headers.RESULT_CODE), directory);
        }
    }

    private static ProcessDto withSuffix(String suffix) {
        return new ProcessDto()
                .setData("{\"body\":\"hello\",\"kameletParameters\":{\"suffix\":" + Json.toString(suffix) + "}}");
    }

    /** Test-only subclass to reach the protected URI builder. */
    private static final class UriExposed extends KameletConnector {
        UriExposed(CamelRuntime runtime, String name, String templateId, String applicationName) {
            super(runtime, name, templateId, applicationName);
        }

        String uri(ProcessDto dto, ApplicationInstall install) {
            return resolveEndpointUri(new SendRequest(dto, dto.getJsonData(), install));
        }
    }
}
