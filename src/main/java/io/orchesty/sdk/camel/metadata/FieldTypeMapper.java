package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.form.FieldType;

/**
 * Shared mapping of a schema option (type + secret + enum flags) to an Orchesty
 * {@link FieldType}. Used by both {@link CatalogFormGenerator} (Camel Catalog
 * metadata) and {@link KameletFormGenerator} (Kamelet {@code spec.definition}),
 * so the two form sources stay consistent.
 *
 * <p><b>Secrets map to {@link FieldType#TEXT}, not {@link FieldType#PASSWORD}.</b> By
 * convention Orchesty application forms never use {@code PASSWORD}: a password field is
 * returned to the UI masked as the boolean {@code true} (see
 * {@code AApplication.getApplicationForms}), so re-saving the form would write that
 * boolean back over the stored secret. Using {@code TEXT} lets the real value round-trip,
 * so editing other fields no longer wipes credentials. (Trade-off: the value is not masked
 * in the UI / detail response.)
 */
public final class FieldTypeMapper {

    private FieldTypeMapper() {}

    public static FieldType map(String type, boolean secret, boolean hasEnum) {
        if ("boolean".equals(type)) {
            return FieldType.CHECKBOX;
        }
        if (hasEnum) {
            return FieldType.SELECT_BOX;
        }
        switch (type == null ? "" : type) {
            case "integer":
            case "number":
            case "duration":
                return FieldType.NUMBER;
            default:
                // Secrets included: never PASSWORD (see class javadoc) — always TEXT.
                return FieldType.TEXT;
        }
    }
}
