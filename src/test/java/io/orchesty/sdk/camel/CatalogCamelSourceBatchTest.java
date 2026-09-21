package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.orchesty.sdk.process.BatchProcessDto;
import io.orchesty.sdk.process.Headers;
import io.orchesty.sdk.process.Json;
import io.orchesty.sdk.process.ResultCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/** The generic polling source, exercised with the {@code file} component on a temporary directory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogCamelSourceBatchTest {

    private static final List<String> OPTIONS = List.of("directoryName", "sortBy", "initialDelay");

    private CamelRuntime runtime;
    private CatalogCamelSourceBatch batch;

    @BeforeAll
    void setUp() {
        runtime = new CamelRuntime();
        batch = fileSource("camel-file-source");
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    /** A file source with a fast empty poll and the test allow-list. */
    private CatalogCamelSourceBatch fileSource(String name) {
        CatalogCamelSourceBatch source = new CatalogCamelSourceBatch(runtime, "file", name, null);
        source.setPollTimeoutMs(200).setPayloadOptions(OPTIONS);
        return source;
    }

    private static List<Map<String, Object>> messages(BatchProcessDto dto) {
        return Json.fromString((String) dto.getBridgeData(), new TypeReference<>() {});
    }

    @Test
    void itemModeEmitsOneMessagePerFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.json"), "{\"x\":1}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.txt"), "hello", StandardCharsets.UTF_8);

        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\","
                + "\"endpointOptions\":{\"sortBy\":\"file:name\",\"initialDelay\":\"0\"}}");

        BatchProcessDto out = batch.process(dto);
        assertEquals(Integer.toString(ResultCode.SUCCESS.code()), out.getHeader(Headers.RESULT_CODE));

        // Default ITEM mode -> one message per file (true fan-out).
        List<Map<String, Object>> msgs = messages(out);
        assertEquals(2, msgs.size());

        // Each message body is a JSON string containing one item.
        Map<String, Object> first = Json.fromString((String) msgs.get(0).get("body"), new TypeReference<>() {});
        assertTrue(first.containsKey("fileName"));
        assertTrue(first.containsKey("body"));

        // Second poll: files committed (moved to .camel) -> empty batch, still success, no messages.
        BatchProcessDto out2 = batch.process(withDir(dir));
        assertEquals(Integer.toString(ResultCode.SUCCESS.code()), out2.getHeader(Headers.RESULT_CODE));
        assertEquals(0, messages(out2).size());
    }

    @Test
    void pageModeEmitsSingleMessageWithWholePage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("3.txt"), "three", StandardCharsets.UTF_8);

        CatalogCamelSourceBatch page = fileSource("camel-file-source-page");
        page.setEmitMode(ACamelPollingBatch.EmitMode.PAGE);

        BatchProcessDto out = page.process(withOptions(dir));
        List<Map<String, Object>> msgs = messages(out);
        assertEquals(1, msgs.size()); // whole page in one message

        Map<String, Object> body = Json.fromString((String) msgs.get(0).get("body"), new TypeReference<>() {});
        assertEquals(3, body.get("count"));
    }

    @Test
    void chunkModeEmitsOneMessagePerSlice(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("3.txt"), "three", StandardCharsets.UTF_8);

        CatalogCamelSourceBatch chunk = fileSource("camel-file-source-chunk");
        chunk.setEmitMode(ACamelPollingBatch.EmitMode.CHUNK).setChunkSize(2);

        BatchProcessDto out = chunk.process(withOptions(dir));
        List<Map<String, Object>> msgs = messages(out);
        // 3 items, chunk size 2 -> [2, 1]
        assertEquals(2, msgs.size());
        List<Object> firstChunk = Json.fromString((String) msgs.get(0).get("body"), new TypeReference<>() {});
        assertEquals(2, firstChunk.size());
    }

    @Test
    void emitModeCanBeOverriddenByPayload(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);

        // Batch default is ITEM, but the payload asks for PAGE.
        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\",\"emitMode\":\"page\","
                + "\"endpointOptions\":{\"sortBy\":\"file:name\"}}");

        BatchProcessDto out = batch.process(dto);
        assertEquals(1, messages(out).size());
    }

    @Test
    void batchSizeCapsTheNumberOfItems(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("3.txt"), "three", StandardCharsets.UTF_8);

        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\",\"batchSize\":2,"
                + "\"endpointOptions\":{\"sortBy\":\"file:name\"}}");

        BatchProcessDto out = batch.process(dto);
        assertEquals(2, messages(out).size()); // capped to 2 items -> 2 ITEM messages
    }

    @Test
    void unknownTopLevelKeysAreIgnored(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);

        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath()
                + "\",\"id\":1,\"password\":\"x\",\"fileOptions\":{\"delete\":true}}");

        BatchProcessDto out = batch.process(dto);
        assertEquals(Integer.toString(ResultCode.SUCCESS.code()), out.getHeader(Headers.RESULT_CODE));
        assertEquals(1, messages(out).size());
        assertTrue(Files.exists(dir.resolve(".camel").resolve("1.txt")), "moved, not deleted: no option alias left");
    }

    @Test
    void failingItemKeepsItsFileAndEarlierItemsAreEmitted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("3.txt"), "three", StandardCharsets.UTF_8);
        CatalogCamelSourceBatch failing = new CatalogCamelSourceBatch(runtime, "file", "camel-file-failing", null) {
            @Override
            protected Object mapExchange(Exchange exchange) {
                if ("2.txt".equals(exchange.getMessage().getHeader(Exchange.FILE_NAME))) {
                    throw new IllegalStateException("cannot map 2.txt");
                }
                return super.mapExchange(exchange);
            }
        };
        failing.setPollTimeoutMs(200).setPayloadOptions(OPTIONS);

        BatchProcessDto out = failing.process(withOptions(dir));
        assertEquals(Integer.toString(ResultCode.SUCCESS.code()), out.getHeader(Headers.RESULT_CODE));
        assertEquals(1, messages(out).size());
        assertFalse(Files.exists(dir.resolve("1.txt")), "committed");
        assertTrue(Files.exists(dir.resolve("2.txt")), "rolled back");
        assertTrue(Files.exists(dir.resolve("3.txt")), "not consumed");
    }

    @Test
    void payloadCannotRaiseThePollTimeout(@TempDir Path dir) throws Exception {
        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\",\"pollTimeoutMs\":60000}");

        long start = System.nanoTime();
        BatchProcessDto out = batch.process(dto);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Integer.toString(ResultCode.SUCCESS.code()), out.getHeader(Headers.RESULT_CODE));
        assertTrue(millis < 5000, "capped to the configured 200 ms, took " + millis + " ms");
    }

    @Test
    void topLevelOptionsNeedTheAllowListAndMustBePathParameters(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        CatalogCamelSourceBatch strict = new CatalogCamelSourceBatch(runtime, "file", "camel-file-strict", null);
        strict.setPollTimeoutMs(200).setPayloadOptions(List.of("delete"));

        // directoryName is not allowed here and delete is not a path parameter: both are ignored,
        // so the poll has no directory and the file is never deleted.
        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\",\"delete\":true}");
        strict.process(dto);
        assertTrue(Files.exists(dir.resolve("1.txt")));
    }

    @Test
    void allowedPathParameterCannotAddOptions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);

        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "?delete=true\"}");
        BatchProcessDto out = batch.process(dto);

        assertEquals(Integer.toString(ResultCode.STOP_AND_FAILED.code()), out.getHeader(Headers.RESULT_CODE));
        assertTrue(Files.exists(dir.resolve("1.txt")), "nothing was polled or deleted");
    }

    @Test
    void unknownComponentStops() throws Exception {
        // A component that is not on the worker never succeeds on a retry.
        ACamelPollingBatch bad = new ACamelPollingBatch(runtime) {
            @Override
            public String getName() {
                return "bad-source";
            }

            @Override
            protected String resolveEndpointUri(PollRequest request) {
                return "no-such-scheme-xyz:whatever";
            }
        };
        bad.setPollTimeoutMs(50);

        BatchProcessDto out = bad.process(new BatchProcessDto());
        assertEquals(
                Integer.toString(ResultCode.STOP_AND_FAILED.code()),
                out.getHeader(Headers.RESULT_CODE),
                out.getHeader(Headers.RESULT_MESSAGE));
    }

    private BatchProcessDto withDir(Path dir) {
        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\"}");
        return dto;
    }

    private BatchProcessDto withOptions(Path dir) {
        BatchProcessDto dto = new BatchProcessDto();
        dto.setData("{\"directoryName\":\"" + dir.toAbsolutePath() + "\","
                + "\"endpointOptions\":{\"sortBy\":\"file:name\"}}");
        return dto;
    }
}
