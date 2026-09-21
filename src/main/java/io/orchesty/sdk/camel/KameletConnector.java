package io.orchesty.sdk.camel;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.camel.metadata.KameletDefinition;
import io.orchesty.sdk.camel.metadata.KameletDefinitionLoader;
import io.orchesty.sdk.process.Json;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.util.URISupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an action/sink Kamelet as a one-shot Orchesty connector via Camel's
 * {@code kamelet:} producer (see {@link CamelRuntime#sendKamelet}). The Kamelet component
 * materializes one route from the template per distinct parameter set; the runtime keeps a
 * bounded number of them.
 *
 * <p>Kamelet parameters are collected from two sources and passed as URI query
 * params (which bind to the template's {@code {{property}}} placeholders):
 * <ul>
 *   <li><b>static</b> config/credentials from the application's {@code authorization_form}
 *       (stored encrypted, decrypted on load, normalized by {@link CamelCredentials}), and</li>
 *   <li><b>dynamic</b> per-message params from the payload under {@code kameletParameters}; they
 *       only add parameters the install leaves unset, never override it.</li>
 * </ul>
 * Only the properties the Kamelet definition declares are passed. Undeclared install keys are
 * skipped; an undeclared payload key, a Camel endpoint option such as {@code templateId} or
 * {@code location}, a value {@link UntrustedValues} rejects (a property placeholder, a simple
 * expression, a Camel reference or a raw value) or a value that cannot be passed verbatim stops
 * the message. Camel substitutes parameters into the template as plain text, so a parameter the
 * template places in an endpoint URI (see {@link KameletDefinition#getUriParameterNames()}) must
 * not contain {@code ?}, {@code &} or a percent-escape: it would add options to that endpoint. A
 * parameter in a recipient list or routing slip may contain only letters, digits and
 * {@code . _ ~ - / : @}.
 *
 * <p>The message body comes from the payload {@code body}. Retry stays Orchesty-owned
 * (see {@link RetryPolicy}); only sink/action kamelets are supported (source
 * kamelets are consumers/triggers and are out of scope).
 *
 * <p><b>Tenants share the components inside Kamelet routes.</b> The endpoints a template uses are
 * resolved on the context's shared components, so register only Kamelets whose components cache
 * no tenant client or credentials on the component.
 */
public class KameletConnector extends ACamelConnector {

    /** Payload key with the per-message Kamelet parameters. */
    public static final String PAYLOAD_KAMELET_PARAMETERS = "kameletParameters";

    /**
     * Kamelet endpoint options and parameters the Kamelet component consumes itself (lower case);
     * they are never accepted as template parameters.
     */
    static final Set<String> RESERVED_PARAMETERS = Set.of(
            "templateid",
            "routeid",
            "location",
            "uuid",
            "block",
            "timeout",
            "failifnoconsumers",
            "noerrorhandler",
            "bridgeerrorhandler",
            "exceptionhandler",
            "exchangepattern",
            "lazystartproducer",
            "parentrouteid",
            "parentprocessorid");

    private static final Logger LOG = LoggerFactory.getLogger(KameletConnector.class);

    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A percent-escape, which Camel decodes when it parses an endpoint URI. */
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9A-Fa-f]{2}");

    /** The only characters a parameter in a list of endpoint URIs may contain. */
    private static final Pattern ENDPOINT_LIST_VALUE = Pattern.compile("[A-Za-z0-9._~/:@-]*");

    private final String name;
    private final String templateId;
    private final String applicationName;
    private final Set<String> parameterNames;
    private final Set<String> uriParameterNames;
    private final Set<String> endpointListParameterNames;

    /**
     * Creates the connector and loads {@code kamelets/<templateId>.kamelet.yaml} from the
     * classpath to learn the declared parameters.
     *
     * @param runtime Camel runtime
     * @param name connector name
     * @param templateId Kamelet name
     * @param applicationName application whose install supplies the static parameters, or {@code null}
     */
    public KameletConnector(CamelRuntime runtime, String name, String templateId, String applicationName) {
        super(runtime);
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw new IllegalArgumentException("Invalid Kamelet name [" + templateId + "].");
        }
        this.name = name;
        this.templateId = templateId;
        this.applicationName = applicationName;
        Set<String> names = new LinkedHashSet<>();
        KameletDefinition definition = KameletDefinitionLoader.loadByName(templateId);
        this.uriParameterNames = definition.getUriParameterNames();
        this.endpointListParameterNames = definition.getEndpointListParameterNames();
        for (String property : definition.getPropertyNames()) {
            if (isReserved(property)) {
                LOG.warn("Kamelet [{}] declares the reserved parameter [{}]; it is ignored.", templateId, property);
            } else {
                names.add(property);
            }
        }
        this.parameterNames = Set.copyOf(names);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getApplicationName() {
        return applicationName;
    }

    @Override
    protected String resolveEndpointUri(SendRequest request) {
        Map<String, String> params = new LinkedHashMap<>();

        // Static params from the application's authorization form; undeclared keys are skipped.
        CamelCredentials.resolve(request.install()).forEach((k, v) -> {
            if (parameterNames.contains(k)) {
                params.put(k, String.valueOf(v));
            }
        });

        // Dynamic per-message params from the payload; they never override the install.
        Object dyn = request.data().get(PAYLOAD_KAMELET_PARAMETERS);
        if (dyn instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (!parameterNames.contains(key)) {
                    throw new IllegalArgumentException("Kamelet [" + templateId + "] has no parameter [" + key + "] ("
                            + PAYLOAD_KAMELET_PARAMETERS + ").");
                }
                if (v != null) {
                    params.putIfAbsent(key, v instanceof String s ? s : Json.toString(v));
                }
            });
        }

        params.forEach(this::checkUriPlacement);

        StringBuilder uri = new StringBuilder("kamelet:").append(templateId);
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            uri.append(first ? '?' : '&');
            first = false;
            uri.append(e.getKey()).append('=').append(raw(e.getKey(), e.getValue()));
        }
        String built = uri.toString();
        verify(built, params);
        return built;
    }

    /**
     * A parameter that ends up in an endpoint URI must not add options to it: Camel substitutes
     * it as text and decodes percent-escapes when it parses the URI, so {@code ?}, {@code &} and
     * percent-escapes (such as {@code %3F}) are rejected. A parameter that a recipient list or
     * routing slip splits into several URIs may contain only letters, digits and
     * {@code . _ ~ - / : @}, so it cannot add another endpoint.
     */
    private void checkUriPlacement(String key, String value) {
        if (endpointListParameterNames.contains(key)
                && !ENDPOINT_LIST_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Kamelet parameter [" + key
                    + "] is used in a list of endpoint URIs and may contain only letters, digits and . _ ~ - / : @.");
        }
        if (uriParameterNames.contains(key)
                && (value.indexOf('?') >= 0
                        || value.indexOf('&') >= 0
                        || PERCENT_ESCAPE.matcher(value).find())) {
            throw new IllegalArgumentException("Kamelet parameter [" + key
                    + "] is used in an endpoint URI and must not contain ?, & or a percent-escape.");
        }
    }

    /**
     * Camel 4.20+ hands kamelet URI parameters to the template without URL-decoding them
     * ({@code %21} would reach {@code {{suffix}}} literally), so values are passed verbatim
     * with Camel's {@code RAW{...}} syntax, or {@code RAW(...)} when the value could close the
     * brace form early. Camel ends a raw value at {@code }&} / {@code )&} or at the end of the URI,
     * so a value that could end both forms is rejected. Install and payload values alike are checked
     * with {@link UntrustedValues} (no placeholder, expression, Camel reference or raw value):
     * templates may place parameters inside expressions or endpoint URIs, which would then resolve
     * them in the worker that all tenants share.
     */
    static String raw(String key, String value) {
        UntrustedValues.check(key, value, "Kamelet parameters");
        if (!value.contains("}&") && !value.endsWith("}")) {
            return "RAW{" + value + "}";
        }
        if (!value.contains(")&") && !value.endsWith(")")) {
            return "RAW(" + value + ")";
        }
        throw new IllegalArgumentException("Kamelet parameter [" + key + "] cannot be passed verbatim.");
    }

    /** Parses the built URI the way Camel will and checks that exactly the intended values arrive. */
    private void verify(String uri, Map<String, String> expected) {
        int query = uri.indexOf('?');
        Map<String, Object> parsed;
        try {
            parsed = query < 0 ? Map.of() : URISupport.parseQuery(uri.substring(query + 1), true);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Kamelet parameters for [" + templateId + "].", e);
        }
        Map<String, Object> resolved = new LinkedHashMap<>(parsed);
        URISupport.resolveRawParameterValues(resolved);
        boolean same = resolved.size() == expected.size()
                && expected.entrySet().stream()
                        .allMatch(e -> Objects.equals(e.getValue(), String.valueOf(resolved.get(e.getKey()))));
        if (!same) {
            throw new IllegalArgumentException(
                    "Kamelet parameters for [" + templateId + "] cannot be passed verbatim.");
        }
    }

    private static boolean isReserved(String key) {
        return RESERVED_PARAMETERS.contains(key.toLowerCase(Locale.ROOT));
    }

    @Override
    protected void prepareExchange(Exchange exchange, SendRequest request) {
        Object body = request.data().get("body");
        if (body != null) {
            exchange.getMessage().setBody(body instanceof String ? body : Json.toString(body));
        }
    }

    /** Sends through the runtime's bounded Kamelet routes; all tenants share the kamelet component. */
    @Override
    protected Exchange send(String uri, Exchange exchange, ApplicationInstall install) {
        return runtime.sendKamelet(uri, exchange);
    }

    @Override
    protected Integer extractStatus(Exchange exchange) {
        // Kamelets are not HTTP-based in general: success unless an exception was thrown.
        return null;
    }

    @Override
    protected void mapResponse(Exchange exchange, SendRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("body", CamelSourcePoller.readBody(exchange));
        request.dto().setJsonData(out);
    }
}
