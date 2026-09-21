package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.AuthorizationType;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.camel.CamelErrorMapper;
import io.orchesty.sdk.camel.RetryPolicy;
import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.process.ProcessDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamelDescriptorTest {

    @Test
    void loadsDescriptorAndBuildsRetryPolicy() {
        CamelDescriptor descriptor = CamelDescriptorLoader.loadResource("sftp-descriptor.yaml");

        assertEquals("camel", descriptor.runtime);
        assertEquals("sftp", descriptor.component);
        assertEquals("camel-sftp", descriptor.application.name);
        assertEquals("camel-sftp-upload", descriptor.connector.name);

        RetryPolicy policy = descriptor.toRetryPolicy();
        assertEquals(120, policy.getRepeatIntervalSeconds());
        assertEquals(5, policy.getRepeatMaxHops());
        assertTrue(policy.getRepeatRanges().contains(408));
    }

    @Test
    void loadsSourceDescriptorWithOutputShaping() {
        CamelDescriptor descriptor = CamelDescriptorLoader.loadResource("file-source-descriptor.yaml");

        assertTrue(descriptor.isSource());
        assertEquals("chunk", descriptor.output.mode);
        assertEquals(25, descriptor.output.chunkSize);
        assertEquals(250, descriptor.output.batchSize);
    }

    @Test
    @SuppressWarnings("unchecked")
    void descriptorApplicationRendersFormsLikeANativeApp() {
        CamelDescriptor descriptor = CamelDescriptorLoader.loadResource("sftp-descriptor.yaml");
        DescriptorApplication app = new DescriptorApplication(descriptor);

        assertEquals("camel-sftp", app.getName());
        assertEquals("Camel SFTP", app.getPublicName());
        assertEquals(AuthorizationType.BASIC, app.getAuthorizationType());

        // full app-forms path (same code Node UI hits): merge settings + limiter injection
        ApplicationInstall install =
                new ApplicationInstall().setUser("u").setName("camel-sftp").setSdk("java");
        Map<String, Object> auth = new java.util.LinkedHashMap<>();
        auth.put("host", "sftp.example.com");
        auth.put("password", "secret");
        install.addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, auth));

        Map<String, Object> forms = app.getApplicationForms(install);
        assertTrue(forms.containsKey(CoreForm.AUTHORIZATION_FORM));
        assertTrue(forms.containsKey(CoreForm.LIMITER_FORM));

        Map<String, Object> authForm = (Map<String, Object>) forms.get(CoreForm.AUTHORIZATION_FORM);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) authForm.get("fields");
        Map<String, Object> passwordField = fields.stream()
                .filter(f -> f.get("key").equals("password"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> hostField = fields.stream()
                .filter(f -> f.get("key").equals("host"))
                .findFirst()
                .orElseThrow();

        // stored values merged and round-tripped. Secrets are rendered as TEXT (by
        // convention, not PASSWORD), so the password value is returned as-is instead of
        // being masked to boolean true — this lets form re-saves keep the stored secret.
        assertEquals("sftp.example.com", hostField.get("value"));
        assertEquals("secret", passwordField.get("value"));
        assertEquals(FieldType.TEXT.value(), passwordField.get("type"));
    }

    @Test
    void applyCatalogDefaultsFillsIdentityAndForm() {
        CamelDescriptor descriptor = new CamelDescriptor();
        descriptor.component = "sftp";
        descriptor.applyCatalogDefaults();

        assertEquals("camel-sftp", descriptor.application.name);
        assertEquals("camel-sftp", descriptor.connector.name);
        assertEquals("connector", descriptor.kind);
        assertTrue(descriptor.application.publicName.toLowerCase().contains("sftp"));
        assertTrue(descriptor.application.authForm.contains("host"));
    }

    @Test
    void descriptorRepeatStatusIsNotStoppedByTheDefaults() {
        CamelDescriptor descriptor = CamelDescriptorLoader.load("component: sftp\nretry:\n  repeat: [404]\n");

        assertThrows(
                OnRepeatException.class,
                () -> CamelErrorMapper.classifyStatus(new ProcessDto(), 404, descriptor.toRetryPolicy()));
    }

    @Test
    void nullSectionsInYamlGetDefaults() {
        CamelDescriptor descriptor =
                CamelDescriptorLoader.load("component: sftp\napplication: null\nconnector: null\noutput: null\n");
        descriptor.applyCatalogDefaults();

        assertEquals("camel-sftp", descriptor.application.name);
        assertEquals("camel-sftp", descriptor.connector.name);
        assertTrue(descriptor.application.authForm.contains("host"));
    }

    @Test
    void authorizationTypeDefaultsToBasicButIsOverridable() {
        CamelDescriptor basic = new CamelDescriptor();
        basic.component = "sftp";
        basic.application.name = "a";
        assertEquals(AuthorizationType.BASIC, new DescriptorApplication(basic).getAuthorizationType());

        CamelDescriptor oauth = new CamelDescriptor();
        oauth.component = "sftp";
        oauth.application.name = "b";
        oauth.application.authorizationType = AuthorizationType.OAUTH2;
        assertEquals(AuthorizationType.OAUTH2, new DescriptorApplication(oauth).getAuthorizationType());
    }

    @Test
    void authorizationBlockSelectsOAuth2AndParsesEndpoints() {
        String yaml = """
                runtime: camel
                component: sftp
                authorization:
                  type: oauth2
                  allowManualTokens: true
                  authUrl: https://auth.example.com/authorize
                  tokenUrl: https://auth.example.com/token
                  scopes: [ "read", "write" ]
                  scopeSeparator: space
                application:
                  name: camel-x
                connector:
                  name: camel-x-conn
                """;
        CamelDescriptor d = CamelDescriptorLoader.load(yaml);

        assertTrue(d.isOAuth2());
        assertEquals(AuthorizationType.OAUTH2, d.resolveAuthorizationType());
        assertTrue(d.allowsManualTokens());
        assertEquals("https://auth.example.com/authorize", d.authorization.authUrl);
        assertEquals("https://auth.example.com/token", d.authorization.tokenUrl);
        assertEquals(List.of("read", "write"), d.authorization.scopes);
    }

    @Test
    void authorizationTypePrecedenceAndDefaults() {
        CamelDescriptor def = new CamelDescriptor();
        assertEquals(AuthorizationType.BASIC, def.resolveAuthorizationType());
        assertFalse(def.isOAuth2());
        assertFalse(def.allowsManualTokens());

        // Legacy application.authorizationType still honoured.
        CamelDescriptor legacy = new CamelDescriptor();
        legacy.application.authorizationType = AuthorizationType.OAUTH2;
        assertTrue(legacy.isOAuth2());

        // The authorization block wins over the legacy field.
        CamelDescriptor block = new CamelDescriptor();
        block.application.authorizationType = AuthorizationType.OAUTH2;
        block.authorization = new CamelDescriptor.AuthorizationSpec();
        block.authorization.type = AuthorizationType.BASIC;
        assertEquals(AuthorizationType.BASIC, block.resolveAuthorizationType());
        assertFalse(block.isOAuth2());
    }
}
