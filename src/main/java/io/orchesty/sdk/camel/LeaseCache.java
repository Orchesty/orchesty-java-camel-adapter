package io.orchesty.sdk.camel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Bounded, least-recently-used cache of resources shared by concurrent users.
 *
 * <p>The map lock is held only to find or insert an entry and to evict: the first user of a key
 * creates the resource outside the lock while later users of that key wait for it, so a slow
 * creation never blocks other keys. An entry evicted (or {@linkplain #clear cleared}) while in use
 * is closed by its last user, so nobody ever uses a closed resource. A failed creation is removed
 * and retried by the next user.
 *
 * @param <K> key type
 * @param <R> resource type
 */
final class LeaseCache<K, R> {

    /** Creates a resource. */
    @FunctionalInterface
    interface Factory<R> {
        R create() throws Exception;
    }

    /** One user's hold on a resource; closing it releases the hold. */
    interface Lease<R> extends AutoCloseable {
        R resource();

        @Override
        void close();
    }

    private final Map<K, Entry<R>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Consumer<R> closer;
    private volatile int maxSize;

    LeaseCache(int maxSize, Consumer<R> closer) {
        setMaxSize(maxSize);
        this.closer = closer;
    }

    void setMaxSize(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1.");
        }
        this.maxSize = maxSize;
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /**
     * Returns a lease on the key's resource, creating the resource on first use.
     *
     * @throws Exception the creation failure
     */
    Lease<R> acquire(K key, Factory<R> factory) throws Exception {
        List<Entry<R>> evicted = new ArrayList<>();
        Entry<R> entry;
        boolean creator = false;
        synchronized (entries) {
            entry = entries.get(key);
            if (entry == null) {
                entry = new Entry<>(closer);
                entries.put(key, entry);
                creator = true;
                Iterator<Entry<R>> eldest = entries.values().iterator();
                while (entries.size() > maxSize && eldest.hasNext()) {
                    Entry<R> candidate = eldest.next();
                    if (candidate != entry) {
                        evicted.add(candidate);
                        eldest.remove();
                    }
                }
            }
            entry.acquire();
        }
        evicted.forEach(Entry::retire);

        if (creator) {
            try {
                entry.future.complete(factory.create());
            } catch (Throwable e) {
                entry.future.completeExceptionally(e);
                remove(key, entry);
            }
        }
        try {
            R resource = entry.await();
            Entry<R> held = entry;
            return new Lease<>() {
                private boolean closed;

                @Override
                public R resource() {
                    return resource;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        held.release();
                    }
                }
            };
        } catch (Exception | Error e) {
            entry.release();
            throw e;
        }
    }

    /** Removes every entry; entries in use are closed by their last user. */
    void clear() {
        List<Entry<R>> all;
        synchronized (entries) {
            all = new ArrayList<>(entries.values());
            entries.clear();
        }
        all.forEach(Entry::retire);
    }

    private void remove(K key, Entry<R> entry) {
        synchronized (entries) {
            entries.remove(key, entry);
        }
    }

    private static final class Entry<R> {

        private final CompletableFuture<R> future = new CompletableFuture<>();
        private final Consumer<R> closer;
        private int users;
        private boolean retired;
        private boolean closed;

        Entry(Consumer<R> closer) {
            this.closer = closer;
        }

        synchronized void acquire() {
            users++;
        }

        R await() throws Exception {
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw e;
            }
        }

        void release() {
            boolean close;
            synchronized (this) {
                users--;
                close = retired && users == 0 && !closed;
                closed |= close;
            }
            if (close) {
                close();
            }
        }

        void retire() {
            boolean close;
            synchronized (this) {
                retired = true;
                close = users == 0 && !closed;
                closed |= close;
            }
            if (close) {
                close();
            }
        }

        private void close() {
            // A failed creation has nothing to close; a pending one still has its creator as a user.
            if (future.isDone() && !future.isCompletedExceptionally()) {
                closer.accept(future.join());
            }
        }
    }
}
