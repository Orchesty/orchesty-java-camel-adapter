package io.orchesty.sdk.camel;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Checks option values that come from an application install or a message payload before they
 * reach Camel. Camel resolves property placeholders and bean references in every endpoint option
 * and evaluates simple expressions in raw values and expression options, so such a value would run
 * in the worker that all tenants share. Values written in Camel's raw syntax are rejected too,
 * because Camel copies them into the URI unencoded.
 *
 * <p>A bare {@code #...} value (for example the password {@code #secret1} or the channel
 * {@code #general}) is accepted: Camel binds it literally to text options.
 */
public final class UntrustedValues {

    private static final List<String> FORBIDDEN_TOKENS = List.of("{{", "${", "$simple{");

    private static final List<String> FORBIDDEN_PREFIXES =
            List.of("RAW(", "RAW{", "#bean:", "#class:", "#type:", "#property:", "#valueAs(");

    private UntrustedValues() {}

    /**
     * Checks one value; maps and collections are checked element by element.
     *
     * @param key option or parameter name, for the error message
     * @param value the value
     * @param source where the value came from, for example {@code application install}
     * @throws IllegalArgumentException when the value contains a placeholder or an expression, or
     *     starts with a Camel reference or raw marker; the message never contains the value
     */
    public static void check(String key, Object value, String source) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> check(key, v, source));
            return;
        }
        if (value instanceof Collection<?> values) {
            values.forEach(v -> check(key, v, source));
            return;
        }
        if (!(value instanceof String s)) {
            return;
        }
        String text = s.trim();
        for (String token : FORBIDDEN_TOKENS) {
            if (text.contains(token)) {
                throw rejected(key, source, "contain [" + token + "] (placeholder or expression)");
            }
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (text.startsWith(prefix)) {
                throw rejected(key, source, "start with [" + prefix + "] (Camel reference or raw value)");
            }
        }
        if (text.equals("#autowired")) {
            throw rejected(key, source, "be [#autowired] (Camel reference)");
        }
    }

    /**
     * Checks every value of a map.
     *
     * @param values option values by name
     * @param source where the values came from, for the error message
     * @throws IllegalArgumentException for the first value {@link #check} rejects
     */
    public static void checkAll(Map<String, ?> values, String source) {
        values.forEach((k, v) -> check(k, v, source));
    }

    private static IllegalArgumentException rejected(String key, String source, String reason) {
        return new IllegalArgumentException("Value of [" + key + "] from the " + source + " must not " + reason + ".");
    }
}
