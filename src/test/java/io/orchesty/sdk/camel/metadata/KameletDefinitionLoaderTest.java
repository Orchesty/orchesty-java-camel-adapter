package io.orchesty.sdk.camel.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Template parameters used outside an expression are reported, because Camel substitutes them as text. */
class KameletDefinitionLoaderTest {

    @Test
    void parametersInEndpointUrisAreReported() {
        assertEquals(
                Set.of("directory", "fileName"),
                KameletDefinitionLoader.loadByName("file-sink").getUriParameterNames(),
                "note is used only in a simple expression");
        assertEquals(
                Set.of(), KameletDefinitionLoader.loadByName("uppercase-action").getUriParameterNames());
    }

    @Test
    void placeholderVariantsAndUnknownPlacesCount() {
        String yaml = """
                metadata:
                  name: variants
                spec:
                  template:
                    from:
                      uri: "kamelet:source"
                      steps:
                        - setHeader:
                            name: "{{headerName}}"
                            constant: "{{headerValue}}"
                        - to: "sftp:{{?host}}:{{port:22}}/dir?password={{password}}"
                """;
        KameletDefinition definition =
                KameletDefinitionLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Set.of("headerName", "host", "port", "password"), definition.getUriParameterNames());
    }

    @Test
    void endpointOptionsCountWhateverTheyAreCalled() {
        String yaml = """
                metadata:
                  name: options
                spec:
                  template:
                    from:
                      uri: "kamelet:source"
                      steps:
                        - to:
                            uri: "bean:orders"
                            parameters:
                              method: "{{operation}}"
                              header: "{{h}}"
                """;
        assertEquals(Set.of("operation", "h"), load(yaml).getUriParameterNames());
    }

    @Test
    void templatesThatBuildUrisAtRuntimeCountEveryParameter() {
        String yaml = """
                metadata:
                  name: via-header
                spec:
                  definition:
                    properties:
                      dir: {type: string}
                      note: {type: string}
                  template:
                    from:
                      uri: "kamelet:source"
                      steps:
                        - log: "${body} {{note}}"
                        - setHeader:
                            name: target
                            constant: "{{dir}}"
                        - toD: "file:${header.target}"
                """;
        assertEquals(Set.of("dir", "note"), load(yaml).getUriParameterNames(), "the header reaches a toD URI");

        String staticYaml = yaml.replace("toD: \"file:${header.target}\"", "to: \"file:out\"");
        assertEquals(
                Set.of("note"),
                load(staticYaml).getUriParameterNames(),
                "without a runtime URI only the header value stays expression-only (log is not a language key)");
    }

    private static KameletDefinition load(String yaml) {
        return KameletDefinitionLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void expressionsThatBecomeEndpointUrisCount() {
        String yaml = """
                metadata:
                  name: dynamic
                spec:
                  template:
                    from:
                      uri: "kamelet:source"
                      steps:
                        - setBody:
                            simple: "{{note}}"
                        - enrich:
                            expression:
                              simple: "file:{{inbox}}"
                        - pollEnrich:
                            constant: "ftp:{{ftpHost}}"
                        - recipientList:
                            simple: "file:{{a}},file:{{b}}"
                        - routingSlip:
                            header: "{{slip}}"
                """;
        KameletDefinition definition =
                KameletDefinitionLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Set.of("inbox", "ftpHost", "a", "b", "slip"), definition.getUriParameterNames());
        assertEquals(Set.of("a", "b", "slip"), definition.getEndpointListParameterNames());
        assertEquals(
                Set.of("directory"),
                KameletDefinitionLoader.loadByName("list-sink").getEndpointListParameterNames());
    }
}
