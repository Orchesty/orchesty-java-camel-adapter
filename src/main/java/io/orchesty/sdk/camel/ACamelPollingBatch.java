package io.orchesty.sdk.camel;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.ApplicationInstallRepository;
import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.node.ABatch;
import io.orchesty.sdk.process.BatchProcessDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;

/**
 * Base for <b>batch</b> nodes that read from a Camel <b>source</b> component using a
 * polling consumer — pulled on demand by Orchesty, never a topology trigger. Because
 * it is a batch (not a connector), each Orchesty invocation performs one bounded poll
 * and emits the drained items as <b>multiple</b> messages that the Bridge fans out to
 * the follower(s).
 *
 * <h2>Output modes</h2>
 * How the polled items become downstream messages is configurable ({@link EmitMode},
 * overridable per invocation via the payload):
 * <ul>
 *   <li>{@link EmitMode#ITEM} (default) — one message per item (true fan-out, e.g. one
 *       spreadsheet row / one file per message);</li>
 *   <li>{@link EmitMode#PAGE} — a single message carrying the whole page
 *       {@code { "items": [...], "count": N }};</li>
 *   <li>{@link EmitMode#CHUNK} — one message per fixed-size slice of items
 *       (arbitrary-sized arrays, see {@link #setChunkSize(int)}).</li>
 * </ul>
 * An empty poll emits no messages and is a normal <b>success</b> ("nothing to read now").
 *
 * <h2>Cursor and paging</h2>
 * Override {@link #nextCursor(PollRequest, List)} to return a non-null cursor and the
 * batch will ask the Bridge to re-invoke it for the next page (see
 * {@link BatchProcessDto#setBatchCursor(String, boolean)}). Default: no cursor (single poll).
 *
 * <p>Errors are classified by the Orchesty-owned {@link RetryPolicy} via
 * {@link CamelErrorMapper}; Camel never retries on its own.
 */
public abstract class ACamelPollingBatch extends ABatch {

    /** Payload key that lets a message override the batch size for this poll. */
    public static final String PAYLOAD_BATCH_SIZE = "batchSize";
    /** Payload key that lets a message shorten the poll timeout (ms); the configured one is the ceiling. */
    public static final String PAYLOAD_POLL_TIMEOUT_MS = "pollTimeoutMs";
    /** Payload key that lets a message override the emit mode ({@code item|page|chunk}). */
    public static final String PAYLOAD_EMIT_MODE = "emitMode";
    /** Payload key that lets a message override the chunk size (for {@link EmitMode#CHUNK}). */
    public static final String PAYLOAD_CHUNK_SIZE = "chunkSize";

    /** How drained items are turned into downstream messages. */
    public enum EmitMode {
        /** One message per item (true fan-out). */
        ITEM,
        /** A single message with the whole page {@code { items, count }}. */
        PAGE,
        /** One message per fixed-size slice of items. */
        CHUNK
    }

    protected final CamelRuntime runtime;
    protected RetryPolicy retryPolicy =
            RetryPolicy.builder().repeatOnException(true).build();
    protected ApplicationInstallRepository repository;
    protected Set<String> payloadOptions = Set.of();

    private int maxBatchSize = 100;
    private long pollTimeoutMs = 1000L;
    private EmitMode emitMode = EmitMode.ITEM;
    private int chunkSize = 10;

    protected ACamelPollingBatch(CamelRuntime runtime) {
        this.runtime = runtime;
    }

    public ACamelPollingBatch setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    public ACamelPollingBatch setRepository(ApplicationInstallRepository repository) {
        this.repository = repository;
        return this;
    }

    /**
     * Sets the endpoint options a message may add, at the top level (path parameters only) or in
     * {@code endpointOptions}; none by default. See {@link EndpointUriBuilder#addPayloadOptions}.
     *
     * @param options option names
     * @return this batch
     */
    public ACamelPollingBatch setPayloadOptions(Collection<String> options) {
        this.payloadOptions = options == null ? Set.of() : Set.copyOf(options);
        return this;
    }

