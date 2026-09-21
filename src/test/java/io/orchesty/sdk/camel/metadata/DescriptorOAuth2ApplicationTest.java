package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.AuthorizationType;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.authorization.IToken;
import io.orchesty.sdk.authorization.OAuth2Provider;
import io.orchesty.sdk.camel.CamelCredentials;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The OAuth2 descriptor application: reports type/endpoints from the descriptor, renders the
 * config form + OAuth {@code client_id}/{@code client_secret} + read-only redirect URL, and
 * resolves "authorized" from either the redirect-flow token or (when allowed) manual tokens.
 * The Camel {@code sftp} component is used as an arbitrary source of catalog config fields.
 */
class DescriptorOAuth2ApplicationTest {

    private static final OAuth2Provider PROVIDER = new OAuth2Provider("http://backend", null);

    private CamelDescriptor descriptor(boolean allowManualTokens) {
        CamelDescriptor d = new CamelDescriptor();
        d.component = "sftp";
        d.kind = "source";
        d.application.name = "camel-x";
        d.application.publicName = "X";
        d.application.authForm = List.of("host");
        d.authorization = new CamelDescriptor.AuthorizationSpec();
        d.authorization.type = AuthorizationType.OAUTH2;
        d.authorization.allowManualTokens = allowManualTokens;
        d.authorization.authUrl = "https://auth/authorize";
        d.authorization.tokenUrl = "https://auth/token";
        d.authorization.scopes = List.of("scope1", "scope2");
        return d;
    }

    private ApplicationInstall install() {
        return new ApplicationInstall().setUser("u").setName("camel-x").setSdk("java");
    }

    @Test
    void reportsOAuth2TypeAndEndpoints() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(false), PROVIDER);
        assertEquals(AuthorizationType.OAUTH2, app.getAuthorizationType());
        assertEquals("https://auth/authorize", app.getAuthUrl());
        assertEquals("https://auth/token", app.getTokenUrl());
        assertEquals(List.of("scope1", "scope2"), app.getScopes(install()));
    }

    @Test
    void formHasConfigClientCredentialsAndRedirectUrl() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(false), PROVIDER);

        Set<String> keys = fieldKeys(app);
        assertTrue(keys.contains("host"), keys.toString()); // catalog config field
        assertTrue(keys.contains("client_id"), keys.toString()); // OAuth credential
        assertTrue(keys.contains("client_secret"), keys.toString());
        assertTrue(keys.contains("redirect_url"), keys.toString()); // added by AOAuth2Application
        assertFalse(keys.contains("accessToken"), keys.toString()); // manual disabled
        assertFalse(keys.contains("refreshToken"), keys.toString());
    }

    @Test
    void catalogClientAndTokenOptionsAreNotDuplicated() {
        CamelDescriptor d = descriptor(true);
        d.component = "google-sheets-stream";
        d.application.authForm = List.of("applicationName", "clientId", "clientSecret", "accessToken", "refreshToken");

        List<String> keys = new DescriptorOAuth2Application(d, PROVIDER)
                .getFormStack().getForms().get(0).getFields().stream()
                        .map(f -> f.getKey())
                        .toList();
        assertEquals(Set.copyOf(keys).size(), keys.size(), keys.toString());
        assertTrue(keys.contains("applicationName"), keys.toString());
        assertFalse(keys.contains("clientId"), keys.toString());
        assertTrue(keys.contains("client_id"), keys.toString());
        assertTrue(keys.contains("accessToken"), keys.toString());
    }

    @Test
    void manualTokenFieldsRenderedWhenAllowed() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(true), PROVIDER);
        Set<String> keys = fieldKeys(app);
        assertTrue(keys.contains("accessToken"), keys.toString());
        assertTrue(keys.contains("refreshToken"), keys.toString());
    }

    @Test
    void authorizedViaFlowToken() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(false), PROVIDER);
        ApplicationInstall install = install();

        Map<String, Object> authForm = new LinkedHashMap<>();
        authForm.put(CamelCredentials.TOKEN, new IToken().setAccessToken("AT").toMap());
        install.addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, authForm));

        assertTrue(app.isAuthorized(install));
    }

    @Test
    void notAuthorizedWithoutTokenAndManualDisabled() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(false), PROVIDER);
        ApplicationInstall install = install();
        // Manual accessToken present but allowManualTokens=false -> still not authorized.
        install.addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("accessToken", "manual")));
        assertFalse(app.isAuthorized(install));
    }

    @Test
    void authorizedViaManualTokenWhenAllowed() {
        DescriptorOAuth2Application app = new DescriptorOAuth2Application(descriptor(true), PROVIDER);
        ApplicationInstall install = install();
        install.addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("accessToken", "manual-token")));

        assertTrue(app.isAuthorized(install));
        // getTokens falls back to the manual field so getAccessToken keeps working.
        assertEquals("manual-token", app.getTokens(install).getAccessToken());
    }

    @SuppressWarnings("unchecked")
    private Set<String> fieldKeys(DescriptorOAuth2Application app) {
        Map<String, Object> forms = app.getApplicationForms(install());
        Map<String, Object> authForm = (Map<String, Object>) forms.get(CoreForm.AUTHORIZATION_FORM);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) authForm.get("fields");
        return fields.stream().map(f -> String.valueOf(f.get("key"))).collect(Collectors.toSet());
    }
}
