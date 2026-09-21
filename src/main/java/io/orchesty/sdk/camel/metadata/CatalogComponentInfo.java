package io.orchesty.sdk.camel.metadata;

import java.util.List;

/**
 * Catalog-derived facts about a Camel component. Used to fill in a
 * {@link CamelDescriptor} so the developer does not repeat title, description,
 * kind or the obvious authorization fields.
 */
public record CatalogComponentInfo(
        String scheme,
        String title,
        String description,
        boolean consumerOnly,
        boolean producerOnly,
        List<String> authOptions) {}
