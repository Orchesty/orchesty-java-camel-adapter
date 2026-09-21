package io.orchesty.sdk.camel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.PollingConsumerPollStrategy;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.EndpointHelper;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ScheduledPollConsumer;
import org.apache.camel.support.service.ServiceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the worker's single long-lived {@link CamelContext} with a {@link ProducerTemplate}.
 * Camel is used as a library ({@code send} for sinks, a polling consumer or a one-shot poll for
 * sources), so Orchesty's Bridge stays the only orchestrator. The only routes are the ones
 * Kamelets materialize, see {@link #sendKamelet}.
 *
 * <p><b>Tenant isolation.</b> Some Camel components cache an authenticated client on the
 * component instance (for example the Google Sheets components). A component shared by all
 * tenants would hand one tenant's client and credentials to the next. Therefore:
 * <ul>
 *   <li>{@link #pollScheduledOnce} uses a fresh private component instance for every poll;</li>
 *   <li>{@link #send(String, Exchange, String)} uses one private component instance per tenant
 *       key (see {@link TenantKey}), cached in a bounded LRU and stopped on eviction once no
 *       send uses it any more.</li>
 * </ul>
 * Private instances are never registered in the context: endpoints are created directly on them
 * from the real URI, normalized as the context would ({@code sftp:host:22/dir} becomes
 * {@code sftp://host:22/dir}), so components that read the URI scheme (for example {@code smtp}
 * versus {@code smtps} in camel-mail) behave as usual. Endpoints sent with a {@code null} tenant key and
 * on-demand sources use the shared components.
 */
public class CamelRuntime implements AutoCloseable {

    /** Default number of per-tenant component instances kept by {@link #send}. */
    public static final int DEFAULT_MAX_TENANT_COMPONENTS = 100;

    /** Default number of Kamelet routes kept by {@link #sendKamelet}. */
    public static final int DEFAULT_MAX_KAMELET_ROUTES = 200;

    /** Endpoints (with their started producers) kept per tenant component. */
    static final int MAX_ENDPOINTS_PER_TENANT = 16;

    /** How long a timed-out scheduled poll may take to wind down after it is stopped. */
    static final long POLL_GRACE_MS = 2000L;

    private static final Logger LOG = LoggerFactory.getLogger(CamelRuntime.class);

    private static final String KAMELET_ROUTE_PREFIX = "orchesty-kamelet-";

    private final CamelContext context;
    private final ProducerTemplate producer;
    private final LeaseCache<String, TenantComponent> tenants =
            new LeaseCache<>(DEFAULT_MAX_TENANT_COMPONENTS, TenantComponent::close);
    private final LeaseCache<String, KameletRoute> kameletRoutes =
            new LeaseCache<>(DEFAULT_MAX_KAMELET_ROUTES, this::removeKameletRoute);
    private final AtomicLong kameletRouteSequence = new AtomicLong();

    public CamelRuntime() {
        this.context = new DefaultCamelContext();
        try {
            this.context.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start CamelContext", e);
        }
        this.producer = context.createProducerTemplate();
    }

    public CamelContext context() {
        return context;
    }

    public ProducerTemplate producer() {
        return producer;
    }

    /**
     * Sets how many per-tenant component instances {@link #send(String, Exchange, String)} keeps;
     * the least recently used one is stopped when the limit is exceeded.
     *
     * @param maxTenantComponents maximum number of cached tenant components, at least 1
     * @return this runtime
     */
    public CamelRuntime setMaxTenantComponents(int maxTenantComponents) {
        tenants.setMaxSize(maxTenantComponents);
        return this;
    }

    /**
     * Sets how many Kamelet routes {@link #sendKamelet} keeps; the least recently used one is
     * removed from the context when the limit is exceeded.
     *
     * @param maxKameletRoutes maximum number of Kamelet routes, at least 1
     * @return this runtime
     */
    public CamelRuntime setMaxKameletRoutes(int maxKameletRoutes) {
        kameletRoutes.setMaxSize(maxKameletRoutes);
        return this;
    }

    public Exchange newExchange() {
        return new DefaultExchange(context);
    }

    /**
     * Sends an exchange to an endpoint, isolated per tenant.
     * With a tenant key, the endpoint's component is a private instance owned by that tenant
     * (created on first use, then reused); without one, the shared component is used.
     * A failure is set on the returned exchange, as with {@link ProducerTemplate#send}.
     *
     * @param uri endpoint URI built for the real component scheme
     * @param exchange exchange to send
     * @param tenantKey tenant key from {@link TenantKey#of}, or {@code null} for no isolation
     * @return the exchange after processing
     */
    public Exchange send(String uri, Exchange exchange, String tenantKey) {
        if (tenantKey == null) {
            return producer.send(uri, exchange);
        }
        String scheme = scheme(uri);
        try (LeaseCache.Lease<TenantComponent> tenant =
                tenants.acquire(scheme + '|' + tenantKey, () -> new TenantComponent(newComponent(scheme)))) {
            tenant.resource().send(EndpointHelper.normalizeEndpointUri(uri), exchange);
        } catch (Exception e) {
            exchange.setException(e);
        }
        return exchange;
    }

    /**
     * Sends an exchange to a Kamelet. Every distinct Kamelet URI materializes one route from the
     * template; the runtime names it {@code orchesty-kamelet-<n>} and keeps at most
     * {@link #setMaxKameletRoutes} of them, removing the least recently used route (once no send
     * uses it any more), so per-message parameters cannot grow the context without bound.
     *
     * @param uri Kamelet URI ({@code kamelet:<templateId>?...}) without a {@code routeId}
     * @param exchange exchange to send
     * @return the exchange after processing
     */
    public Exchange sendKamelet(String uri, Exchange exchange) {
        try (LeaseCache.Lease<KameletRoute> route = kameletRoutes.acquire(uri, () -> createKameletRoute(uri))) {
            route.resource().endpoint.send(exchange);
        } catch (Exception e) {
            exchange.setException(e);
        }
        return exchange;
    }

    /**
     * Polls a <b>scheduled</b> source endpoint exactly once (for example
     * {@code google-sheets-stream} and most API components) and stops it again.
     *
     * <p>Every call uses a fresh private component instance and a fresh consumer, both stopped
     * afterwards, so no background scheduler and no cached client survive the call, and
     * concurrent polls of different tenants never share a component. Combine with
     * {@code initialDelay=0&repeatCount=1} on the endpoint so the scheduler fires once right away,
     * and limit the poll on the endpoint (for example {@code maxResults}).
     *
     * <p>Every polled exchange is handed to {@code handler} on the poll thread, while the
     * consumer's session is still open. Outside a route no unit of work completes the exchange, so
     * the handler must do it: completing runs the source's commit (for example marking mail as
     * seen or moving a file) or, for an exchange with an exception, its rollback.
     *
     * <p>A {@link ScheduledPollConsumer} normally logs and swallows poll failures, which would look
     * like an empty batch. The poll failure is captured instead and rethrown, so the caller can
     * classify it with {@link RetryPolicy}.
     *
     * <p>A poll that does not finish in time is stopped and given {@value #POLL_GRACE_MS} ms to
     * wind down; the handler may have received part of the poll by then.
     *
     * @param uri endpoint URI built for the real component scheme
     * @param timeoutMs how long to wait for the poll to finish
     * @param handler receives and completes every polled exchange
     * @return {@code true} if the poll finished, {@code false} if it did not finish in time
     * @throws IllegalArgumentException if the component has no scheduled poll consumer
     * @throws Exception the poll failure, if the poll failed
     */
    public boolean pollScheduledOnce(String uri, long timeoutMs, Processor handler) throws Exception {
        String scheme = scheme(uri);
        Component component = newComponent(scheme);
        AtomicReference<Exception> failure = new AtomicReference<>();
        boolean finished;
        try {
            Endpoint endpoint = component.createEndpoint(EndpointHelper.normalizeEndpointUri(uri));
            try {
                ServiceHelper.startService(endpoint);
                // The poll runs on the scheduler thread and hands each message to the handler
                // before the poll's commit or rollback.
                Consumer pollConsumer = endpoint.createConsumer(handler);
                if (!(pollConsumer instanceof ScheduledPollConsumer scheduled)) {
                    throw new IllegalArgumentException("Camel component [" + scheme
                            + "] has no scheduled poll consumer; register it as an on-demand source.");
                }
                CountDownLatch pollDone = new CountDownLatch(1);
                scheduled.setPollStrategy(new CapturingPollStrategy(pollDone, failure));

                ServiceHelper.startService(pollConsumer);
                try {
                    finished = pollDone.await(Math.max(timeoutMs, 0L), TimeUnit.MILLISECONDS);
                    if (!finished) {
                        // Stopping interrupts the poll; let it hand over what it is processing.
                        stopQuietly(pollConsumer);
                        pollDone.await(POLL_GRACE_MS, TimeUnit.MILLISECONDS);
                    }
                } finally {
                    stopQuietly(pollConsumer);
                }
            } finally {
                stopQuietly(endpoint);
            }
        } finally {
            stopQuietly(component);
        }

        if (!finished) {
            return false;
        }
        Exception cause = failure.get();
        if (cause != null) {
            throw cause;
        }
        return true;
    }

    private KameletRoute createKameletRoute(String uri) throws Exception {
        String routeId = KAMELET_ROUTE_PREFIX + kameletRouteSequence.incrementAndGet();
        String routeUri = uri + (uri.indexOf('?') < 0 ? '?' : '&') + "routeId=" + routeId;
        // Creating the endpoint materializes the route from the template.
        Endpoint endpoint = context.getEndpoint(routeUri);
        KameletRoute route = new KameletRoute(routeId, endpoint);
        try {
            route.endpoint = new EndpointProducer(endpoint, false);
        } catch (Exception e) {
            removeKameletRoute(route);
            throw e;
        }
        return route;
    }

    /** Stops and removes a Kamelet route, its producer and the endpoint that created it. */
    private void removeKameletRoute(KameletRoute route) {
        if (route.endpoint != null) {
            route.endpoint.close();
        }
        try {
            context.getRouteController().stopRoute(route.routeId);
            context.removeRoute(route.routeId);
        } catch (Exception e) {
            LOG.debug("Failed to remove Kamelet route [{}]: {}", route.routeId, e.getMessage());
        }
        try {
            context.removeEndpoint(route.routeEndpoint);
        } catch (Exception e) {
            LOG.debug("Failed to remove Kamelet endpoint of route [{}]: {}", route.routeId, e.getMessage());
        }
    }

    /** Creates and starts a new, unregistered instance of the scheme's component. */
    private Component newComponent(String scheme) {
        Component component;
        try {
            component = PluginHelper.getComponentResolver(context).resolveComponent(scheme, context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot create Camel component [" + scheme + "]: " + e.getMessage(), e);
        }
        if (component == null) {
            throw new IllegalArgumentException("Unknown Camel component [" + scheme + "].");
        }
        component.setCamelContext(context);
        ServiceHelper.startService(component);
        return component;
    }

    private static String scheme(String uri) {
        int colon = uri.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Camel endpoint URI without a scheme: [" + uri + "].");
        }
        return uri.substring(0, colon);
    }

    static void stopQuietly(Object service) {
        try {
            ServiceHelper.stopAndShutdownService(service);
        } catch (RuntimeException e) {
            LOG.debug("Failed to stop Camel service {}: {}", service, e.getMessage());
        }
    }

    /** Number of cached per-tenant components; for tests. */
    int tenantComponentCount() {
        return tenants.size();
    }

    /** Number of cached Kamelet routes; for tests. */
    int kameletRouteCount() {
        return kameletRoutes.size();
    }

    /** An endpoint with its started producer, or a fresh producer per send when not a singleton. */
    private static final class EndpointProducer {

        private final Endpoint endpoint;
        private final Producer producer;
        private final boolean ownsEndpoint;

        EndpointProducer(Endpoint endpoint, boolean ownsEndpoint) throws Exception {
            this.endpoint = endpoint;
            this.ownsEndpoint = ownsEndpoint;
            Producer created = null;
            try {
                if (ownsEndpoint) {
                    ServiceHelper.startService(endpoint);
                }
                if (endpoint.isSingletonProducer()) {
                    created = endpoint.createProducer();
                    ServiceHelper.startService(created);
                }
            } catch (Exception e) {
                stopQuietly(created);
                if (ownsEndpoint) {
                    stopQuietly(endpoint);
                }
                throw e;
            }
            this.producer = created;
        }

        void send(Exchange exchange) throws Exception {
            if (producer != null) {
                producer.process(exchange);
                return;
            }
            Producer single = endpoint.createProducer();
            try {
                ServiceHelper.startService(single);
                single.process(exchange);
            } finally {
                stopQuietly(single);
            }
        }

        void close() {
            stopQuietly(producer);
            if (ownsEndpoint) {
                stopQuietly(endpoint);
            }
        }
    }

    /** One tenant's private component with its endpoints and producers. */
    private static final class TenantComponent {

        private final Component component;
        private final LeaseCache<String, EndpointProducer> endpoints =
                new LeaseCache<>(MAX_ENDPOINTS_PER_TENANT, EndpointProducer::close);

        TenantComponent(Component component) {
            this.component = component;
        }

        void send(String uri, Exchange exchange) throws Exception {
            try (LeaseCache.Lease<EndpointProducer> endpoint =
                    endpoints.acquire(uri, () -> new EndpointProducer(component.createEndpoint(uri), true))) {
                endpoint.resource().send(exchange);
            }
        }

        /** Runs once no send uses the tenant any more, so its endpoints are idle too. */
        void close() {
            endpoints.clear();
            stopQuietly(component);
        }
    }

    /** A route materialized from a Kamelet template, with the producer that feeds it. */
    private static final class KameletRoute {

        private final String routeId;
        private final Endpoint routeEndpoint;
        private EndpointProducer endpoint;

        KameletRoute(String routeId, Endpoint routeEndpoint) {
            this.routeId = routeId;
            this.routeEndpoint = routeEndpoint;
        }
    }

    /**
     * Poll strategy that turns a scheduled consumer's swallowed poll error into a value the caller
     * can act on. It records the rollback cause and releases a latch on commit and on rollback, so
     * a one-shot poll can be awaited. Rollback returns {@code false} so Camel does not retry;
     * Orchesty owns retries.
     */
    private static final class CapturingPollStrategy implements PollingConsumerPollStrategy {

        private final CountDownLatch done;
        private final AtomicReference<Exception> failure;

        CapturingPollStrategy(CountDownLatch done, AtomicReference<Exception> failure) {
            this.done = done;
            this.failure = failure;
        }

        @Override
        public boolean begin(Consumer consumer, Endpoint endpoint) {
            return true;
        }

        @Override
        public void commit(Consumer consumer, Endpoint endpoint, int polledMessages) {
            done.countDown();
        }

        @Override
        public boolean rollback(Consumer consumer, Endpoint endpoint, int retryCounter, Exception cause) {
            failure.compareAndSet(null, cause);
            done.countDown();
            return false;
        }
    }

    @Override
    public void close() {
        tenants.clear();
        kameletRoutes.clear();
        stopQuietly(producer);
        stopQuietly(context);
    }
}
