package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.Field;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.application.form.Form;
import io.orchesty.sdk.process.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;

/**
 * Turns Apache Camel Catalog component metadata into Orchesty {@link Form}s.
 *
 * <p>This is what lets a Camel component's connection options be rendered by the
 * Orchesty UI as a normal application form: option {@code type}/{@code secret}/{@code enum}/{@code required}
 * flags map to {@link FieldType} and field attributes. The catalog ships as data in
 * the {@code camel-catalog} jar, so no component jars or network are needed to generate a form.
 */
public class CatalogFormGenerator {

    private final CamelCatalog catalog;

    public CatalogFormGenerator() {
        this(new DefaultCamelCatalog());
    }

    public CatalogFormGenerator(CamelCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Generates an {@code authorization_form} for the given component and option names.
     *
     * @param component component scheme
     * @param optionNames options to include, or empty to use the Catalog's defaults
     * @return the form
     */
    public Form generateAuthorizationForm(String component, List<String> optionNames) {
        List<String> names =
                optionNames == null || optionNames.isEmpty() ? discoverAuthOptions(component) : optionNames;
        return generateForm(component, CoreForm.AUTHORIZATION_FORM, "Authorization", names);
    }

    /**
     * Catalog identity + the default authorization-field list for {@code component}.
     * Field discovery: path parameters, required options, secret options and non-advanced
     * security options. Deprecated / advanced options are skipped (unless a required path).
     */
    public CatalogComponentInfo inspect(String component) {
        JsonNode root = schema(component);
        JsonNode meta = root.get("component");
        String scheme = text(meta, "scheme", component);
        String title = text(meta, "title", scheme);
        String description = text(meta, "description", "");
        boolean consumerOnly = meta != null && meta.path("consumerOnly").asBoolean(false);
        boolean producerOnly = meta != null && meta.path("producerOnly").asBoolean(false);
        return new CatalogComponentInfo(
                scheme, title, description, consumerOnly, producerOnly, discoverAuthOptions(root));
    }

    /**
     * Returns the component's path parameters (Catalog {@code kind: path}), for example
     * {@code directoryName} of {@code file}.
     *
     * @param component component scheme
     * @return the path parameter names; empty for a component outside the Catalog
     */
    public Set<String> pathOptions(String component) {
        String json = catalog.componentJSonSchema(component);
        if (json == null || json.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        JsonNode properties = schema(component).get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                if ("path".equals(text(entry.getValue(), "kind", ""))) {
                    out.add(entry.getKey());
                }
            });
        }
        return Set.copyOf(out);
    }

    /** Option names that should pre-fill an authorization form when the descriptor omits {@code authForm}. */
    public List<String> discoverAuthOptions(String component) {
        return discoverAuthOptions(schema(component));
    }

    private List<String> discoverAuthOptions(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode properties = root.get("properties");
        if (properties == null || !properties.isObject()) {
            return out;
        }
        properties.fields().forEachRemaining(entry -> {
            if (includeInAuthForm(entry.getValue())) {
                out.add(entry.getKey());
            }
        });
        return out;
    }

    private static boolean includeInAuthForm(JsonNode meta) {
        if (meta.path("deprecated").asBoolean(false)) {
            return false;
        }
        String group = text(meta, "group", "");
        String label = text(meta, "label", "");
        boolean advanced = containsIgnoreCase(group, "advanced") || containsIgnoreCase(label, "advanced");
        boolean path = "path".equals(text(meta, "kind", ""));
        boolean required = meta.path("required").asBoolean(false);
        if (advanced && !(path && required)) {
            return false;
        }
        if (path || required || meta.path("secret").asBoolean(false)) {
            return true;
        }
        return containsIgnoreCase(group, "security");
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private JsonNode schema(String component) {
        String json = catalog.componentJSonSchema(component);
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("Unknown Camel component: " + component);
        }
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Camel catalog schema for " + component, e);
        }
    }

    public Form generateForm(String component, String formKey, String publicName, List<String> optionNames) {
        JsonNode root = schema(component);
        JsonNode properties = root.get("properties");
        JsonNode componentProperties = root.get("componentProperties");

        Form form = new Form(formKey, publicName);
        for (String option : optionNames) {
            JsonNode meta = pick(properties, option);
            if (meta == null) {
                meta = pick(componentProperties, option);
            }
            if (meta == null) {
                throw new IllegalArgumentException(
                        "Option [" + option + "] not found on Camel component [" + component + "]");
            }
            form.addField(toField(option, meta));
        }
        return form;
    }

    private static JsonNode pick(JsonNode container, String name) {
        return container != null && container.has(name) ? container.get(name) : null;
    }

    private Field toField(String key, JsonNode meta) {
        String type = text(meta, "type", "string");
        boolean secret = meta.path("secret").asBoolean(false);
        boolean required = meta.path("required").asBoolean(false);
        String description = text(meta, "description", "");
        String label = text(meta, "displayName", humanize(key));

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

    private static String text(JsonNode node, String field, String def) {
        if (node == null) {
            return def;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }

    private static String humanize(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
