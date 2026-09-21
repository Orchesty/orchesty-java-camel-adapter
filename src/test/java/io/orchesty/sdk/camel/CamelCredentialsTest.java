package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CamelCredentials} normalizes both credential shapes into Camel endpoint options:
 * BASIC (flat camelCase keys) and OAuth2 (OAuth-standard client keys + a nested flow token),
 * while dropping UI-only keys.
 */
class CamelCredentialsTest {

    private ApplicationInstall installWith(Map<String, Object> authForm) {
        return new ApplicationInstall().addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, authForm));
    }

    @Test
    void basicFlatFieldsPassThrough() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("applicationName", "orchesty");
        auth.put("clientId", "cid");
        auth.put("clientSecret", "csec");
        auth.put("accessToken", "atok");
        auth.put("refreshToken", "rtok");
        auth.put("spreadsheetId", "SHEET1");
        auth.put("range", "A1:B2");

        Map<String, Object> out = CamelCredentials.resolve(installWith(auth));

        assertEquals("cid", out.get("clientId"));
        assertEquals("csec", out.get("clientSecret"));
        assertEquals("atok", out.get("accessToken"));
        assertEquals("rtok", out.get("refreshToken"));
        assertEquals("SHEET1", out.get("spreadsheetId"));
        assertEquals("A1:B2", out.get("range"));
        assertEquals("orchesty", out.get("applicationName"));
    }

    @Test
    void oauthClientKeysMappedTokenExtractedAndUiKeysDropped() {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("accessToken", "AT"); // IToken.toMap keys
        token.put("refreshToken", "RT");

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("client_id", "cid"); // OAuth-standard keys
        auth.put("client_secret", "csec");
        auth.put("applicationName", "orchesty");
        auth.put("spreadsheetId", "SHEET1");
        auth.put("range", "A1:B2");
        auth.put("token", token);
        auth.put("frontend_redirect_url", "http://front");
        auth.put("redirect_url", "http://redir");
        auth.put("oauth_state_nonce", "nonce");

        Map<String, Object> out = CamelCredentials.resolve(installWith(auth));

        // OAuth client keys mapped to Camel option names, standard keys removed.
        assertEquals("cid", out.get("clientId"));
        assertEquals("csec", out.get("clientSecret"));
        assertFalse(out.containsKey("client_id"));
        assertFalse(out.containsKey("client_secret"));

        // Flow token surfaced as accessToken/refreshToken.
        assertEquals("AT", out.get("accessToken"));
        assertEquals("RT", out.get("refreshToken"));

        // Config passes through; UI-only keys dropped.
        assertEquals("SHEET1", out.get("spreadsheetId"));
        assertFalse(out.containsKey("token"));
        assertFalse(out.containsKey("frontend_redirect_url"));
        assertFalse(out.containsKey("redirect_url"));
        assertFalse(out.containsKey("oauth_state_nonce"), "the OAuth nonce never reaches an endpoint URI");
    }

    @Test
    void flowTokenWinsOverManualTokens() {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("accessToken", "FLOW");

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("accessToken", "MANUAL"); // manual fallback field
        auth.put("token", token); // flow token present

        Map<String, Object> out = CamelCredentials.resolve(installWith(auth));
        assertEquals("FLOW", out.get("accessToken"));
    }

    @Test
    void textTokenIsAnOrdinaryOption() {
        Map<String, Object> out = CamelCredentials.resolve(installWith(Map.of("token", "xoxb-1")));
        assertEquals("xoxb-1", out.get("token"), "for example camel-slack's token option");
    }

    @Test
    void installValuesThatCamelWouldEvaluateAreRejected() {
        for (String value : new String[] {"{{sys:user.home}}", "#class:java.lang.Object", "RAW(x)"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CamelCredentials.resolve(installWith(Map.of("username", value))),
                    value);
        }
        Map<String, Object> token = Map.of("accessToken", "{{env:X}}");
        assertThrows(
                IllegalArgumentException.class, () -> CamelCredentials.resolve(installWith(Map.of("token", token))));
    }

    @Test
    void nullInstallReturnsEmpty() {
        assertTrue(CamelCredentials.resolve(null).isEmpty());
    }
}
