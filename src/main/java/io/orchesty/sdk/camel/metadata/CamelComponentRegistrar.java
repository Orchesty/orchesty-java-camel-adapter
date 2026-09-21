package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.AApplication;
import io.orchesty.sdk.application.IApplication;
import io.orchesty.sdk.camel.ACamelPollingBatch;
import io.orchesty.sdk.camel.CamelRuntime;
import io.orchesty.sdk.camel.CatalogCamelConnector;
import io.orchesty.sdk.camel.CatalogCamelSourceBatch;
import io.orchesty.sdk.camel.EndpointUriBuilder;
import io.orchesty.sdk.camel.KameletConnector;
import io.orchesty.sdk.node.NodeRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.camel.spi.EndpointUriFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Code-first registration of a Camel component (or Kamelet) as an Orchesty application with a
 * connector or batch. The Camel Catalog supplies identity, form fields and URI syntax; the
 * caller only overrides genuine deviations via {@link ComponentOptions}.
 *
 * <pre>
 * registerComponent(registry, camel, "sftp");
 * registerComponent(registry, camel, "file", ComponentOptions.source().emit(ITEM).build());
 * </pre>
 */
public final class CamelComponentRegistrar {

    /**
     * Source components that keep all credentials in the endpoint URI and have a real on-demand
     * polling consumer, so they may poll on demand. ({@code sql} has neither: its consumer keeps
     * polling and marks rows consumed before Orchesty gets them.)
     */
    static final Set<String> ON_DEMAND_COMPONENTS = Set.of("file", "ftp", "ftps", "sftp");

    private static final Logger LOG = LoggerFactory.getLogger(CamelComponentRegistrar.class);

    private CamelComponentRegistrar() {}

    /**
     * Registers a Camel component with everything inferred from the Camel Catalog.
     *
     * @param registry worker's node registry
     * @param camel Camel runtime the node runs on
     * @param component component scheme, for example {@code sftp}
     * @return the registered application
     */
    public static AApplication registerComponent(NodeRegistry registry, CamelRuntime camel, String component) {
        return registerComponent(registry, camel, component, null);
    }

    /**
     * Registers a Camel component; the options override what the Camel Catalog would infer.
     *
     * @param registry worker's node registry
     * @param camel Camel runtime the node runs on
     * @param component component scheme, for example {@code sftp}
     * @param options overrides, or {@code null} for none
     * @return the registered application
     */
    public static AApplication registerComponent(
            NodeRegistry registry, CamelRuntime camel, String component, ComponentOptions options) {
        return register(registry, camel, descriptorFor(component, options), options);
    }

    /**
     * Registers an action or sink Kamelet from {@code kamelets/<name>.kamelet.yaml} on the
     * classpath.
     *
     * @param registry worker's node registry
     * @param camel Camel runtime the node runs on
     * @param kamelet Kamelet name
     * @return the registered application
     */
    public static AApplication registerKamelet(NodeRegistry registry, CamelRuntime camel, String kamelet) {
        return registerKamelet(registry, camel, kamelet, null);
    }

    /**
     * Registers an action or sink Kamelet; the options override what its definition would infer.
     *
     * @param registry worker's node registry
     * @param camel Camel runtime the node runs on
     * @param kamelet Kamelet name
     * @param options overrides, or {@code null} for none
     * @return the registered application
     */
    public static AApplication registerKamelet(
            NodeRegistry registry, CamelRuntime camel, String kamelet, ComponentOptions options) {
        CamelDescriptor descriptor = descriptorForKamelet(kamelet, options);
        return register(registry, camel, descriptor, options);
    }

    /**
     * Registers a component from a descriptor, for example one loaded from YAML. Blank fields are
     * filled from the Camel Catalog and the node type is chosen by {@code kind}.
     *
     * @param registry worker's node registry
     * @param camel Camel runtime the node runs on
     * @param descriptor component descriptor
     * @return the registered application
     */
    public static AApplication registerComponent(
            NodeRegistry registry, CamelRuntime camel, CamelDescriptor descriptor) {
        return register(registry, camel, descriptor.applyCatalogDefaults(), null);
    }

    public static CamelDescriptor descriptorFor(String component, ComponentOptions options) {
        CamelDescriptor descriptor = new CamelDescriptor();
        descriptor.runtime = "camel";
        descriptor.component = component;
        applyOptions(descriptor, options);
        descriptor.applyCatalogDefaults();
        applyExcludes(descriptor, options);
        return descriptor;
    }

    public static CamelDescriptor descriptorForKamelet(String kamelet, ComponentOptions options) {
        CamelDescriptor descriptor = new CamelDescriptor();
        descriptor.runtime = "camel";
        descriptor.kamelet = kamelet;
        applyOptions(descriptor, options);
        descriptor.applyCatalogDefaults();
        applyExcludes(descriptor, options);
        return descriptor;
    }

