package io.orchesty.sdk.camel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic polling source: URI from the Catalog via {@link org.apache.camel.spi.EndpointUriFactory},
 * credentials from the application install, extra options from the payload.
 *
 * <p>Reserved payload keys ({@code batchSize}, {@code emitMode}, …) stay on the
 * batch contract. The {@code endpointOptions} map and top-level path parameters (for example
 * {@code directoryName}) add Camel options the install leaves unset, but only the ones the
 * registration allows ({@code payloadOptions}); secret options always come from the install.
 * Components that need more (a cursor that overrides the install, custom item mapping) get a
 * specialized subclass through {@code ComponentOptions.sourceFactory}.
 *
 * <p>The {@code file} component reads the worker's own file system, so register it only on
 * workers that serve a single tenant. Set {@code moveFailed} on on-demand file sources: a file
 * that keeps failing is otherwise picked first on every poll and blocks the directory.
 */
public class CatalogCamelSourceBatch extends ACamelPollingBatch {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogCamelSourceBatch.class);

    public static final String PAYLOAD_ENDPOINT_OPTIONS = "endpointOptions";

    private static final Set<String> RESERVED = Set.of(
            PAYLOAD_BATCH_SIZE,
            PAYLOAD_POLL_TIMEOUT_MS,
            PAYLOAD_EMIT_MODE,
            PAYLOAD_CHUNK_SIZE,
            PAYLOAD_ENDPOINT_OPTIONS);

    private final String scheme;
    private final String name;
    private final String applicationName;
    private final Map<String, Object> uriDefaults;
    private final boolean scheduled;
    private final Set<String> pathOptions;

    public CatalogCamelSourceBatch(CamelRuntime runtime, String scheme, String name, String applicationName) {
        this(runtime, scheme, name, applicationName, Map.of(), false);
    }

    public CatalogCamelSourceBatch(
            CamelRuntime runtime,
            String scheme,
            String name,
            String applicationName,
            Map<String, Object> uriDefaults,
            boolean scheduled) {
        super(runtime);
        this.scheme = scheme;
        this.name = name;
        this.applicationName = applicationName;
        this.uriDefaults = uriDefaults == null ? Map.of() : Map.copyOf(uriDefaults);
        this.scheduled = scheduled;
        this.pathOptions = EndpointUriBuilder.pathOptions(scheme);
        if (scheduled) {
            setPollTimeoutMs(15_000L);
        }
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

    @Override
    protected boolean scheduledSource() {
        return scheduled;
    }

    /**
     * Builds the URI from the install's options, then the payload, then the registration defaults.
     * The payload only adds options the install leaves unset. {@code endpointOptions} is checked strictly
     * (see {@link EndpointUriBuilder#addPayloadOptions}). A top-level key is used only when it is a
     * path parameter of the component that the registration allows ({@link #setPayloadOptions});
     * other top-level keys are ordinary message data and are ignored. The registration defaults
     * are the only values not checked with {@link UntrustedValues}.
     */
    @Override
    protected String resolveEndpointUri(PollRequest request) {
        Map<String, Object> params = new LinkedHashMap<>(CamelCredentials.resolve(request.install()));
        Map<String, Object> data = request.data();
        data.forEach((k, v) -> {
            if (RESERVED.contains(k) || v == null || String.valueOf(v).isEmpty()) {
                return;
            }
            if (pathOptions.contains(k) && payloadOptions.contains(k)) {
                EndpointUriBuilder.addPayloadOption(runtime.context(), scheme, params, k, v, k, payloadOptions);
            } else {
                LOG.debug("Ignoring payload key [{}]: not an allowed path parameter of [{}].", k, scheme);
            }
        });
        EndpointUriBuilder.addPayloadOptions(
                runtime.context(),
                scheme,
                params,
                data.get(PAYLOAD_ENDPOINT_OPTIONS),
                PAYLOAD_ENDPOINT_OPTIONS,
                payloadOptions);
        return EndpointUriBuilder.sourceUri(
                runtime.context(),
                scheme,
                params,
                uriDefaults,
                scheduled ? Integer.valueOf(request.batchSize()) : null);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Object mapExchange(Exchange exchange) {
        Map<String, Object> item = (Map<String, Object>) super.mapExchange(exchange);
        Object fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME);
        if (fileName != null) {
            item.put("fileName", fileName);
        }
        return item;
    }
}
