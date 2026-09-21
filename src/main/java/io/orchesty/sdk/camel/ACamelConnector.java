package io.orchesty.sdk.camel;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.ApplicationInstallRepository;
import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.node.AConnector;
import io.orchesty.sdk.process.ProcessDto;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;

/**
 * Base for connectors that execute a single Apache Camel producer call per message.
 *
 * <p>Each invocation resolves the endpoint URI, builds the exchange, sends it through
 * {@link CamelRuntime#send(String, Exchange, String)} (with a private component per tenant),
 * classifies the outcome with {@link RetryPolicy} and maps the response back into the
 * {@link ProcessDto}. Orchestration, retries and observability stay with Orchesty.
 */
public abstract class ACamelConnector extends AConnector {

    protected final CamelRuntime runtime;
    protected RetryPolicy retryPolicy = RetryPolicy.httpDefault();
    protected ApplicationInstallRepository repository;
    protected Set<String> payloadOptions = Set.of();
    protected Set<String> payloadHeaders = Set.of();

    protected ACamelConnector(CamelRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * One message being sent.
     *
     * @param dto incoming message; the response is written to it
     * @param data its payload, parsed once
     * @param install application install with the tenant's settings, or {@code null}
     */
    public record SendRequest(ProcessDto dto, Map<String, Object> data, ApplicationInstall install) {}

    public ACamelConnector setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    public ACamelConnector setRepository(ApplicationInstallRepository repository) {
        this.repository = repository;
        return this;
    }

    /**
     * Sets the endpoint options a message may add (see
     * {@link EndpointUriBuilder#addPayloadOptions}); none by default.
     *
     * @param options option names
     * @return this connector
     */
    public ACamelConnector setPayloadOptions(Collection<String> options) {
        this.payloadOptions = options == null ? Set.of() : Set.copyOf(options);
        return this;
    }

    /**
     * Sets the {@code Camel*} headers a message may set; other {@code Camel*} headers are dropped,
     * because many components let them override endpoint settings. None by default.
     *
     * @param headers header names
     * @return this connector
     */
    public ACamelConnector setPayloadHeaders(Collection<String> headers) {
        this.payloadHeaders = headers == null ? Set.of() : Set.copyOf(headers);
        return this;
    }

    /**
     * Builds the Camel endpoint URI to send to, for the component's real scheme.
     *
     * @param request the message, its payload and install
     * @return the endpoint URI
     * @throws Exception if the URI cannot be built; an {@link IllegalArgumentException} stops the
     *     message instead of retrying it
     */
    protected abstract String resolveEndpointUri(SendRequest request) throws Exception;

    /**
     * Populates the outgoing exchange (body, headers, method).
     *
     * @param exchange exchange to send
     * @param request the message, its payload and install
     * @throws Exception if the exchange cannot be prepared
     */
    protected abstract void prepareExchange(Exchange exchange, SendRequest request) throws Exception;

    /**
     * Maps a successful Camel response back into the message ({@code request.dto()}).
     *
     * @param exchange exchange after processing
     * @param request the message, its payload and install
     * @throws Exception if the response cannot be mapped
     */
    protected abstract void mapResponse(Exchange exchange, SendRequest request) throws Exception;

    /**
     * Tells whether each tenant gets a private Camel component instance (see
     * {@link CamelRuntime#send(String, Exchange, String)}). Enabled by default; override with
     * {@code false} only for components that cannot run as several instances.
     *
     * @return {@code true} to isolate tenants
     */
    protected boolean tenantIsolation() {
        return true;
    }

    /**
     * Returns the status code used to classify the outcome; by default the HTTP response code
     * header. Return {@code null} when the component has no status codes.
     *
     * @param exchange exchange after processing
     * @return the status code, or {@code null}
     */
    protected Integer extractStatus(Exchange exchange) {
        return exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
    }

    /**
     * Sends the message through Camel. Invalid input (an {@link IllegalArgumentException}, for
     * example a rejected option or a missing install) and unresolvable endpoints stop the message,
     * other errors are classified by the {@link RetryPolicy} (see {@link CamelErrorMapper}). Only a
     * repeat escapes, as {@link OnRepeatException}, so the worker applies the node's repeater.
     */
    @Override
    public ProcessDto process(ProcessDto dto) throws Exception {
        try {
            SendRequest request = new SendRequest(dto, dto.getJsonData(), loadInstall(dto));
            String uri = resolveEndpointUri(request);
            Exchange exchange = runtime.newExchange();
            prepareExchange(exchange, request);
            Exchange out = send(uri, exchange, request.install());

            Throwable failure = out.getException();
            if (failure != null) {
                CamelErrorMapper.classifyException(dto, failure, retryPolicy);
                return dto;
            }
            Integer status = extractStatus(out);
            if (status != null && CamelErrorMapper.classifyStatus(dto, status, retryPolicy)) {
                return dto;
            }
            mapResponse(out, request);
        } catch (OnRepeatException e) {
            throw e;
        } catch (Exception e) {
            CamelErrorMapper.classifyException(dto, e, retryPolicy);
            return dto;
        }
        dto.setSuccessProcess(getName() + " processed");
        return dto;
    }

    /**
     * Sends the prepared exchange; by default through {@link CamelRuntime#send(String, Exchange,
     * String)} with a private component per tenant when {@link #tenantIsolation()} is on.
     *
     * @param uri endpoint URI from {@link #resolveEndpointUri}
     * @param exchange prepared exchange
     * @param install application install, or {@code null}
     * @return the exchange after processing, with any failure set on it
     */
    protected Exchange send(String uri, Exchange exchange, ApplicationInstall install) {
        String tenantKey = tenantIsolation() ? TenantKey.of(getApplicationName(), install) : null;
        return runtime.send(uri, exchange, tenantKey);
    }

    /**
     * Loads the application install of the message's user and SDK, if this connector uses an
     * application. Uses the {@link #setRepository repository} set explicitly, otherwise the one
     * from the {@link io.orchesty.sdk.storage.DatabaseClient} injected by {@code WorkerServer}.
     *
     * @param dto incoming message
     * @return the install, or {@code null} when the connector has no application
     * @throws IllegalArgumentException when the connector has an application but no repository is
     *     available, the message has no {@code user}/{@code sdk} header or the user has no enabled
     *     install
     */
    protected ApplicationInstall loadInstall(ProcessDto dto) {
        return InstallLoader.load(getApplicationName(), resolveRepository(), dto.getUser(), dto.getSdk());
    }

    private ApplicationInstallRepository resolveRepository() {
        if (repository != null) {
            return repository;
        }
        try {
            return getDb().getApplicationRepository();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
