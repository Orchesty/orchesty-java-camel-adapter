package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.ApplicationInstallRepository;
import io.orchesty.sdk.application.WorkerApiClient;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.authorization.OAuth2StateNonce;
import io.orchesty.sdk.crypt.CryptManager;
import io.orchesty.sdk.crypt.WindWalkerCrypt;
import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.process.Headers;
import io.orchesty.sdk.process.ProcessDto;
import io.orchesty.sdk.process.ResultCode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tenants must never share a Camel component that caches credentials: connectors get one private
 * component per tenant and scheduled polls a fresh one per poll. Private components are never
 * registered in the context, so components that read their URI scheme keep working.
 */
class TenantIsolationTest {

    private CamelRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new CamelRuntime();
        TenantEchoComponent.reset();
    }

    @AfterEach
    void tearDown() {
        TenantEchoComponent.sendRelease.countDown();
        runtime.close();
        TenantEchoComponent.pollBarrier = null;
    }

    @Test
    void tenantsGetTheirOwnComponentClient() {
        assertEquals("credential-a", send("credential-a", "tenant-a"));
        assertEquals("credential-b", send("credential-b", "tenant-b"));
    }

    @Test
    void sameTenantReusesItsComponent() {
        send("credential-a", "tenant-a");
        send("credential-a", "tenant-a");

        assertEquals(1, TenantEchoComponent.INSTANCES.get());
        assertEquals(1, runtime.tenantComponentCount());
        assertFalse(runtime.context().getComponentNames().contains("tenant-echo"), "never registered");
    }

    @Test
    void leastRecentlyUsedTenantComponentIsStopped() {
        runtime.setMaxTenantComponents(1);

        send("credential-a", "tenant-a");
        send("credential-b", "tenant-b");

        assertEquals(1, runtime.tenantComponentCount());
        assertEquals(1, TenantEchoComponent.STOPPED.get(), "evicted component is stopped");
        assertEquals("credential-a", send("credential-a", "tenant-a"), "evicted tenant gets a fresh component");
    }

    @Test
    void evictedComponentIsStoppedOnlyAfterItsSendFinishes() throws Exception {
        runtime.setMaxTenantComponents(1);
        CompletableFuture<String> blocked = CompletableFuture.supplyAsync(() -> send("block", "tenant-a"));
        assertTrue(TenantEchoComponent.sendEntered.await(5, TimeUnit.SECONDS));

        send("credential-b", "tenant-b"); // evicts tenant-a while its send is running

        assertEquals(0, TenantEchoComponent.STOPPED.get(), "in-flight component stays started");
        TenantEchoComponent.sendRelease.countDown();
        assertEquals("block", blocked.get(5, TimeUnit.SECONDS));
        assertEquals(1, TenantEchoComponent.STOPPED.get(), "stopped by its last send");
    }

    @Test
    void withoutTenantKeyTheSharedComponentIsUsed() {
        assertEquals("credential-a", send("credential-a", null));
        assertEquals("credential-a", send("credential-b", null), "shared component keeps its client");
        assertEquals(0, runtime.tenantComponentCount());
    }

    @Test
    void connectorIsolatesUsersOfTheSameApplication() throws Exception {
        EchoConnector connector = new EchoConnector(runtime);
        connector.setRepository(new InstallsByUser(Map.of(
                "alice", install("alice", "credential-alice"),
                "bob", install("bob", "credential-bob"))));

        assertEquals(
                "credential-alice",
                connector.process(dto("alice")).getJsonData().get("client"));
        assertEquals(
                "credential-bob", connector.process(dto("bob")).getJsonData().get("client"));
    }

    @Test
    void connectorClassifiesEndpointFailuresInsteadOfThrowing() throws Exception {
        EchoConnector connector = new EchoConnector(runtime);
        connector.setRepository(new InstallsByUser(Map.of(
                "alice", install("alice", "fail"),
                "bob", install("bob", "credential-bob&bogus=1"))));

        assertThrows(OnRepeatException.class, () -> connector.process(dto("alice")));

        ProcessDto invalid = connector.process(dto("bob"));
        assertEquals(
                Integer.toString(ResultCode.STOP_AND_FAILED.code()),
                invalid.getHeader(Headers.RESULT_CODE),
                "an unknown endpoint option never succeeds on a retry");
    }

    @Test
    void connectorStopsWhenTheInstallOrUserIsMissing() throws Exception {
        EchoConnector connector = new EchoConnector(runtime);
        connector.setRepository(new InstallsByUser(Map.of("alice", install("alice", "credential-alice"))));

        ProcessDto notInstalled = connector.process(dto("mallory"));
        assertEquals(Integer.toString(ResultCode.STOP_AND_FAILED.code()), notInstalled.getHeader(Headers.RESULT_CODE));

        ProcessDto noUser = connector.process(new ProcessDto().setData("{}"));
        assertEquals(Integer.toString(ResultCode.STOP_AND_FAILED.code()), noUser.getHeader(Headers.RESULT_CODE));
    }

    @Test
    void changedCredentialsGiveANewTenantKey() {
        String before = TenantKey.of("echo", install("alice", "old-token"));
        String after = TenantKey.of("echo", install("alice", "new-token"));

        assertNotEquals(before, after);
        assertEquals(before, TenantKey.of("echo", install("alice", "old-token")));
        assertNull(TenantKey.of("echo", null));
    }

    @Test
    void nonCredentialSettingsKeepTheTenantKey() {
        String key = TenantKey.of("echo", install("alice", "token"));

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("credential", "token");
        form.put(OAuth2StateNonce.KEY, "nonce-1");
        form.put("frontend_redirect_url", "http://front");
        ApplicationInstall changed = install("alice", "token")
                .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, form, "limiter_form", Map.of("value", 5)));

        assertEquals(key, TenantKey.of("echo", changed));
    }

    @Test
    void overlappingScheduledPollsDoNotShareAComponent() throws Exception {
        TenantEchoComponent.pollBarrier = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<Object>> a = pool.submit(() -> poll("tenant-echo:x?credential=A", 5000));
            Future<List<Object>> b = pool.submit(() -> poll("tenant-echo:x?credential=B", 5000));

            assertEquals(List.of("A-0"), a.get());
            assertEquals(List.of("B-0"), b.get());
        }
        assertEquals(2, TenantEchoComponent.STOPPED.get(), "no private component survives a poll");
        assertFalse(runtime.context().getComponentNames().contains("tenant-echo"));
    }

    @Test
    void scheduledPollCommitsEveryMappedExchange() throws Exception {
        assertEquals(5, poll("tenant-echo:x?credential=many", 5000).size());
        assertEquals(5, TenantEchoComponent.COMMITTED.get(), "completed during the poll, as camel-mail needs");
    }

    @Test
    void unfinishedPollReturnsWhatItMapped() throws Exception {
        long start = System.nanoTime();
        List<Object> polled = poll("tenant-echo:x?credential=three-then-slow", 200);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(3, polled.size(), "items already committed are kept");
        assertEquals(3, TenantEchoComponent.COMMITTED.get());
        assertTrue(millis < 200 + CamelRuntime.POLL_GRACE_MS + 1000, "took " + millis + " ms");
    }

    @Test
    void failingScheduledItemIsRolledBackWithoutDroppingTheOthers() throws Exception {
        assertEquals(
                List.of("three-bad-middle-0", "three-bad-middle-2"),
                poll("tenant-echo:x?credential=three-bad-middle", 5000));
        assertEquals(2, TenantEchoComponent.COMMITTED.get());
        assertEquals(1, TenantEchoComponent.ROLLED_BACK.get());
    }

    @Test
    void scheduledItemThatCannotBeMappedIsRolledBack() throws Exception {
        List<Object> items =
                CamelSourcePoller.pollScheduled(runtime, "tenant-echo:x?credential=many", 5000, exchange -> {
                    String body = exchange.getMessage().getBody(String.class);
                    if (body.endsWith("-4")) {
                        throw new IllegalStateException("cannot map");
                    }
                    return body;
                });

        assertEquals(4, items.size());
        assertEquals(4, TenantEchoComponent.COMMITTED.get());
        assertEquals(1, TenantEchoComponent.ROLLED_BACK.get(), "the source keeps the item for the next poll");
    }

    @Test
    void onDemandPollKeepsCommittedItemsWhenALaterReceiveFails() throws Exception {
        List<Object> items = CamelSourcePoller.pollOnDemand(
                runtime,
                "tenant-echo:flaky",
                1000,
                10,
                exchange -> exchange.getMessage().getBody(String.class));

        assertEquals(List.of("item-1"), items);
        assertEquals(1, TenantEchoComponent.COMMITTED.get(), "item 1 was committed and is emitted");
        assertEquals(1, TenantEchoComponent.POLLING_CONSUMERS_STOPPED.get(), "one consumer per batch, stopped");
    }

    @Test
    void onDemandPollStopsWhenTheSameFileComesBack() throws Exception {
        List<Object> items = CamelSourcePoller.pollOnDemand(
                runtime,
                "tenant-echo:same",
                1000,
                10,
                exchange -> exchange.getMessage().getBody(String.class));

        assertEquals(List.of("same"), items, "an uncommitted file is emitted once, not once per receive");
    }

    @Test
    void unfinishedScheduledPollWithoutItemsTimesOut() throws Exception {
        assertThrows(TimeoutException.class, () -> poll("tenant-echo:x?credential=slow", 100));

        assertThrows(
                OnRepeatException.class,
                () -> CamelErrorMapper.classifyException(
                        new ProcessDto(), new TimeoutException("poll"), RetryPolicy.httpDefault()));
    }

    @Test
    void eventDrivenConsumerCannotBePolledAsScheduled() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> runtime.pollScheduledOnce("tenant-echo:event", 1000, x -> {}));
        assertTrue(e.getMessage().contains("on-demand"), e.getMessage());
    }

    @Test
    void realComponentWorksAsAPrivateInstance(@TempDir Path dir) throws Exception {
        Exchange exchange = runtime.newExchange();
        exchange.getMessage().setBody("hello");
        Exchange sent = runtime.send("file:" + dir + "?fileName=out.txt", exchange, "tenant-a");
        assertNull(sent.getException());
        assertEquals("hello", Files.readString(dir.resolve("out.txt")));

        String uri = "file:" + dir + "?delete=true&initialDelay=0&repeatCount=1";
        assertEquals(List.of("hello"), poll(uri, 5000), "the body is read before the commit deletes the file");
        assertFalse(Files.exists(dir.resolve("out.txt")), "a scheduled poll commits");
        assertEquals(List.of(), poll(uri, 5000));
    }

    @Test
    void mailKeepsItsSchemePortAndSecurity() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            CompletableFuture<String> greeting = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    OutputStream out = client.getOutputStream();
                    out.write("220 test ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                            .readLine();
                } catch (Exception e) {
                    return "error: " + e;
                }
            });
            sendMail("smtp", server.getLocalPort());
            String line = greeting.get(10, TimeUnit.SECONDS);
            assertNotNull(line);
            assertTrue(line.startsWith("EHLO") || line.startsWith("HELO"), line);
        }

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            CompletableFuture<Integer> firstByte = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    return client.getInputStream().read();
                } catch (Exception e) {
                    return -2;
                }
            });
            sendMail("smtps", server.getLocalPort());
            assertEquals(0x16, firstByte.get(10, TimeUnit.SECONDS), "smtps starts with a TLS handshake");
        }
    }

    /** Builds the URI like the connectors do ({@code smtp:host:port?...}, without {@code //}). */
    private void sendMail(String scheme, int port) {
        Exchange exchange = runtime.newExchange();
        exchange.getMessage().setBody("hello");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("host", "127.0.0.1");
        options.put("port", port);
        options.put("to", "a@example.com");
        options.put("from", "b@example.com");
        options.put("connectionTimeout", 3000);
        options.put("username", "u");
        options.put("password", "p");
        String uri = EndpointUriBuilder.build(runtime.context(), scheme, options);
        assertFalse(uri.startsWith(scheme + "://"), uri);
        runtime.send(uri, exchange, "tenant-mail");
    }

    private List<Object> poll(String uri, long timeout) throws Exception {
        return CamelSourcePoller.pollScheduled(
                runtime, uri, timeout, exchange -> exchange.getMessage().getBody(String.class));
    }

    private String send(String credential, String tenantKey) {
        Exchange out = runtime.send("tenant-echo:x?credential=" + credential, runtime.newExchange(), tenantKey);
        return out.getMessage().getBody(String.class);
    }

    private static ApplicationInstall install(String user, String credential) {
        return new ApplicationInstall()
                .setUser(user)
                .setName("echo")
                .setSdk("java")
                .addSettings(Map.of(CoreForm.AUTHORIZATION_FORM, Map.of("credential", credential)));
    }

    private static ProcessDto dto(String user) {
        ProcessDto dto = new ProcessDto().setData("{}");
        dto.addHeader("user", user);
        dto.addHeader("sdk", "java");
        return dto;
    }

    /** Connector that sends the install's credential to {@code tenant-echo} and returns the client. */
    private static final class EchoConnector extends ACamelConnector {

        EchoConnector(CamelRuntime runtime) {
            super(runtime);
        }

        @Override
        public String getName() {
            return "echo-connector";
        }

        @Override
        public String getApplicationName() {
            return "echo";
        }

        @Override
        protected String resolveEndpointUri(SendRequest request) {
            @SuppressWarnings("unchecked")
            Map<String, Object> form =
                    (Map<String, Object>) request.install().getSettings().get(CoreForm.AUTHORIZATION_FORM);
            return "tenant-echo:x?credential=" + form.get("credential");
        }

        @Override
        protected void prepareExchange(Exchange exchange, SendRequest request) {}

        @Override
        protected Integer extractStatus(Exchange exchange) {
            return null;
        }

        @Override
        protected void mapResponse(Exchange exchange, SendRequest request) {
            request.dto().setJsonData(Map.of("client", exchange.getMessage().getBody(String.class)));
        }
    }

    /** Returns the install of the requested user instead of calling worker-api. */
    private static final class InstallsByUser extends ApplicationInstallRepository {

        private final Map<String, ApplicationInstall> installs;

        InstallsByUser(Map<String, ApplicationInstall> installs) {
            super(new WorkerApiClient("http://127.0.0.1:1", ""), new CryptManager(List.of(new WindWalkerCrypt("x"))));
            this.installs = installs;
        }

        @Override
        public ApplicationInstall findByNameAndUser(String name, String user, List<String> sdks, Boolean enabled) {
            return installs.get(user);
        }
    }
}
