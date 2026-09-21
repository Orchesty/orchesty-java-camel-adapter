package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.ApplicationInstallRepository;
import io.orchesty.sdk.camel.ACamelPollingBatch;
import io.orchesty.sdk.node.IConnector;
import io.orchesty.sdk.node.NodeRegistry;
import java.util.Locale;

/**
 * Registers a Camel node into the worker's {@link NodeRegistry} using the descriptor's
 * {@code kind} to pick the Orchesty node type automatically:
 * <ul>
 *   <li>{@code kind: source}: a <b>batch</b> ({@link ACamelPollingBatch}); the poll drives
 *       a fan-out with the configured {@code output} shaping (item, page or chunk).</li>
 *   <li>{@code kind: connector} (default): a <b>connector</b> ({@link IConnector}); a
 *       producer or single-entity call, one message in, one out.</li>
 * </ul>
 * The retry policy and (optional) install repository from the descriptor are applied
 * before registration, so callers only construct the node and hand it here.
 */
public final class CamelNodeRegistrar {

    private CamelNodeRegistrar() {}

    /**
     * Registers a {@code kind: source} node as a batch, applying retry, output shaping
     * ({@code descriptor.output}) and the credential repository. Fails with an
     * {@link IllegalArgumentException} if the descriptor is not a source; use
     * {@link #registerConnector} for producers.
     *
     * @param <T> batch type
     * @param registry worker's node registry
     * @param batch batch node to register
     * @param descriptor its descriptor
     * @param repository install repository, or {@code null} to keep the batch's own
     * @return the registered batch
     */
    public static <T extends ACamelPollingBatch> T registerSource(
            NodeRegistry registry, T batch, CamelDescriptor descriptor, ApplicationInstallRepository repository) {
        if (!descriptor.isSource()) {
            throw new IllegalArgumentException("Descriptor for [" + descriptor.connector.name + "] is kind: "
                    + descriptor.kind + ", not source; use registerConnector().");
        }
        batch.setRetryPolicy(descriptor.toRetryPolicy());
        if (repository != null) {
            batch.setRepository(repository);
        }
        applyOutput(batch, descriptor.output);
        registry.registerBatch(batch);
        return batch;
    }

    /**
     * Registers a {@code kind: connector} node as a connector. Retry and repository wiring is
     * connector-specific (there is no shared configuration interface), so it stays with the
     * caller; this only checks the kind and registers the node.
     *
     * @param <T> connector type
     * @param registry worker's node registry
     * @param connector connector node to register
     * @param descriptor its descriptor
     * @return the registered connector
     */
    public static <T extends IConnector> T registerConnector(
            NodeRegistry registry, T connector, CamelDescriptor descriptor) {
        if (descriptor.isSource()) {
            throw new IllegalArgumentException(
                    "Descriptor for [" + descriptor.connector.name + "] is kind: source; use registerSource().");
        }
        registry.registerConnector(connector);
        return connector;
    }

    private static void applyOutput(ACamelPollingBatch batch, CamelDescriptor.OutputSpec output) {
        if (output == null) {
            return;
        }
        if (output.mode != null && !output.mode.isEmpty()) {
            batch.setEmitMode(
                    ACamelPollingBatch.EmitMode.valueOf(output.mode.trim().toUpperCase(Locale.ROOT)));
        }
        if (output.chunkSize != null) {
            batch.setChunkSize(output.chunkSize);
        }
        if (output.batchSize != null) {
            batch.setMaxBatchSize(output.batchSize);
        }
        if (output.pollTimeoutMs != null) {
            batch.setPollTimeoutMs(output.pollTimeoutMs);
        }
    }
}
