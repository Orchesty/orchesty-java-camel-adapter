package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.Field;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.application.form.Form;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CatalogFormGeneratorTest {

    private final CatalogFormGenerator generator = new CatalogFormGenerator();

    @Test
    void generatesSftpAuthFormFromCatalogMetadata() {
        Form form = generator.generateAuthorizationForm("sftp", List.of("host", "port", "username", "password"));

        assertEquals(CoreForm.AUTHORIZATION_FORM, form.getKey());
        Map<String, Field> byKey = form.getFields().stream().collect(Collectors.toMap(Field::getKey, f -> f));

        // password + username are secret in the catalog, but by convention secrets are
        // rendered as TEXT (never PASSWORD, which would round-trip masked as a boolean).
        assertEquals(FieldType.TEXT, byKey.get("password").getType());
        assertEquals(FieldType.TEXT, byKey.get("username").getType());
        // port is an integer option -> NUMBER
        assertEquals(FieldType.NUMBER, byKey.get("port").getType());
        // host is a plain string -> TEXT
        assertEquals(FieldType.TEXT, byKey.get("host").getType());
        // host is required in the catalog
        assertTrue(byKey.get("host").isRequired());

        // descriptions come straight from the Camel catalog
        assertTrue(byKey.get("host").getDescription() != null
                && !byKey.get("host").getDescription().isEmpty());
    }

    @Test
    void generatesFileSourceFormFromCatalog() {
        // A polling source (kind: source) still generates its form from the component catalog.
        Form form =
                generator.generateAuthorizationForm("file", List.of("directoryName", "delete", "recursive", "include"));
        Map<String, Field> byKey = form.getFields().stream().collect(Collectors.toMap(Field::getKey, f -> f));

        // directoryName is the path option -> TEXT and required
        assertEquals(FieldType.TEXT, byKey.get("directoryName").getType());
        assertTrue(byKey.get("directoryName").isRequired());
        // delete/recursive are booleans -> CHECKBOX
        assertEquals(FieldType.CHECKBOX, byKey.get("delete").getType());
        assertEquals(FieldType.CHECKBOX, byKey.get("recursive").getType());
        // include is a plain string filter -> TEXT
        assertEquals(FieldType.TEXT, byKey.get("include").getType());
    }

    @Test
    void generatesGoogleSheetsOauthFormFromCatalog() {
        // OAuth-credentialed source: secret tokens are rendered as TEXT (never PASSWORD),
        // so their values round-trip instead of being masked to a boolean.
        Form form = generator.generateAuthorizationForm(
                "google-sheets-stream",
                List.of(
                        "applicationName",
                        "clientId",
                        "clientSecret",
                        "accessToken",
                        "refreshToken",
                        "spreadsheetId",
                        "range"));
        Map<String, Field> byKey = form.getFields().stream().collect(Collectors.toMap(Field::getKey, f -> f));

        // secrets -> TEXT (by convention, not PASSWORD)
        assertEquals(FieldType.TEXT, byKey.get("clientSecret").getType());
        assertEquals(FieldType.TEXT, byKey.get("accessToken").getType());
        assertEquals(FieldType.TEXT, byKey.get("refreshToken").getType());
        // non-secret creds/config -> TEXT
        assertEquals(FieldType.TEXT, byKey.get("clientId").getType());
        assertEquals(FieldType.TEXT, byKey.get("applicationName").getType());
        // spreadsheetId is the required path option
        assertEquals(FieldType.TEXT, byKey.get("spreadsheetId").getType());
        assertTrue(byKey.get("spreadsheetId").isRequired());
    }

    @Test
    void discoverAuthOptionsPicksPathAndSecurityFields() {
        java.util.List<String> options = generator.discoverAuthOptions("sftp");
        assertTrue(options.contains("host"), options.toString());
        assertTrue(options.contains("username"), options.toString());
        assertTrue(options.contains("password"), options.toString());
        // Advanced knobs stay out of the default form.
        assertTrue(options.stream().noneMatch(o -> o.toLowerCase().contains("buffer")), options.toString());
    }

    @Test
    void discoverAuthOptionsIncludesSecretsOutsideTheSecurityGroup() {
        List<String> options = generator.discoverAuthOptions("slack");
        assertTrue(options.contains("token"), "slack's token is secret in group common: " + options);
        assertFalse(options.contains("username"), "deprecated options stay out: " + options);
    }

    @Test
    void inspectMarksGoogleSheetsStreamAsConsumerOnly() {
        CatalogComponentInfo info = generator.inspect("google-sheets-stream");
        assertTrue(info.consumerOnly());
        assertTrue(
                info.authOptions().contains("spreadsheetId"), info.authOptions().toString());
    }

    @Test
    void enumOptionBecomesSelectBoxWithChoices() {
        // sftp has 'separator' with enum values (UNIX, Windows, Auto)
        Form form = generator.generateForm("sftp", "extra", "Extra", List.of("separator"));
        Field separator = form.getFields().get(0);
        assertEquals(FieldType.SELECT_BOX, separator.getType());
        assertTrue(separator.getChoices().contains("UNIX"));
    }
}
