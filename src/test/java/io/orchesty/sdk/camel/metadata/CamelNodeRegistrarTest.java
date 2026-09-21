package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.orchesty.sdk.camel.ACamelPollingBatch;
import io.orchesty.sdk.camel.CamelRuntime;
import io.orchesty.sdk.node.IConnector;
import io.orchesty.sdk.node.NodeRegistry;
import io.orchesty.sdk.process.BatchProcessDto;
import io.orchesty.sdk.process.Json;
import io.orchesty.sdk.process.ProcessDto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CamelNodeRegistrarTest {

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

    /** A minimal source batch that polls a directory, used to observe output shaping. */
    private static final class ProbeBatch extends ACamelPollingBatch {
        private final Path dir;

        ProbeBatch(CamelRuntime runtime, Path dir) {
            super(runtime);
            this.dir = dir;
            setPollTimeoutMs(200);
        }

        @Override
        public String getName() {
            return "camel-file-source-poll";
        }

        @Override
        protected String resolveEndpointUri(PollRequest request) {
            return "file:" + dir.toAbsolutePath() + "?sortBy=file:name";
        }
    }

    private static List<Object> messages(BatchProcessDto dto) {
        return Json.fromString((String) dto.getBridgeData(), new TypeReference<>() {});
    }

    @Test
    void sourceDescriptorRegistersBatchAndAppliesOutput(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("3.txt"), "three", StandardCharsets.UTF_8);

        CamelDescriptor descriptor = CamelDescriptorLoader.loadResource("file-source-descriptor.yaml");
        NodeRegistry registry = new NodeRegistry();

        ProbeBatch batch = CamelNodeRegistrar.registerSource(registry, new ProbeBatch(runtime, dir), descriptor, null);

        // Registered under the batch list (auto-selected by kind: source).
        List<String> batchNames =
                registry.listBatches().stream().map(NodeRegistry.ListItem::name).toList();
        assertTrue(batchNames.contains("camel-file-source-poll"));

        // Output shaping from the descriptor (mode: chunk, chunkSize: 25) was applied: 3 files
        // fit in a single chunk -> one message. (ITEM would have produced three.)
        BatchProcessDto out = batch.process(new BatchProcessDto());
        assertEquals(1, messages(out).size());
    }

    @Test
    void connectorDescriptorRejectedBySourceRegistrar() {
        CamelDescriptor connectorDescriptor = CamelDescriptorLoader.loadResource("sftp-descriptor.yaml");
        NodeRegistry registry = new NodeRegistry();

        assertThrows(
                IllegalArgumentException.class,
                () -> CamelNodeRegistrar.registerSource(
                        registry, new ProbeBatch(runtime, Path.of("/tmp")), connectorDescriptor, null));
    }

    @Test
    void sourceDescriptorRejectedByConnectorRegistrar() {
        CamelDescriptor sourceDescriptor = CamelDescriptorLoader.loadResource("file-source-descriptor.yaml");
        NodeRegistry registry = new NodeRegistry();

        IConnector connector = new IConnector() {
            @Override
            public String getName() {
                return "x";
            }

            @Override
            public String getApplicationName() {
                return "";
            }

            @Override
            public ProcessDto process(ProcessDto dto) {
                return dto;
            }
        };

        assertThrows(
                IllegalArgumentException.class,
                () -> CamelNodeRegistrar.registerConnector(registry, connector, sourceDescriptor));
    }
}
