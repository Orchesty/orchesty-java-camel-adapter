package io.orchesty.sdk.camel;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.PollingConsumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.apache.camel.support.PollingConsumerSupport;
import org.apache.camel.support.ScheduledPollConsumer;
import org.apache.camel.support.SynchronizationAdapter;

/**
 * Test component that behaves like the Google API components: the first {@code credential} it
 * sees becomes its cached "client", and every producer or poll on that component instance answers
 * with the cached value. Resolved by scheme {@code tenant-echo} through
 * {@code META-INF/services/org/apache/camel/component/tenant-echo}.
 *
 * <p>Polled bodies are {@code <client>-<index>}. Special credentials: {@code fail} makes the
 * producer throw, {@code block} makes it wait for {@link #sendRelease}, {@code slow} makes a poll
 * take two seconds before emitting, {@code many} makes a poll emit five exchanges,
 * {@code three-then-slow} emits three and then hangs, {@code three-bad-middle} emits three with a
 * failed middle one. Scheduled exchanges count {@link #COMMITTED} / {@link #ROLLED_BACK} when they
 * are completed, as camel-mail commits. The path {@code event} gives an event-driven (not scheduled) consumer, the
 * path {@code flaky} an on-demand consumer whose second receive fails.
 */
public class TenantEchoComponent extends DefaultComponent {

    /** Number of component instances created, to check reuse. */
    static final AtomicInteger INSTANCES = new AtomicInteger();

    /** Number of component instances stopped, to check cleanup. */
    static final AtomicInteger STOPPED = new AtomicInteger();

    /** Number of {@code flaky} and scheduled items committed. */
    static final AtomicInteger COMMITTED = new AtomicInteger();

    /** Number of scheduled items rolled back. */
    static final AtomicInteger ROLLED_BACK = new AtomicInteger();

    /** Number of {@code flaky} polling consumers stopped. */
    static final AtomicInteger POLLING_CONSUMERS_STOPPED = new AtomicInteger();

    /** When set, every poll waits here, so two polls can be forced to overlap. */
    static volatile CyclicBarrier pollBarrier;

    /** Counted down when a {@code block} send has started. */
    static volatile CountDownLatch sendEntered = new CountDownLatch(1);

    /** A {@code block} send waits for this latch. */
    static volatile CountDownLatch sendRelease = new CountDownLatch(1);

    private volatile String cachedCredential;

    public TenantEchoComponent() {
        INSTANCES.incrementAndGet();
    }

    static void reset() {
        INSTANCES.set(0);
        STOPPED.set(0);
        COMMITTED.set(0);
        ROLLED_BACK.set(0);
        POLLING_CONSUMERS_STOPPED.set(0);
        pollBarrier = null;
        sendEntered = new CountDownLatch(1);
        sendRelease = new CountDownLatch(1);
    }

    synchronized String client(String credential) {
        if (cachedCredential == null) {
            cachedCredential = credential;
        }
        return cachedCredential;
    }

    @Override
    protected void doStop() throws Exception {
        STOPPED.incrementAndGet();
        super.doStop();
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
        Object credential = parameters.remove("credential");
        return new EchoEndpoint(uri, this, remaining, credential == null ? "" : String.valueOf(credential));
    }

    private static final class EchoEndpoint extends DefaultEndpoint {

        private final TenantEchoComponent component;
        private final String path;
        private final String credential;

        EchoEndpoint(String uri, TenantEchoComponent component, String path, String credential) {
            super(uri, component);
            this.component = component;
            this.path = path;
            this.credential = credential;
        }

        @Override
        public Producer createProducer() {
            return new DefaultProducer(this) {
                @Override
                public void process(Exchange exchange) throws Exception {
                    if ("fail".equals(credential)) {
                        throw new IllegalStateException("remote side unavailable");
                    }
                    if ("block".equals(credential)) {
                        sendEntered.countDown();
                        sendRelease.await(5, TimeUnit.SECONDS);
                    }
                    exchange.getMessage().setBody(component.client(credential));
                }
            };
        }

