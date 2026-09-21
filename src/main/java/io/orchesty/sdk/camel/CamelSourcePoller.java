package io.orchesty.sdk.camel;

import io.orchesty.sdk.process.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.PollingConsumer;
import org.apache.camel.support.UnitOfWorkHelper;
import org.apache.camel.support.service.ServiceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared, node-agnostic polling of a Camel <b>source</b> endpoint — pulled on demand
 * by Orchesty, never a topology trigger. One bounded poll maps each exchange to an item and
 * returns them. Used by batch source nodes (and any other source flavor) so the poll/commit
 * semantics live in one place.
 *
 * <ul>
 *   <li><b>On-demand</b> ({@code file}, {@code ftp}, {@code ftps}, {@code sftp}): one polling
 *       consumer per batch, so a remote source opens one session per batch and closes it when
 *       the batch ends. Each item is committed on its own (for example, the file is moved or
 *       deleted), up to {@code batchSize} items. A failing item is rolled back (for example,
 *       the file stays); the items committed before it are still returned. These components
 *       keep credentials in the endpoint URI, so each tenant gets its own endpoint.</li>
 *   <li><b>Scheduled</b> ({@code google-sheets-stream}, {@code sql} and most API components): a
 *       single poll via {@link CamelRuntime#pollScheduledOnce}, with a fresh private component
 *       and consumer that are stopped afterwards, so no background scheduler or cached client
 *       survives the call. Each polled exchange is mapped and committed during the poll (the
 *       endpoint limits the poll, see {@link EndpointUriBuilder}); a failing one is rolled back
 *       and skipped. Poll failures are rethrown instead of looking like an empty batch.</li>
 * </ul>
 */
public final class CamelSourcePoller {

    private static final Logger LOG = LoggerFactory.getLogger(CamelSourcePoller.class);

    private CamelSourcePoller() {}

    /**
     * Polls an on-demand source with a polling consumer created for this batch, committing each
     * item on its own. When an item fails to arrive or to map, it is rolled back and the poll
     * ends; the items committed before it are returned, and the failure is thrown only when it
     * was the first item. A file that comes back within the batch (its commit neither moved nor
     * deleted it) ends the batch instead of being emitted again.
     *
     * @param runtime Camel runtime
     * @param uri endpoint URI
     * @param timeout how long to wait for the first item, in milliseconds
     * @param batchSize maximum number of items
     * @param mapper maps a consumed exchange to an item
     * @return the items, possibly empty
     * @throws Exception the consumer or mapping failure of the first item, after rolling it back
     */
    public static List<Object> pollOnDemand(
            CamelRuntime runtime, String uri, long timeout, int batchSize, Function<Exchange, Object> mapper)
            throws Exception {
        Endpoint endpoint = runtime.context().getEndpoint(uri);
        if (endpoint == null) {
            throw new IllegalArgumentException("No Camel component for ["
                    + uri.substring(0, Math.max(uri.indexOf(':'), 0)) + "]; add the component to the worker.");
        }
        PollingConsumer consumer = endpoint.createPollingConsumer();
        List<Object> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            ServiceHelper.startService(consumer);
            for (int i = 0; i < batchSize; i++) {
                Exchange exchange = null;
                Object item;
                try {
                    exchange = (i == 0) ? consumer.receive(timeout) : consumer.receiveNoWait();
                    if (exchange == null) {
                        break; // nothing (more) available
                    }
                    String file = fileId(exchange);
                    if (file != null && !seen.add(file)) {
                        // Each receive polls again; a file that comes back was not moved or deleted by
                        // the commit (for example FTP/SFTP, which leave files in place by default).
                        done(exchange);
                        LOG.warn(
                                "File [{}] was polled twice in one batch; the source neither moves nor deletes"
                                        + " what it reads (set move, delete or noop).",
                                file);
                        break;
                    }
                    Exception f = exchange.getException();
                    if (f != null) {
                        throw f;
                    }
                    item = mapper.apply(exchange);
                } catch (Exception t) {
                    if (exchange != null) {
                        // Roll back this exchange (e.g. keep the file) and let Orchesty decide on retry.
                        exchange.setException(t);
                        done(exchange);
                    }
                    if (items.isEmpty()) {
                        throw t;
                    }
                    LOG.warn(
                            "Item {} of the poll failed; emitting the {} item(s) before it: {}",
                            i + 1,
                            items.size(),
                            CamelErrorMapper.describe(t));
                    break;
                }
                items.add(item);
                done(exchange); // commit (e.g. move/delete the file)
            }
        } finally {
            CamelRuntime.stopQuietly(consumer);
        }
        return items;
    }

    /** The file an exchange of a file-family consumer carries, or {@code null} for other components. */
    private static String fileId(Exchange exchange) {
        Object path = exchange.getMessage().getHeader(Exchange.FILE_PATH);
        if (path == null) {
            path = exchange.getMessage().getHeader(Exchange.FILE_NAME);
        }
        return path == null ? null : String.valueOf(path);
    }

    /**
     * Completes a consumed exchange the way {@code ConsumerTemplate.doneUoW} does: the unit of work
     * (or the handed-over completions) commits it, or rolls it back when it has an exception.
     */
    private static void done(Exchange exchange) {
        try {
            if (exchange.getUnitOfWork() == null) {
                UnitOfWorkHelper.doneSynchronizations(
                        exchange, exchange.getExchangeExtension().handoverCompletions());
            } else {
                exchange.getUnitOfWork().done(exchange);
            }
        } catch (Exception e) {
            LOG.warn("Failed to complete a polled exchange: {}", CamelErrorMapper.describe(e));
        }
    }

    /**
     * Polls a scheduled source once with a fresh private component and consumer (see
     * {@link CamelRuntime#pollScheduledOnce}). Each polled exchange is mapped and then committed on
     * the poll thread, as on demand; an exchange that failed or cannot be mapped is rolled back
     * (for example, the mail stays unseen) and skipped. The first failure is thrown only when no
     * exchange could be mapped.
     *
     * <p>A poll that does not finish in time returns the items mapped so far, with a warning; when
     * there are none, a {@link TimeoutException} is thrown.
     *
     * @param runtime Camel runtime
     * @param uri endpoint URI, limited to the batch size where the component allows it
     * @param timeout how long to wait for the poll, in milliseconds
     * @param mapper maps a consumed exchange to an item
     * @return the items, possibly empty
     * @throws TimeoutException if the poll did not finish in time and mapped nothing
     * @throws Exception the poll failure, or the first exchange failure when nothing could be mapped
     */
    public static List<Object> pollScheduled(
            CamelRuntime runtime, String uri, long timeout, Function<Exchange, Object> mapper) throws Exception {
        List<Object> items = new CopyOnWriteArrayList<>();
        AtomicReference<Exception> firstFailure = new AtomicReference<>();
        AtomicInteger failed = new AtomicInteger();
        boolean finished = runtime.pollScheduledOnce(uri, timeout, exchange -> {
            try {
                Exception f = exchange.getException();
                if (f != null) {
                    throw f;
                }
                items.add(mapper.apply(exchange));
            } catch (Exception t) {
                exchange.setException(t);
                failed.incrementAndGet();
                firstFailure.compareAndSet(null, t);
            }
            done(exchange); // commit (e.g. mark the mail as seen), or roll back a failed exchange
        });
        if (!finished) {
            if (items.isEmpty()) {
                throw new TimeoutException("Poll of [" + uri.substring(0, Math.max(uri.indexOf(':'), 0))
                        + "] did not finish within " + timeout + " ms.");
            }
            LOG.warn("Poll did not finish within {} ms; emitting the {} item(s) mapped so far.", timeout, items.size());
        }
        Exception failure = firstFailure.get();
        if (failure != null) {
            if (items.isEmpty()) {
                throw failure;
            }
            LOG.warn(
                    "{} of {} polled item(s) failed and were skipped: {}",
                    failed.get(),
                    items.size() + failed.get(),
                    CamelErrorMapper.describe(failure));
        }
        return new ArrayList<>(items);
    }

    /**
     * Reads the message body with Camel's type converters (so streams, files and byte arrays
     * work): JSON objects and arrays are parsed, anything else is returned as the string.
     *
     * @param exchange consumed exchange
     * @return the parsed body, the string, or {@code null}
     */
    public static Object readBody(Exchange exchange) {
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return Json.MAPPER.readValue(raw, Object.class);
            } catch (Exception ignored) {
                return raw;
            }
        }
        return raw;
    }
}