    private static AApplication register(
            NodeRegistry registry, CamelRuntime camel, CamelDescriptor descriptor, ComponentOptions options) {
        AApplication app = CamelApplicationFactory.create(descriptor);
        registry.registerApplication(app);

        if (descriptor.kamelet != null && !descriptor.kamelet.isBlank()) {
            KameletConnector connector =
                    new KameletConnector(camel, descriptor.resolveConnectorName(), descriptor.kamelet, app.getName());
            connector.setRetryPolicy(descriptor.toRetryPolicy());
            connector.setApplication(app);
            registry.registerConnector(connector);
            return app;
        }

        if (descriptor.isSource()) {
            ACamelPollingBatch batch = sourceNode(camel, descriptor, app, options);
            batch.setApplication(app);
            batch.setPayloadOptions(descriptor.payloadOptions);
            CamelNodeRegistrar.registerSource(registry, batch, descriptor, null);
            return app;
        }

        CatalogCamelConnector connector = new CatalogCamelConnector(
                camel, descriptor.component, descriptor.resolveConnectorName(), app.getName(), uriDefaults(options));
        connector.setRetryPolicy(descriptor.toRetryPolicy());
        connector.setApplication(app);
        connector.setPayloadOptions(descriptor.payloadOptions);
        connector.setPayloadHeaders(descriptor.payloadHeaders);
        registry.registerConnector(connector);
        return app;
    }

    private static ACamelPollingBatch sourceNode(
            CamelRuntime camel, CamelDescriptor descriptor, IApplication app, ComponentOptions options) {
        String scheme = descriptor.component;
        String nodeName = descriptor.resolveConnectorName();
        boolean scheduled = isScheduled(descriptor);
        Map<String, Object> defaults = uriDefaults(options);
        if (scheduled) {
            defaults.putIfAbsent("initialDelay", 0);
            defaults.putIfAbsent("repeatCount", 1);
            warnWithoutPollLimit(camel, scheme);
        }

        if (options != null && options.sourceFactory != null) {
            return options.sourceFactory.create(camel, nodeName, app.getName(), defaults);
        }
        return new CatalogCamelSourceBatch(camel, scheme, nodeName, app.getName(), defaults, scheduled);
    }

    /**
     * Sources poll on demand on the shared component only when they keep their credentials in
     * the endpoint URI; every other source gets one scheduled poll on a private component, so no
     * tenant's cached client reaches another tenant.
     */
    static boolean isScheduled(CamelDescriptor descriptor) {
        boolean safeOnDemand = ON_DEMAND_COMPONENTS.contains(descriptor.component);
        if (descriptor.scheduled == null) {
            return !safeOnDemand;
        }
        if (!descriptor.scheduled && !safeOnDemand) {
            LOG.warn(
                    "Camel source [{}] polls on demand on the shared component; it must not cache tenant"
                            + " credentials on the component.",
                    descriptor.component);
        }
        return descriptor.scheduled;
    }

    /**
     * Logs a warning when a scheduled source cannot be limited to the batch size: its poll then
     * emits everything it reads.
     *
     * @return {@code true} if the warning was logged
     */
    static boolean warnWithoutPollLimit(CamelRuntime camel, String scheme) {
        EndpointUriFactory factory = camel.context().getCamelContextExtension().getEndpointUriFactory(scheme);
        if (factory == null || factory.propertyNames() == null) {
            return false;
        }
        for (String option : EndpointUriBuilder.POLL_LIMIT_OPTIONS) {
            if (factory.propertyNames().contains(option)) {
                return false;
            }
        }
        LOG.warn(
                "Camel source [{}] has no maxMessagesPerPoll/maxResults option; batchSize cannot limit its poll.",
                scheme);
        return true;
    }

    private static Map<String, Object> uriDefaults(ComponentOptions options) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (options != null && options.uriDefaults != null) {
            defaults.putAll(options.uriDefaults);
        }
        return defaults;
    }

    private static void applyOptions(CamelDescriptor descriptor, ComponentOptions options) {
        if (options == null) {
            return;
        }
        if (options.kind != null) {
            descriptor.kind = options.kind;
        }
        if (options.name != null) {
            descriptor.application.name = options.name;
        }
        if (options.publicName != null) {
            descriptor.application.publicName = options.publicName;
        }
        if (options.description != null) {
            descriptor.application.description = options.description;
        }
        if (options.connectorName != null) {
            descriptor.connector.name = options.connectorName;
        }
        if (options.includeForm != null) {
            descriptor.application.authForm = new ArrayList<>(options.includeForm);
        }
        if (options.authorization != null) {
            descriptor.authorization = options.authorization;
        }
        if (options.retry != null) {
            descriptor.retry = options.retry;
        }
        if (options.output != null) {
            descriptor.output = options.output;
        }
        if (options.scheduled != null) {
            descriptor.scheduled = options.scheduled;
        }
        if (options.payloadOptions != null) {
            descriptor.payloadOptions = options.payloadOptions;
        }
        if (options.payloadHeaders != null) {
            descriptor.payloadHeaders = options.payloadHeaders;
        }
    }

    private static void applyExcludes(CamelDescriptor descriptor, ComponentOptions options) {
        if (options == null || options.excludeForm == null || descriptor.application.authForm == null) {
            return;
        }
        descriptor.application.authForm.removeAll(options.excludeForm);
    }
}
