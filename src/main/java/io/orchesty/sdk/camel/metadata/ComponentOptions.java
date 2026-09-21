package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.camel.ACamelPollingBatch;
import io.orchesty.sdk.camel.CamelRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Overrides for {@link CamelComponentRegistrar#registerComponent}. Everything here
 * is optional: name, title, form fields and kind are inferred from the Camel Catalog
 * when omitted. Use this only for genuine deviations (OAuth endpoints, emit mode,
 * a stable historical node name, a form subset).
 */
public final class ComponentOptions {

    final String name;
    final String publicName;
    final String description;
    final String connectorName;
    final String kind;
    final ACamelPollingBatch.EmitMode emitMode;
    final List<String> includeForm;
    final List<String> excludeForm;
    final CamelDescriptor.AuthorizationSpec authorization;
    final CamelDescriptor.RetrySpec retry;
    final CamelDescriptor.OutputSpec output;
    final Map<String, Object> uriDefaults;
    final Boolean scheduled;
    final SourceFactory sourceFactory;
    final List<String> payloadOptions;
    final List<String> payloadHeaders;

    /**
     * Builds a specialized polling batch. Use when the generic
     * {@link io.orchesty.sdk.camel.CatalogCamelSourceBatch} payload mapping is not enough
     * (e.g. Google Sheets rows).
     */
    @FunctionalInterface
    public interface SourceFactory {
        /**
         * Creates the batch.
         *
         * @param camel Camel runtime
         * @param nodeName batch name
         * @param applicationName application the batch belongs to
         * @param uriDefaults endpoint option defaults from {@link Builder#uriDefault}, plus
         *     {@code initialDelay=0}/{@code repeatCount=1} for scheduled sources
         * @return the batch
         */
        ACamelPollingBatch create(
                CamelRuntime camel, String nodeName, String applicationName, Map<String, Object> uriDefaults);
    }

    private ComponentOptions(Builder b) {
        this.name = b.name;
        this.publicName = b.publicName;
        this.description = b.description;
        this.connectorName = b.connectorName;
        this.kind = b.kind;
        this.emitMode = b.emitMode;
        this.includeForm = b.includeForm;
        this.excludeForm = b.excludeForm;
        this.authorization = b.authorization;
        this.retry = b.retry;
        this.output = b.output;
        this.uriDefaults = b.uriDefaults;
        this.scheduled = b.scheduled;
        this.sourceFactory = b.sourceFactory;
        this.payloadOptions = b.payloadOptions;
        this.payloadHeaders = b.payloadHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the options of a source (polling batch) registration.
     *
     * @return a builder preset to {@code kind: source}
     */
    public static Builder source() {
        return new Builder().kind("source");
    }

    public static final class Builder {
        private String name;
        private String publicName;
        private String description;
        private String connectorName;
        private String kind;
        private ACamelPollingBatch.EmitMode emitMode;
        private List<String> includeForm;
        private List<String> excludeForm;
        private CamelDescriptor.AuthorizationSpec authorization;
        private CamelDescriptor.RetrySpec retry;
        private CamelDescriptor.OutputSpec output;
        private Map<String, Object> uriDefaults;
        private Boolean scheduled;
        private SourceFactory sourceFactory;
        private List<String> payloadOptions;
        private List<String> payloadHeaders;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder publicName(String publicName) {
            this.publicName = publicName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder connectorName(String connectorName) {
            this.connectorName = connectorName;
            return this;
        }

        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public Builder emit(ACamelPollingBatch.EmitMode emitMode) {
            this.emitMode = emitMode;
            return this;
        }

        public Builder includeForm(String... keys) {
            this.includeForm = List.of(keys);
            return this;
        }

        public Builder includeForm(List<String> keys) {
            this.includeForm = keys == null ? null : List.copyOf(keys);
            return this;
        }

        public Builder excludeForm(String... keys) {
            this.excludeForm = List.of(keys);
            return this;
        }

        public Builder oauth2(String authUrl, String tokenUrl, List<String> scopes) {
            CamelDescriptor.AuthorizationSpec spec = new CamelDescriptor.AuthorizationSpec();
            spec.type = "oauth2";
            spec.authUrl = authUrl;
            spec.tokenUrl = tokenUrl;
            spec.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
            this.authorization = spec;
            return this;
        }

        public Builder allowManualTokens(boolean allow) {
            if (this.authorization == null) {
                this.authorization = new CamelDescriptor.AuthorizationSpec();
                this.authorization.type = "oauth2";
            }
            this.authorization.allowManualTokens = allow;
            return this;
        }

        public Builder retry(CamelDescriptor.RetrySpec retry) {
            this.retry = retry;
            return this;
        }

        public Builder uriDefault(String key, Object value) {
            if (this.uriDefaults == null) {
                this.uriDefaults = new LinkedHashMap<>();
            }
            this.uriDefaults.put(key, value);
            return this;
        }

        /**
         * Uses the scheduled-consumer poll path: one poll on a private component instance, then
         * stop. This is the default for every source except {@code file}, {@code ftp},
         * {@code ftps}, {@code sftp} and {@code sql}.
         *
         * @return this builder
         */
        public Builder scheduled() {
            this.scheduled = true;
            return this;
        }

        /**
         * Chooses the poll path explicitly; {@code false} is {@link #onDemand()}.
         *
         * @param scheduled {@code true} for the scheduled path, {@code false} for on-demand
         * @return this builder
         */
        public Builder scheduled(boolean scheduled) {
            this.scheduled = scheduled;
            return this;
        }

        /**
         * Uses the on-demand consumer path on the <b>shared</b> component. Only safe for
         * components that keep all credentials in the endpoint URI and cache no client on the
         * component; registration logs a warning for components outside the built-in list.
         *
         * @return this builder
         */
        public Builder onDemand() {
            this.scheduled = false;
            return this;
        }

        /**
         * Sets the endpoint options a message may add (never override) to the install's: in
         * {@code endpointOptions} for connectors; in {@code endpointOptions} or as top-level path
         * parameters for sources. Only options the component declares and that are not secret are
         * accepted. None by default.
         *
         * @param options option names, for example {@code "fileName"} or {@code "directoryName"}
         * @return this builder
         */
        public Builder payloadOptions(String... options) {
            this.payloadOptions = List.of(options);
            return this;
        }

        /**
         * Sets the {@code Camel*} headers a connector's message may set in {@code headers}; other
         * {@code Camel*} headers are dropped, because many components let them override endpoint
         * settings. None by default.
         *
         * @param headers header names, for example {@code "CamelFileName"}
         * @return this builder
         */
        public Builder payloadHeaders(String... headers) {
            this.payloadHeaders = List.of(headers);
            return this;
        }

        /**
         * Sets the maximum number of items one poll emits (the payload may ask for fewer).
         *
         * @param batchSize maximum items per poll
         * @return this builder
         */
        public Builder batchSize(int batchSize) {
            output().batchSize = batchSize;
            return this;
        }

        /**
         * Sets how long one poll may take, in milliseconds (the payload may ask for less).
         *
         * @param pollTimeoutMs poll timeout
         * @return this builder
         */
        public Builder pollTimeoutMs(long pollTimeoutMs) {
            output().pollTimeoutMs = pollTimeoutMs;
            return this;
        }

        /**
         * Sets the number of items per message in {@code CHUNK} emit mode.
         *
         * @param chunkSize items per chunk
         * @return this builder
         */
        public Builder chunkSize(int chunkSize) {
            output().chunkSize = chunkSize;
            return this;
        }

        private CamelDescriptor.OutputSpec output() {
            if (output == null) {
                output = new CamelDescriptor.OutputSpec();
            }
            return output;
        }

        /**
         * Uses a specialized polling batch instead of {@code CatalogCamelSourceBatch}.
         *
         * @param sourceFactory creates the batch
         * @return this builder
         */
        public Builder sourceFactory(SourceFactory sourceFactory) {
            this.sourceFactory = sourceFactory;
            return this;
        }

        public ComponentOptions build() {
            if (emitMode != null) {
                output().mode = emitMode.name().toLowerCase(Locale.ROOT);
            }
            return new ComponentOptions(this);
        }
    }
}
