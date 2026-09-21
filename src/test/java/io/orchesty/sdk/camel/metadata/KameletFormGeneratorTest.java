package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.Field;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.application.form.Form;
import io.orchesty.sdk.application.form.FormStack;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KameletFormGeneratorTest {

    @Test
    void mapsKameletDefinitionToFormFields() {
        KameletDefinition def = KameletDefinitionLoader.loadByName("rich-config");
        Form form = new KameletFormGenerator()
                .generateAuthorizationForm(def, List.of("apiKey", "pageSize", "mode", "label"));

        assertEquals(CoreForm.AUTHORIZATION_FORM, form.getKey());
        Map<String, Field> byKey = form.getFields().stream().collect(Collectors.toMap(Field::getKey, f -> f));

        // format: password is secret, but by convention secrets render as TEXT (never
        // PASSWORD, which would round-trip masked as a boolean); required flag is kept.
        assertEquals(FieldType.TEXT, byKey.get("apiKey").getType());
        assertTrue(byKey.get("apiKey").isRequired());
        // integer -> NUMBER
        assertEquals(FieldType.NUMBER, byKey.get("pageSize").getType());
        // enum -> SELECT_BOX with choices
        assertEquals(FieldType.SELECT_BOX, byKey.get("mode").getType());
        assertTrue(byKey.get("mode").getChoices().contains("ROWS"));
        // plain string -> TEXT, not required
        assertEquals(FieldType.TEXT, byKey.get("label").getType());
        assertFalse(byKey.get("label").isRequired());
        // title/description carried over from the kamelet definition
        assertEquals("API Key", byKey.get("apiKey").getLabel());
    }

    @Test
    void descriptorApplicationRendersKameletForm() {
        CamelDescriptor descriptor = new CamelDescriptor();
        descriptor.kamelet = "rich-config";
        descriptor.application.name = "rich";
        descriptor.application.publicName = "Rich";
        descriptor.application.authForm = List.of("apiKey", "pageSize");

        DescriptorApplication app = new DescriptorApplication(descriptor);
        FormStack stack = app.getFormStack();

        Form auth = stack.getForms().stream()
                .filter(f -> CoreForm.AUTHORIZATION_FORM.equals(f.getKey()))
                .findFirst()
                .orElseThrow();
        Map<String, Field> byKey = auth.getFields().stream().collect(Collectors.toMap(Field::getKey, f -> f));
        assertEquals(FieldType.TEXT, byKey.get("apiKey").getType());
        assertEquals(FieldType.NUMBER, byKey.get("pageSize").getType());
    }
}