    public ACamelPollingBatch setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        return this;
    }

    public ACamelPollingBatch setPollTimeoutMs(long pollTimeoutMs) {
        this.pollTimeoutMs = pollTimeoutMs;
        return this;
    }

    public ACamelPollingBatch setEmitMode(EmitMode emitMode) {
        this.emitMode = emitMode;
        return this;
    }

    public ACamelPollingBatch setChunkSize(int chunkSize) {
        if (chunkSize > 0) {
            this.chunkSize = chunkSize;
        }
        return this;
    }

    /**
     * What the endpoint URI of one poll is built from.
     *
     * @param dto incoming message
     * @param data its payload, parsed once
     * @param install application install with the tenant's settings, or {@code null}
     * @param batchSize effective batch size of this poll; scheduled sources limit the poll to it
     *     on the endpoint (for example {@code maxResults}), see {@link EndpointUriBuilder#build(
     *     org.apache.camel.CamelContext, String, Map, Integer)}
     */
    public record PollRequest(
            BatchProcessDto dto, Map<String, Object> data, ApplicationInstall install, int batchSize) {}

    /**
     * Builds the Camel source endpoint URI to poll, for the component's real scheme.
     *
     * @param request the message, its payload, install and batch size
     * @return the endpoint URI
     * @throws Exception if the URI cannot be built; an {@link IllegalArgumentException} stops the
     *     message instead of retrying it
     */
    protected abstract String resolveEndpointUri(PollRequest request) throws Exception;

    /**
     * Tells whether the source is a <b>scheduled</b> consumer (for example
     * {@code google-sheets-stream} and most API components) rather than an on-demand polling
     * consumer (for example {@code file} or {@code sql}). Scheduled sources are drained by
     * {@link CamelRuntime#pollScheduledOnce}: one poll with a fresh private component and
     * consumer, so no background scheduler or cached client survives the call. On-demand sources
     * run on the <b>shared</b> component, so return {@code true} for every component that is not
     * guaranteed to keep all credentials in the endpoint URI.
     *
     * @return {@code true} for a scheduled source
     */
    protected boolean scheduledSource() {
        return false;
    }

    /**
     * Maps one consumed exchange into an item; by default {@code { "body": <parsed> }}, with
     * JSON strings parsed. Override to expose more, for example the file name or headers.
     *
     * @param exchange consumed exchange
     * @return the item
     */
    protected Object mapExchange(Exchange exchange) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("body", CamelSourcePoller.readBody(exchange));
        return item;
    }

    /**
     * Returns the cursor of the next page, or {@code null} for a single poll. A cursor asks the
     * Bridge to invoke this node again with it, while the current messages are fanned out.
     *
     * @param request the current poll: message, payload, install and batch size
     * @param items items of the current poll
     * @return the next cursor, or {@code null}
     */
    protected String nextCursor(PollRequest request, List<Object> items) {
        return null;
    }

    /**
     * Polls once and emits the items. Invalid input (an {@link IllegalArgumentException}, for
     * example a rejected option or a missing install) stops the message, other errors are
     * classified by the {@link RetryPolicy}. A repeat escapes as {@link OnRepeatException}, so the
     * worker applies the node's repeater.
     */
    @Override
    public BatchProcessDto process(BatchProcessDto dto) throws Exception {
        Map<String, Object> data = dto.getJsonData();
        int batchSize = resolveBatchSize(data);
        long timeout = resolvePollTimeout(data);

        List<Object> items;
        String cursor;
        try {
            PollRequest request = new PollRequest(dto, data, loadInstall(dto), batchSize);
            String uri = resolveEndpointUri(request);
            items = scheduledSource()
                    ? CamelSourcePoller.pollScheduled(runtime, uri, timeout, this::mapExchange)
                    : CamelSourcePoller.pollOnDemand(runtime, uri, timeout, batchSize, this::mapExchange);
            cursor = nextCursor(request, items);
        } catch (OnRepeatException e) {
            throw e;
        } catch (Exception e) {
            CamelErrorMapper.classifyException(dto, e, retryPolicy);
            return dto;
        }

        emit(dto, data, items);

        if (cursor != null && !cursor.isEmpty()) {
            dto.setBatchCursor(cursor, false);
        } else {
            dto.setSuccessProcess(getName() + " emitted " + dto.getMessages().size() + " message(s) from "
                    + items.size() + " item(s)");
        }
        return dto;
    }

    private void emit(BatchProcessDto dto, Map<String, Object> data, List<Object> items) {
        switch (resolveEmitMode(data)) {
            case PAGE -> {
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("items", items);
                page.put("count", items.size());
                dto.addItem(page);
            }
            case CHUNK -> {
                int size = resolveChunkSize(data);
                for (int i = 0; i < items.size(); i += size) {
                    dto.addItem(new ArrayList<>(items.subList(i, Math.min(i + size, items.size()))));
                }
            }
            default -> items.forEach(dto::addItem);
        }
    }

    private EmitMode resolveEmitMode(Map<String, Object> data) {
        Object v = data.get(PAYLOAD_EMIT_MODE);
        if (v != null && !String.valueOf(v).isEmpty()) {
            try {
                return EmitMode.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to configured default
            }
        }
        return emitMode;
    }

    private int resolveChunkSize(Map<String, Object> data) {
        Object v = data.get(PAYLOAD_CHUNK_SIZE);
        if (v instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return chunkSize;
    }

    private int resolveBatchSize(Map<String, Object> data) {
        Object v = data.get(PAYLOAD_BATCH_SIZE);
        if (v instanceof Number n) {
            int requested = n.intValue();
            if (requested > 0) {
                return Math.min(requested, maxBatchSize);
            }
        }
        return maxBatchSize;
    }

    /** The payload may shorten the poll timeout; the configured one is the ceiling. */
    private long resolvePollTimeout(Map<String, Object> data) {
        Object v = data.get(PAYLOAD_POLL_TIMEOUT_MS);
        if (v instanceof Number n && n.longValue() >= 0) {
            return Math.min(n.longValue(), pollTimeoutMs);
        }
        return pollTimeoutMs;
    }

    /**
     * Loads the application install of the message's user and SDK, if this batch uses an
     * application. Uses the {@link #setRepository repository} set explicitly, otherwise the one
     * from the {@link io.orchesty.sdk.storage.DatabaseClient} injected by {@code WorkerServer}.
     *
     * @param dto incoming message
     * @return the install, or {@code null} when the batch has no application
     * @throws IllegalArgumentException when the batch has an application but the message has no
     *     {@code user}/{@code sdk} header or the user has no enabled install
     */
    protected ApplicationInstall loadInstall(BatchProcessDto dto) {
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
