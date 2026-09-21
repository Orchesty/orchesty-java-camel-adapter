package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Resources are created once per key outside the map lock and closed by their last user. */
class LeaseCacheTest {

    private final List<String> closed = new CopyOnWriteArrayList<>();
    private final LeaseCache<String, String> cache = new LeaseCache<>(2, closed::add);

    @Test
    void concurrentFirstUseCreatesTheResourceOnce() throws Exception {
        AtomicInteger created = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        LeaseCache.Factory<String> slow = () -> {
            created.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return "r";
        };

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> use("k", slow));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> use("k", slow));
        Thread.sleep(200);
        release.countDown();

        assertEquals("r", first.get(5, TimeUnit.SECONDS));
        assertEquals("r", second.get(5, TimeUnit.SECONDS));
        assertEquals(1, created.get());
    }

    @Test
    void aHitDoesNotWaitForAnotherKeysCreation() throws Exception {
        try (LeaseCache.Lease<String> warm = cache.acquire("warm", () -> "w")) {
            assertEquals("w", warm.resource());
        }
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> use("cold", () -> {
            release.await(5, TimeUnit.SECONDS);
            return "c";
        }));
        Thread.sleep(100);

        long start = System.nanoTime();
        assertEquals("w", use("warm", () -> "never"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "the hit ran while 'cold' was being created");
        release.countDown();
        assertEquals("c", slow.get(5, TimeUnit.SECONDS));
    }

    @Test
    void failedCreationIsRetriedOnNextUse() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> cache.acquire("k", () -> {
                    throw new IllegalStateException("down");
                }));
        assertEquals(0, cache.size());
        assertEquals("ok", use("k", () -> "ok"));
    }

    @Test
    void evictedResourceIsClosedByItsLastUser() throws Exception {
        LeaseCache.Lease<String> held = cache.acquire("a", () -> "a");
        use("b", () -> "b");
        use("c", () -> "c"); // evicts "a" while it is held

        assertEquals(List.of(), closed);
        held.close();
        assertEquals(List.of("a"), closed);
    }

    @Test
    void evictionDuringCreationClosesTheResourceOnceReady() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> pending = CompletableFuture.supplyAsync(() -> use("a", () -> {
            release.await(5, TimeUnit.SECONDS);
            return "a";
        }));
        Thread.sleep(100);
        use("b", () -> "b");
        use("c", () -> "c"); // evicts "a" before it exists

        release.countDown();
        assertEquals("a", pending.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("a"), closed);
    }

    private String use(String key, LeaseCache.Factory<String> factory) {
        try (LeaseCache.Lease<String> lease = cache.acquire(key, factory)) {
            return lease.resource();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
