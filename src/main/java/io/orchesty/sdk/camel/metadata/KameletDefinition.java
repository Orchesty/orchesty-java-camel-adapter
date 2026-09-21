package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parsed subset of a Kamelet YAML ({@code spec.definition}) needed to render an
 * Orchesty form: the kamelet name, human title/description and the JSON-schema
 * {@code properties} + top-level {@code required} list.
 */
public class KameletDefinition {

    private final String name;
    private final String title;
    private final String description;
    private final JsonNode properties;
    private final Set<String> required;
    private final Set<String> uriParameters;
    private final Set<String> endpointListParameters;

    public KameletDefinition(String name, String title, String description, JsonNode properties, Set<String> required) {
        this(name, title, description, properties, required, Set.of(), Set.of());
    }

    public KameletDefinition(
            String name,
            String title,
            String description,
            JsonNode properties,
            Set<String> required,
            Set<String> uriParameters,
            Set<String> endpointListParameters) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.properties = properties == null ? MissingNode.getInstance() : properties;
        this.required = required;
        this.uriParameters = uriParameters == null ? Set.of() : Set.copyOf(uriParameters);
        this.endpointListParameters = endpointListParameters == null ? Set.of() : Set.copyOf(endpointListParameters);
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /** The {@code spec.definition.properties} node (a JSON-schema object). */
    public JsonNode getProperties() {
        return properties;
    }

    public JsonNode getProperty(String key) {
        return properties.has(key) ? properties.get(key) : null;
    }

    public boolean isRequired(String key) {
        return required.contains(key);
    }

    /** Property keys in definition order. */
    public List<String> getPropertyNames() {
        List<String> names = new ArrayList<>();
        if (properties.isObject()) {
            properties.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    /**
     * Parameters that end up in an endpoint URI: used outside an expression (endpoint
     * {@code parameters} included), or in an expression that {@code enrich}, {@code pollEnrich},
     * {@code recipientList}, {@code routingSlip} or {@code dynamicRouter} turn into a URI. A
     * template that builds URIs at runtime ({@code toD}, {@code ${...}} in a URI or one of those
     * processors) may move any parameter there through a header, so then every declared parameter
     * counts. Camel substitutes them as plain text and decodes
     * percent-escapes when it parses the URI, so their values must not contain {@code ?},
     * {@code &} or a percent-escape.
     *
     * @return the parameter names
     */
    public Set<String> getUriParameterNames() {
        return uriParameters;
    }

    /**
     * Parameters in an expression that {@code recipientList}, {@code routingSlip} or
     * {@code dynamicRouter} split into several endpoint URIs at a configurable delimiter; their
     * values may contain only letters, digits and {@code . _ ~ - / : @}.
     *
     * @return the parameter names
     */
    public Set<String> getEndpointListParameterNames() {
        return endpointListParameters;
    }

    /**
     * Form fields when the descriptor omits {@code authForm}: the {@code required}
     * list, or every property when the kamelet declares none.
     */
    public List<String> discoverAuthOptions() {
        return required.isEmpty() ? getPropertyNames() : new ArrayList<>(required);
    }
}
