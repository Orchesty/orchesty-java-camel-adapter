package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a {@link KameletDefinition} from a Kamelet YAML. Kamelets are not part of
 * the Camel Catalog; their configurable parameters live in {@code spec.definition}
 * (a JSON-schema block), so the YAML is parsed directly.
 *
 * <p>By convention the definitions are on the classpath under
 * {@code kamelets/<name>.kamelet.yaml} (the same place the runtime KameletComponent
 * discovers them).
 */
public final class KameletDefinitionLoader {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    /** A template parameter placeholder: {@code {{name}}}, {@code {{?name}}} or {@code {{name:default}}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\??([A-Za-z0-9_.-]+)(?::[^}]*)?}}");

    /** Expression languages of the YAML DSL: a parameter below one of them ends up in an expression. */
    private static final Set<String> LANGUAGES = Set.of(
            "simple",
            "constant",
            "jq",
            "jsonpath",
            "groovy",
            "xpath",
            "header",
            "exchangeProperty",
            "variable",
            "js",
            "mvel",
            "ognl",
            "spel",
            "tokenize",
            "method",
            "datasonnet",
            "xquery",
            "java",
            "csimple",
            "joor",
            "python");

    /** Processors that evaluate their expression into one endpoint URI. */
    private static final Set<String> DYNAMIC_URI_PROCESSORS = Set.of("enrich", "pollEnrich");

    /** Keys whose value Camel evaluates into an endpoint URI at runtime when it contains {@code ${...}}. */
    private static final Set<String> RUNTIME_URI_KEYS = Set.of("uri", "toD", "wireTap");

    /** Processors that evaluate their expression into a delimited list of endpoint URIs. */
    private static final Set<String> ENDPOINT_LIST_PROCESSORS = Set.of("recipientList", "routingSlip", "dynamicRouter");

    private KameletDefinitionLoader() {}

    public static KameletDefinition load(InputStream in) {
        try {
            JsonNode root = MAPPER.readTree(in);
            JsonNode metadata = root.path("metadata");
            JsonNode definition = root.path("spec").path("definition");

            String name = metadata.path("name").asText("");
            String title = definition.path("title").asText(name);
            String description = definition.path("description").asText("");
            JsonNode properties = definition.get("properties");

            Set<String> required = new LinkedHashSet<>();
            JsonNode req = definition.get("required");
            if (req != null && req.isArray()) {
                req.forEach(n -> required.add(n.asText()));
            }
            UriParameters uri = new UriParameters();
            uri.collect(root.path("spec").path("template"), Place.URI, "");
            uri.widen(properties);
            return new KameletDefinition(name, title, description, properties, required, uri.uri, uri.list);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid kamelet definition: " + e.getMessage(), e);
        }
    }

    /** Where a template value ends up. */
    private enum Place {
        /** Outside an expression, for example in an endpoint URI. */
        URI,
        /** The option map of an endpoint ({@code parameters}): appended to the URI, keys are option names. */
        OPTIONS,
        /** In an expression whose result is an endpoint URI ({@code enrich}, {@code pollEnrich}). */
        DYNAMIC_URI,
        /** In an expression whose result is a list of endpoint URIs ({@code recipientList}, …). */
        ENDPOINT_LIST,
        /** In an ordinary expression. */
        EXPRESSION
    }

    /**
     * Collects the parameters ({@code {{name}}}, {@code {{?name}}}, {@code {{name:default}}}) that end
     * up in an endpoint URI. Camel substitutes them as plain text: anything outside an expression
     * counts (including endpoint {@code parameters}, whatever the option is called), and so do
     * expressions that {@code enrich}, {@code pollEnrich}, {@code recipientList}, {@code routingSlip}
     * and {@code dynamicRouter} turn into endpoint URIs. The last three split their result into
     * several URIs, so their parameters are also collected in {@link #list}.
     *
     * <p>A template can also move a parameter into a header or variable and build a URI from it at
     * runtime. When it builds URIs at runtime at all ({@code ${...}} in a {@code uri}, {@code toD} or
     * {@code wireTap}, or one of the processors above), every declared parameter counts.
     */
    private static final class UriParameters {

        private final Set<String> uri = new LinkedHashSet<>();
        private final Set<String> list = new LinkedHashSet<>();
        private boolean dynamicUri;
        private boolean endpointList;

        void collect(JsonNode node, Place place, String key) {
            if (node.isTextual()) {
                if (place != Place.EXPRESSION) {
                    String text = node.asText();
                    if (RUNTIME_URI_KEYS.contains(key) && text.contains("${")) {
                        dynamicUri = true;
                    }
                    Matcher m = PLACEHOLDER.matcher(text);
                    while (m.find()) {
                        uri.add(m.group(1));
                        if (place == Place.ENDPOINT_LIST) {
                            list.add(m.group(1));
                        }
                    }
                }
            } else if (node.isObject()) {
                node.fields().forEachRemaining(e -> collect(e.getValue(), next(place, e.getKey()), e.getKey()));
            } else if (node.isArray()) {
                node.forEach(child -> collect(child, place, key));
            }
        }

        /** Counts every declared parameter when the template builds URIs at runtime. */
        void widen(JsonNode properties) {
            if (properties == null || !properties.isObject() || !(dynamicUri || endpointList)) {
                return;
            }
            properties.fieldNames().forEachRemaining(name -> {
                uri.add(name);
                if (endpointList) {
                    list.add(name);
                }
            });
        }

        private Place next(Place place, String key) {
            if (place == Place.EXPRESSION) {
                return place;
            }
            if (place == Place.OPTIONS) {
                return Place.URI; // an option name, even one called like a language (method, header, …)
            }
            if (ENDPOINT_LIST_PROCESSORS.contains(key)) {
                endpointList = true;
                return Place.ENDPOINT_LIST;
            }
            if (DYNAMIC_URI_PROCESSORS.contains(key)) {
                dynamicUri = true;
                return place == Place.URI ? Place.DYNAMIC_URI : place;
            }
            if ("parameters".equals(key)) {
                return Place.OPTIONS;
            }
            if (LANGUAGES.contains(key) && place == Place.URI) {
                return Place.EXPRESSION;
            }
            return place;
        }
    }

    /**
     * Loads {@code kamelets/<name>.kamelet.yaml} from the classpath.
     *
     * @param name Kamelet name
     * @return the parsed definition
     */
    public static KameletDefinition loadByName(String name) {
        String resource = "kamelets/" + name + ".kamelet.yaml";
        try (InputStream in = KameletDefinitionLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Kamelet definition not found on classpath: " + resource);
            }
            return load(in);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid kamelet definition: " + e.getMessage(), e);
        }
    }
}
