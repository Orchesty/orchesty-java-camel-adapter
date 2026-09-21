package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.process.Headers;
import io.orchesty.sdk.process.ProcessDto;
import io.orchesty.sdk.process.ResultCode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.component.file.remote.SftpEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Payload options and {@code Camel*} headers come only from the registration's allow-lists and only
 * add to the install; responses are mapped without echoing uploads, and mapping failures are
 * classified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogCamelConnectorTest {

    private static final String STOP = Integer.toString(ResultCode.STOP_AND_FAILED.code());

    private CamelRuntime runtime;

    @BeforeAll
    void setUp() {
        runtime = new CamelRuntime();
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void payloadOptionsNeverOverrideTheInstall() {
        Exposed connector = new Exposed(runtime, "sftp");
        connector.setPayloadOptions(List.of("host", "directoryName"));
        ApplicationInstall install = new ApplicationInstall()
                .addSettings(Map.of(
                        CoreForm.AUTHORIZATION_FORM,
                        Map.of("host", "install-host", "username", "alice", "password", "secret")));
        ProcessDto dto = new ProcessDto()
                .setData("{\"endpointOptions\":{\"host\":\"evil-host\",\"directoryName\":\"uploads\"}}");

        String uri = connector.uri(dto, install);
        assertTrue(uri.contains("install-host"), uri);
        assertFalse(uri.contains("evil-host"), uri);
        assertTrue(uri.contains("uploads"), uri);
        assertTrue(uri.contains("disconnect=true"), "connectors close the session after each send: " + uri);
    }

    @Test
    void optionsOutsideTheAllowListStopTheMessage() throws Exception {
        CatalogCamelConnector connector = new CatalogCamelConnector(runtime, "sftp", "camel-sftp");
        connector.setPayloadOptions(List.of("directoryName"));

        ProcessDto dto = new ProcessDto().setData("{\"endpointOptions\":{\"knownHostsFile\":\"/etc/hosts\"}}");
        assertEquals(STOP, connector.process(dto).getHeader(Headers.RESULT_CODE));
    }

    @Test
    void secretUndeclaredAndReferenceOptionsStopTheMessage() throws Exception {
        CatalogCamelConnector connector = new CatalogCamelConnector(runtime, "sftp", "camel-sftp");
        connector.setPayloadOptions(List.of("password", "privateKey", "noSuchOption", "directoryName"));
        for (String options : new String[] {
            "{\"password\":\"x\"}", "{\"privateKey\":\"x\"}", "{\"noSuchOption\":1}", "{\"directoryName\":\"#bean:x\"}"
        }) {
            ProcessDto dto = new ProcessDto().setData("{\"endpointOptions\":" + options + "}");
            assertEquals(STOP, connector.process(dto).getHeader(Headers.RESULT_CODE), options);
        }
    }

    @Test
    void camelHeadersNeedTheAllowList() {
        Exposed connector = new Exposed(runtime, "file");
        connector.setPayloadHeaders(List.of("CamelFileName"));
        Exchange exchange = runtime.newExchange();
        ProcessDto dto = new ProcessDto()
                .setData("{\"headers\":{\"CamelHttpUri\":\"http://evil\",\"camelOverruleFileName\":\"x\","
                        + "\"CamelFileName\":\"a.txt\",\"X-Trace\":\"1\"}}");

        connector.prepare(exchange, dto);
        assertNull(exchange.getMessage().getHeader("CamelHttpUri"));
        assertNull(exchange.getMessage().getHeader("camelOverruleFileName"));
        assertEquals("a.txt", exchange.getMessage().getHeader("CamelFileName"));
        assertEquals("1", exchange.getMessage().getHeader("X-Trace"));
    }

    @Test
    void fileUploadDoesNotEchoTheContent(@TempDir Path dir) throws Exception {
        CatalogCamelConnector connector =
                new CatalogCamelConnector(runtime, "file", "camel-file", null, Map.of("directoryName", dir.toString()));
        ProcessDto dto = new ProcessDto().setData("{\"fileName\":\"out.txt\",\"content\":\"top secret contents\"}");

        Map<String, Object> out = connector.process(dto).getJsonData();
        assertEquals("top secret contents", Files.readString(dir.resolve("out.txt")));
        assertEquals("out.txt", out.get("fileName"));
        assertEquals(true, out.get("ok"));
        assertFalse(out.containsKey("body"), out.toString());
    }

    @Test
    void responseMappingFailuresAreClassified(@TempDir Path dir) throws Exception {
        CatalogCamelConnector connector =
                new CatalogCamelConnector(
                        runtime, "file", "camel-file", null, Map.of("directoryName", dir.toString())) {
                    @Override
                    protected void mapResponse(Exchange exchange, SendRequest request) {
                        throw new IllegalStateException("cannot read the response");
                    }
                };
        ProcessDto dto = new ProcessDto().setData("{\"fileName\":\"out.txt\",\"content\":\"x\"}");

        OnRepeatException repeat = assertThrows(OnRepeatException.class, () -> connector.process(dto));
        assertEquals(RetryPolicy.httpDefault().getRepeatIntervalSeconds(), repeat.getInterval());
    }

    @Test
    void registrationDefaultsMayUseReferences() {
        Exposed connector = new Exposed(runtime, "sftp", Map.of("keyPair", "#bean:keyPair"));
        ApplicationInstall install =
                new ApplicationInstall().addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("host", "h")));

        String uri = connector.uri(new ProcessDto().setData("{}"), install);
        assertTrue(uri.contains("keyPair="), uri);
    }

    @Test
    void installValuesAreNeverEvaluatedByCamel() {
        Exposed connector = new Exposed(runtime, "sftp");
        for (String value : new String[] {"{{env:HOME}}", "#class:java.lang.Object", "$simple{env:HOME}", "RAW(x)"}) {
            ApplicationInstall install = new ApplicationInstall()
                    .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("host", "h", "username", value)));
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> connector.uri(new ProcessDto().setData("{}"), install),
                    value);
            assertFalse(e.getMessage().contains(value), "the value is not echoed: " + e.getMessage());
        }
    }

    @Test
    void payloadValuesCannotAddEndpointOptions() throws Exception {
        CatalogCamelConnector connector = new CatalogCamelConnector(runtime, "sftp", "camel-sftp");
        connector.setPayloadOptions(List.of("directoryName", "fileName"));
        for (String options : new String[] {
            "{\"directoryName\":\"in?delete=true\"}",
            "{\"directoryName\":\"in&delete=true\"}",
            "{\"fileName\":\"RAW(x)&delete=true&a=RAW(y)\"}",
            "{\"fileName\":\"${env:HOME}\"}"
        }) {
            ProcessDto dto = new ProcessDto().setData("{\"endpointOptions\":" + options + "}");
            assertEquals(STOP, connector.process(dto).getHeader(Headers.RESULT_CODE), options);
        }
    }

    @Test
    void secretsArePassedVerbatimUnlessTheyCouldEndTheRawValue() {
        Exposed connector = new Exposed(runtime, "sftp");
        for (String password : new String[] {"p&w)d% +x}", "#secret1"}) {
            ApplicationInstall install = new ApplicationInstall()
                    .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("host", "h", "password", password)));
            String uri = connector.uri(new ProcessDto().setData("{}"), install);
            SftpEndpoint endpoint = runtime.context().getEndpoint(uri, SftpEndpoint.class);
            assertEquals(password, endpoint.getConfiguration().getPassword(), uri);
        }

        ApplicationInstall closing = new ApplicationInstall()
                .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("host", "h", "password", "x)&delete=true")));
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> connector.uri(new ProcessDto().setData("{}"), closing));
        assertTrue(e.getMessage().contains(")&"), e.getMessage());
    }

    @Test
    void applicationWithoutRepositoryStops() throws Exception {
        CatalogCamelConnector connector = new CatalogCamelConnector(runtime, "file", "camel-file", "some-app");

        ProcessDto dto = new ProcessDto().setData("{\"content\":\"x\"}");
        assertEquals(STOP, connector.process(dto).getHeader(Headers.RESULT_CODE), "never runs without credentials");
    }

    @Test
    void streamResponsesAreConvertedAndParsed() throws Exception {
        Exposed connector = new Exposed(runtime, "file");
        Exchange exchange = runtime.newExchange();
        exchange.getMessage().setBody(new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        ProcessDto dto = new ProcessDto().setData("{}");

        connector.map(exchange, dto);
        assertEquals(Map.of("a", 1), dto.getJsonData().get("body"));
    }

    /** Test-only subclass to reach the protected members. */
    private static final class Exposed extends CatalogCamelConnector {
        Exposed(CamelRuntime runtime, String scheme) {
            super(runtime, scheme, "camel-" + scheme);
        }

        Exposed(CamelRuntime runtime, String scheme, Map<String, Object> uriDefaults) {
            super(runtime, scheme, "camel-" + scheme, null, uriDefaults);
        }

        String uri(ProcessDto dto, ApplicationInstall install) {
            return resolveEndpointUri(new SendRequest(dto, dto.getJsonData(), install));
        }

        void prepare(Exchange exchange, ProcessDto dto) {
            prepareExchange(exchange, new SendRequest(dto, dto.getJsonData(), null));
        }

        void map(Exchange exchange, ProcessDto dto) {
            mapResponse(exchange, new SendRequest(dto, dto.getJsonData(), null));
        }
    }
}
