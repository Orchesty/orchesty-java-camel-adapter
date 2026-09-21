package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.component.file.remote.SftpEndpoint;
import org.apache.camel.spi.EndpointUriFactory;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointUriBuilderTest {

    private CamelRuntime runtime;

    @BeforeAll
    void setUp() {
        runtime = new CamelRuntime();
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void buildsSftpUriFromCatalogSyntax() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", "sftp.example.com");
        props.put("port", 22);
        props.put("username", "alice");
        props.put("password", "secret");
        props.put("directoryName", "uploads");

        String uri = EndpointUriBuilder.build(runtime.context(), "sftp", props);
        assertTrue(uri.startsWith("sftp:"), uri);
        assertTrue(uri.contains("sftp.example.com"), uri);
        assertTrue(uri.contains("alice"), uri);
        assertTrue(uri.contains("uploads") || uri.contains("directoryName"), uri);
    }

    @Test
    void disablesCamelReconnectWithSchedulableDelay() {
        String uri = EndpointUriBuilder.build(runtime.context(), "sftp", Map.of("host", "sftp.example.com"));

        assertTrue(uri.contains("maximumReconnectAttempts=0"), uri);
        assertTrue(uri.contains("reconnectDelay=1"), uri); // 0 is rejected by Camel 4.20+'s connect scheduler
        assertTrue(uri.contains("throwExceptionOnConnectFailed=true"), uri);
    }

    @Test
    void unreachableSftpFailsFastWithConnectError() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", "127.0.0.1");
        props.put("port", closedPort);
        props.put("username", "alice");
        props.put("password", "secret");
        String uri = EndpointUriBuilder.build(runtime.context(), "sftp", props);

        Exchange exchange = new DefaultExchange(runtime.context());
        exchange.getMessage().setBody("x");
        exchange.getMessage().setHeader(Exchange.FILE_NAME, "x.txt");
        long start = System.nanoTime();
        runtime.context().createProducerTemplate().send(uri, exchange);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertInstanceOf(
                GenericFileOperationFailedException.class,
                exchange.getException(),
                String.valueOf(exchange.getException()));
        assertTrue(millis < 900, "no Camel-side reconnect wait expected, took " + millis + " ms");
    }

    @Test
    void connectorsDisconnectAndScheduledSourcesAreLimited() {
        String source = EndpointUriBuilder.build(runtime.context(), "sftp", Map.of("host", "h"), null);
        assertFalse(source.contains("disconnect"), "a source keeps its session for the batch: " + source);
        assertFalse(source.contains("maxMessagesPerPoll"), "on-demand polls are not limited: " + source);

        String file = EndpointUriBuilder.build(runtime.context(), "file", Map.of("directoryName", "/tmp/in"), 2);
        assertTrue(file.contains("maxMessagesPerPoll=2"), file);

        String connector = EndpointUriBuilder.build(runtime.context(), "sftp", Map.of("host", "h"));
        assertTrue(connector.contains("disconnect=true"), connector);
    }

    @Test
    void missingComponentIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointUriBuilder.build(runtime.context(), "no-such-component", Map.of("x", 1)));
    }

    @Test
    void buildsFileUri() {
        String uri = EndpointUriBuilder.build(runtime.context(), "file", Map.of("directoryName", "/tmp/in"));
        assertTrue(uri.startsWith("file:"), uri);
        assertTrue(uri.contains("/tmp/in") || uri.contains("tmp"), uri);
    }

    @Test
    void optionsAreCheckedButTrustedDefaultsAreNot() {
        for (Map<String, Object> options : List.<Map<String, Object>>of(
                Map.of("host", "h", "directoryName", "{{env:HOME}}"),
                Map.of("host", "h", "keyPair", "#class:java.lang.Object"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EndpointUriBuilder.build(runtime.context(), "sftp", options),
                    "a subclass that forwards payload values is protected too: " + options);
        }

        String uri = EndpointUriBuilder.connectorUri(
                runtime.context(), "sftp", Map.of("host", "h"), Map.of("keyPair", "#bean:keyPair"));
        assertTrue(uri.contains("keyPair="), "the developer's defaults may use references: " + uri);

        String source = EndpointUriBuilder.sourceUri(
                runtime.context(), "sftp", Map.of("host", "h", "binary", false), Map.of("binary", true), null);
        assertTrue(source.contains("binary=false"), "options win over defaults: " + source);
    }

    @Test
    void hashInAPathValueIsKept() {
        String uri = EndpointUriBuilder.build(runtime.context(), "sftp", Map.of("host", "h", "directoryName", "in#1"));
        SftpEndpoint endpoint = runtime.context().getEndpoint(uri, SftpEndpoint.class);
        assertEquals("in#1", endpoint.getConfiguration().getDirectory(), "as Slack #general or sql :#id need: " + uri);
    }

    @Test
    void pathValuesCannotAddOptions() {
        for (String directory : new String[] {
            "in?delete=true", "in&delete=true", "in\nx", "in%3Fdelete%3Dtrue", "in%3Fa=1%26delete=true"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EndpointUriBuilder.build(
                            runtime.context(), "sftp", Map.of("host", "h", "directoryName", directory)),
                    directory);
        }
    }

    @Test
    void parseBackRejectsAddedRepeatedOrPathOptions() {
        EndpointUriFactory factory = EndpointUriBuilder.requireFactory(runtime.context(), "sftp");
        Map<String, Object> props = Map.of("host", "h", "directoryName", "in", "password", "p");
        Set<String> path = EndpointUriBuilder.pathOptions("sftp");

        EndpointUriBuilder.verify("sftp", "sftp:h/in?password=RAW(p)", factory, props, path);
        for (String uri : new String[] {
            "sftp:h/in?password=RAW(p)&delete=true",
            "sftp:h/in?password=RAW(x)&password=RAW(p)",
            "sftp:h/in?host=evil&password=RAW(p)"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EndpointUriBuilder.verify("sftp", uri, factory, props, path),
                    uri);
        }
    }
}
