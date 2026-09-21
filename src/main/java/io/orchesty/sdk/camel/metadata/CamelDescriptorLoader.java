package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;

/** Loads a {@link CamelDescriptor} from YAML. */
public final class CamelDescriptorLoader {

    private static final YAMLMapper MAPPER =
            (YAMLMapper) new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CamelDescriptorLoader() {}

    public static CamelDescriptor load(String yaml) {
        try {
            return MAPPER.readValue(yaml, CamelDescriptor.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid camel descriptor: " + e.getMessage(), e);
        }
    }

    public static CamelDescriptor load(InputStream in) {
        try {
            return MAPPER.readValue(in, CamelDescriptor.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid camel descriptor: " + e.getMessage(), e);
        }
    }

    public static CamelDescriptor loadResource(String classpathResource) {
        InputStream resource = CamelDescriptorLoader.class.getClassLoader().getResourceAsStream(classpathResource);
        if (resource == null) {
            throw new IllegalArgumentException("Descriptor resource not found: " + classpathResource);
        }
        try (InputStream in = resource) {
            return load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid camel descriptor: " + e.getMessage(), e);
        }
    }
}