        /** Path {@code flaky}: the first receive returns an item, every later receive fails. */
        @Override
        public PollingConsumer createPollingConsumer() throws Exception {
            if ("same".equals(path)) {
                return sameFileConsumer();
            }
            if (!"flaky".equals(path)) {
                return super.createPollingConsumer();
            }
            Endpoint endpoint = this;
            return new PollingConsumerSupport(this) {
                private int received;

                @Override
                public Exchange receive() {
                    return receiveNoWait();
                }

                @Override
                public Exchange receive(long timeout) {
                    return receiveNoWait();
                }

                @Override
                public Exchange receiveNoWait() {
                    if (++received > 1) {
                        throw new RuntimeCamelException("connection lost");
                    }
                    Exchange exchange = endpoint.createExchange();
                    exchange.getMessage().setBody("item-1");
                    exchange.getExchangeExtension().addOnCompletion(new SynchronizationAdapter() {
                        @Override
                        public void onComplete(Exchange done) {
                            COMMITTED.incrementAndGet();
                        }
                    });
                    return exchange;
                }

                @Override
                protected void doStart() {}

                @Override
                protected void doStop() {
                    POLLING_CONSUMERS_STOPPED.incrementAndGet();
                }
            };
        }

        /** Path {@code same}: every receive returns the same file, as an FTP source without move/delete. */
        private PollingConsumer sameFileConsumer() {
            Endpoint endpoint = this;
            return new PollingConsumerSupport(this) {
                @Override
                public Exchange receive() {
                    return receiveNoWait();
                }

                @Override
                public Exchange receive(long timeout) {
                    return receiveNoWait();
                }

                @Override
                public Exchange receiveNoWait() {
                    Exchange exchange = endpoint.createExchange();
                    exchange.getMessage().setHeader(Exchange.FILE_NAME, "same.txt");
                    exchange.getMessage().setBody("same");
                    return exchange;
                }

                @Override
                protected void doStart() {}

                @Override
                protected void doStop() {}
            };
        }

        @Override
        public Consumer createConsumer(Processor processor) {
            if ("event".equals(path)) {
                return new DefaultConsumer(this, processor);
            }
            ScheduledPollConsumer consumer = new ScheduledPollConsumer(this, processor) {
                @Override
                protected int poll() throws Exception {
                    CyclicBarrier barrier = pollBarrier;
                    if (barrier != null) {
                        barrier.await(5, TimeUnit.SECONDS);
                    }
                    if ("slow".equals(credential)) {
                        Thread.sleep(2000);
                    }
                    int count = "many".equals(credential) ? 5 : credential.startsWith("three") ? 3 : 1;
                    for (int i = 0; i < count; i++) {
                        Exchange exchange = createExchange(false);
                        exchange.getMessage().setBody(component.client(credential) + "-" + i);
                        if ("three-bad-middle".equals(credential) && i == 1) {
                            exchange.setException(new IllegalStateException("broken row"));
                        }
                        // Like camel-mail: the commit runs only when the exchange is completed.
                        exchange.getExchangeExtension().addOnCompletion(new SynchronizationAdapter() {
                            @Override
                            public void onComplete(Exchange done) {
                                COMMITTED.incrementAndGet();
                            }

                            @Override
                            public void onFailure(Exchange done) {
                                ROLLED_BACK.incrementAndGet();
                            }

                            @Override
                            public boolean allowHandover() {
                                return false;
                            }
                        });
                        getProcessor().process(exchange);
                    }
                    if ("three-then-slow".equals(credential)) {
                        Thread.sleep(5000);
                    }
                    return count;
                }
            };
            consumer.setInitialDelay(0);
            consumer.setRepeatCount(1);
            return consumer;
        }
    }
}
