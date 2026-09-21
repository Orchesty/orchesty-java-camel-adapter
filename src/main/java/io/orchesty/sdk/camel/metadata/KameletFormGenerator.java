package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.Field;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.application.form.Form;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a Kamelet {@code spec.definition} into an Orchesty {@link Form}, so a
 * Kamelet's parameters render in the UI exactly like a hand-written application
 * form. Mirrors {@link CatalogFormGenerator} but reads the kamelet JSON-schema
 * instead of the Camel Catalog.
 *
 * <p>Types are mapped by {@link FieldTypeMapper}: {@code boolean} becomes {@code CHECKBOX},
 * {@code enum} becomes {@code SELECT_BOX}, {@code integer} and {@code number} become
 * {@code NUMBER}, everything else, secrets included ({@code format: password} or a password
 * {@code x-descriptors}), becomes {@code TEXT}; see {@link FieldTypeMapper} for why secrets are
 * not {@code PASSWORD}. {@code required} comes from the definition's top-level
 * {@code required} array.
 */
public class KameletFormGenerator {

    /**
     * Generates an {@code authorization_form} for the given Kamelet and property names.
     *
     * @param def Kamelet definition
     * @param optionNames properties to include, or empty to use the definition's defaults
     * @return the form
     */
    public Form generateAuthorizationForm(KameletDefinition def, List<String> optionNames) {
        List<String> names = optionNames == null || optionNames.isEmpty() ? def.discoverAuthOptions() : optionNames;
        return generateForm(def, CoreForm.AUTHORIZATION_FORM, "Authorization", names);
    }

    public Form generateForm(KameletDefinition def, String formKey, String publicName, List<String> optionNames) {
        Form form = new Form(formKey, publicName);
        for (String option : optionNames) {
            JsonNode meta = def.getProperty(option);
            if (meta == null) {
                throw new IllegalArgumentException(
                        "Property [" + option + "] not found in kamelet [" + def.getName() + "]");
            }
            form.addField(toField(option, meta, def.isRequired(option)));
        }
        return form;
    }

    private Field toField(String key, JsonNode meta, boolean required) {
        String type = text(meta, "type", "string");
        String format = text(meta, "format", "");
        boolean secret = "password".equals(format) || hasPasswordDescriptor(meta);
        String description = text(meta, "description", "");
        String label = text(meta, "title", humanize(key));

        List<Object> choices = new ArrayList<>();
        if (meta.has("enum") && meta.get("enum").isArray()) {
            meta.get("enum").forEach(n -> choices.add(n.asText()));
        }

        FieldType fieldType = FieldTypeMapper.map(type, secret, !choices.isEmpty());

        Field field = new Field(fieldType, key, label, null, required).setDescription(description);
        if (!choices.isEmpty()) {
            field.setChoices(choices);
        }
        return field;
    }

    private static boolean hasPasswordDescriptor(JsonNode meta) {
        JsonNode d = meta.get("x-descriptors");
        if (d != null && d.isArray()) {
            for (JsonNode n : d) {
                if (n.asText("").contains("password")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field, String def) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }

    private static String humanize(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
