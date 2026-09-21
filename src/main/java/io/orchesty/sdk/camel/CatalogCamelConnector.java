package io.orchesty.sdk.camel;

import io.orchesty.sdk.process.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic one-shot Camel producer: URI from the Catalog via {@link org.apache.camel.spi.EndpointUriFactory},
 * credentials from the application install, body/headers from the payload.
 *
 * <p>Payload contract:
 * <ul>
 *   <li>{@code body} or {@code content} — exchange body (otherwise the whole payload);</li>
 *   <li>{@code fileName} — {@link Exchange#FILE_NAME};</li>
 *   <li>{@code headers} — extra message headers; {@code Camel*} headers only when the
 *       registration allows them ({@code payloadHeaders});</li>
 *   <li>{@code endpointOptions} — extra Camel endpoint options for this call; they never override
 *       the install, and only allowed ({@code payloadOptions}), declared, non-secret options are
 *       accepted.</li>
 * </ul>
 * HTTP calls belong on the SDK's {@code HttpSender}; a response status is still classified when a
 * component sets {@link Exchange#HTTP_RESPONSE_CODE}.
 */
public class CatalogCamelConnector extends ACamelConnector {

    public static final String PAYLOAD_ENDPOINT_OPTIONS = "endpointOptions";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogCamelConnector.class);

    /** Exchange property with the request body, to tell an echoed body from a response. */
    private static final String REQUEST_BODY = "OrchestyRequestBody";

    private static final Set<String> RESERVED =
            Set.of("body", "content", "fileName", "headers", PAYLOAD_ENDPOINT_OPTIONS);

    private final String scheme;
    private final String name;
    private final String applicationName;
    private final Map<String, Object> uriDefaults;

    public CatalogCamelConnector(CamelRuntime runtime, String scheme, String name) {
        this(runtime, scheme, name, null, Map.of());
    }

    public CatalogCamelConnector(CamelRuntime runtime, String scheme, String name, String applicationName) {
        this(runtime, scheme, name, applicationName, Map.of());
    }

    public CatalogCamelConnector(
            CamelRuntime runtime, String scheme, String name, String applicationName, Map<String, Object> uriDefaults) {
        super(runtime);
        this.scheme = scheme;
        this.name = name;
        this.applicationName = applicationName;
        this.uriDefaults = uriDefaults == null ? Map.of() : Map.copyOf(uriDefaults);
    }

    public String getScheme() {
        return scheme;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getApplicationName() {
        String fromApp = super.getApplicationName();
        if (fromApp != null && !fromApp.isEmpty()) {
            return fromApp;
        }
        return applicationName == null ? "" : applicationName;
    }

    /**
     * Builds the URI from the install's options, then the payload's {@code endpointOptions}, then
     * the registration defaults. The payload only adds options the install leaves unset, and only
     * ones the registration allows ({@link #setPayloadOptions}), the component declares and that
     * are not secret (see {@link EndpointUriBuilder#addPayloadOptions}). The registration defaults
     * are the only values not checked with {@link UntrustedValues}.
     */
    @Override
    protected String resolveEndpointUri(SendRequest request) {
        Map<String, Object> params = new LinkedHashMap<>(CamelCredentials.resolve(request.install()));
        EndpointUriBuilder.addPayloadOptions(
                runtime.context(),
                scheme,
                params,
                request.data().get(PAYLOAD_ENDPOINT_OPTIONS),
                PAYLOAD_ENDPOINT_OPTIONS,
                payloadOptions);
        return EndpointUriBuilder.connectorUri(runtime.context(), scheme, params, uriDefaults);
    }

    /**
     * Sets headers, file name and body from the payload. {@code Camel*} headers from the
     * {@code headers} map are dropped unless the registration allows them
     * ({@link #setPayloadHeaders}), because many components let them override endpoint settings.
     */
    @Override
    protected void prepareExchange(Exchange exchange, SendRequest request) {
        Map<String, Object> data = request.data();
        Object headers = data.get("headers");
        if (headers instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String header = String.valueOf(k);
                if (v == null) {
                    return;
                }
                if (header.regionMatches(true, 0, "Camel", 0, 5) && !payloadHeaders.contains(header)) {
                    LOG.debug("Dropping header [{}]: not allowed by payloadHeaders.", header);
                    return;
                }
                exchange.getMessage().setHeader(header, v);
            });
        }
        if (notBlank(data.get("fileName"))) {
            exchange.getMessage().setHeader(Exchange.FILE_NAME, data.get("fileName"));
        }
        Object body = data.get("body");
        if (body == null) {
            body = data.get("content");
        }
        if (body == null) {
            Map<String, Object> rest = new LinkedHashMap<>();
            data.forEach((k, v) -> {
                if (!RESERVED.contains(k)) {
                    rest.put(k, v);
                }
            });
            body = rest.isEmpty() ? "" : rest;
        }
        Object payload =
                body instanceof String || body instanceof byte[] ? body : (body == null ? "" : Json.toString(body));
        exchange.getMessage().setBody(payload);
        exchange.setProperty(REQUEST_BODY, payload);
    }

    /**
     * Maps the response: the status (if any), the body converted with Camel's type converters (JSON
     * parsed) and the file name. When the producer left the request body in place (for example
     * file and FTP uploads), the body is not echoed back.
     */
    @Override
    protected void mapResponse(Exchange exchange, SendRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        Integer status = extractStatus(exchange);
        if (status != null) {
            out.put("status", status);
        }
        if (exchange.getMessage().getBody() != exchange.getProperty(REQUEST_BODY)) {
            out.put("body", CamelSourcePoller.readBody(exchange));
        }
        Object fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME);
        if (fileName == null) {
            fileName = request.data().get("fileName");
        }
        if (fileName != null) {
            out.put("fileName", fileName);
        }
        if (status == null) {
            out.put("ok", true);
        }
        request.dto().setJsonData(out);
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }
}
