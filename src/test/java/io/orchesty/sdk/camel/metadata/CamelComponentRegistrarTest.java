package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.application.IApplication;
import io.orchesty.sdk.camel.ACamelPollingBatch;
import io.orchesty.sdk.camel.CamelRuntime;
import io.orchesty.sdk.camel.CatalogCamelConnector;
import io.orchesty.sdk.camel.CatalogCamelSourceBatch;
import io.orchesty.sdk.node.NodeRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CamelComponentRegistrarTest {

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
    void registerSftpInfersAppAndUsesCatalogConnector() {
        NodeRegistry registry = new NodeRegistry();
        IApplication app = CamelComponentRegistrar.registerComponent(registry, runtime, "sftp");

        assertEquals("camel-sftp", app.getName());
        assertTrue(app.getPublicName().toLowerCase().contains("sftp"));
        assertFalse(app.getFormStack().getForms().isEmpty());
        Set<String> keys = app.getFormStack().getForms().get(0).getFields().stream()
                .map(f -> f.getKey())
                .collect(Collectors.toSet());
        assertTrue(keys.contains("host"), keys.toString());
        assertTrue(keys.contains("username"), keys.toString());
        assertTrue(keys.contains("password"), keys.toString());

        List<String> connectors = registry.listConnectors().stream()
                .map(NodeRegistry.ListItem::name)
                .toList();
        assertTrue(connectors.contains("camel-sftp"));
        assertEquals("camel-sftp", registry.getConnector("camel-sftp").getApplicationName());
        assertTrue(registry.getConnector("camel-sftp") instanceof CatalogCamelConnector);
    }

    @Test
    void registerFileSourceUsesHistoricalNamesWhenOverridden() {
        NodeRegistry registry = new NodeRegistry();
        CamelComponentRegistrar.registerComponent(
                registry,
                runtime,
                "file",
                ComponentOptions.source()
                        .name("camel-file-source")
                        .connectorName("camel-file-source-poll")
                        .includeForm("directoryName", "delete", "recursive", "include")
                        .emit(ACamelPollingBatch.EmitMode.ITEM)
                        .build());

        assertTrue(registry.getApplications().containsKey("camel-file-source"));
        List<String> batches =
                registry.listBatches().stream().map(NodeRegistry.ListItem::name).toList();
        assertTrue(batches.contains("camel-file-source-poll"));
        assertTrue(registry.getBatch("camel-file-source-poll") instanceof CatalogCamelSourceBatch);
    }

    @Test
    void sourcesAreScheduledUnlessTheyKeepCredentialsInTheUri() {
        assertTrue(CamelComponentRegistrar.isScheduled(CamelComponentRegistrar.descriptorFor(
                "google-sheets-stream", ComponentOptions.source().build())));
        assertFalse(CamelComponentRegistrar.isScheduled(CamelComponentRegistrar.descriptorFor(
                "file", ComponentOptions.source().build())));
        assertFalse(CamelComponentRegistrar.isScheduled(CamelComponentRegistrar.descriptorFor(
                "google-sheets-stream", ComponentOptions.source().onDemand().build())));

        CamelDescriptor yaml = CamelDescriptorLoader.load("component: sftp\nkind: source\nscheduled: true\n");
        assertTrue(CamelComponentRegistrar.isScheduled(yaml.applyCatalogDefaults()));

        CamelDescriptor sql = new CamelDescriptor();
        sql.component = "sql";
        assertTrue(CamelComponentRegistrar.isScheduled(sql), "sql has no real on-demand consumer");
    }

    @Test
    void scheduledSourceWithoutPollLimitIsReported() {
        assertTrue(CamelComponentRegistrar.warnWithoutPollLimit(runtime, "kamelet"));
        assertFalse(CamelComponentRegistrar.warnWithoutPollLimit(runtime, "google-sheets-stream"));
        assertFalse(CamelComponentRegistrar.warnWithoutPollLimit(runtime, "file"));
    }

    @Test
    void payloadAllowListsReachTheDescriptor() {
        CamelDescriptor descriptor = CamelComponentRegistrar.descriptorFor(
                "sftp",
                ComponentOptions.builder()
                        .payloadOptions("directoryName")
                        .payloadHeaders("CamelFileName")
                        .build());

        assertEquals(List.of("directoryName"), descriptor.payloadOptions);
        assertEquals(List.of("CamelFileName"), descriptor.payloadHeaders);
        CamelDescriptor yaml = CamelDescriptorLoader.load("component: sftp\npayloadOptions: [fileName]\n");
        assertEquals(List.of("fileName"), yaml.payloadOptions);
    }

    @Test
    void sourceFactoryReceivesTheUriDefaults() {
        Map<String, Object> received = new HashMap<>();
        CamelComponentRegistrar.registerComponent(
                new NodeRegistry(),
                runtime,
                "google-sheets-stream",
                ComponentOptions.source()
                        .uriDefault("majorDimension", "COLUMNS")
                        .sourceFactory((camel, name, app, defaults) -> {
                            received.putAll(defaults);
                            return new CatalogCamelSourceBatch(
                                    camel, "google-sheets-stream", name, app, defaults, true);
                        })
                        .build());

        assertEquals("COLUMNS", received.get("majorDimension"));
        assertEquals(0, received.get("initialDelay"), "scheduled by default");
    }

    @Test
    void descriptorForAppliesExclude() {
        CamelDescriptor descriptor = CamelComponentRegistrar.descriptorFor(
                "sftp",
                ComponentOptions.builder()
                        .excludeForm("privateKey", "privateKeyUri")
                        .build());
        assertFalse(descriptor.application.authForm.contains("privateKey"));
        assertTrue(descriptor.application.authForm.contains("host"));
    }
}
