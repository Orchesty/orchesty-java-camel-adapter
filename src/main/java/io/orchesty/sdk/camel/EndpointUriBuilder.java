package io.orchesty.sdk.camel;

import io.orchesty.sdk.camel.metadata.CatalogFormGenerator;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.EndpointUriFactory;
import org.apache.camel.support.EndpointHelper;
import org.apache.camel.util.URISupport;
import org.apache.camel.util.UnsafeUriCharactersEncoder;

/**
 * Builds a Camel endpoint URI from a scheme and an option map via
 * {@link EndpointUriFactory} (the Catalog {@code syntax} plus query options).
 * Retry-related flags Orchesty relies on are applied when the component declares them, so
 * connectors do not hand-write {@code sftp://host:port/...} strings.
 *
 * <p>Options from the install or the message are checked with {@link UntrustedValues}; only the
 * developer's {@code trustedDefaults} skip that check. The built URI is parsed back the way Camel
 * will read it: a path value with {@code ?} or {@code &}, a secret with {@code )&}, or anything
 * else that adds or repeats an endpoint option is rejected with an
 * {@link IllegalArgumentException}. Build URIs only here: a hand-written URI string bypasses every
 * check.
 */
public final class EndpointUriBuilder {

    /** Options that limit how many messages one poll returns. */
    public static final List<String> POLL_LIMIT_OPTIONS = List.of("maxMessagesPerPoll", "maxResults");

    private static final Map<String, Set<String>> PATH_OPTIONS = new ConcurrentHashMap<>();

    private EndpointUriBuilder() {}

    /**
     * Builds the endpoint URI of a connector from its options, see
     * {@link #connectorUri(CamelContext, String, Map, Map)}; every option is checked with
     * {@link UntrustedValues}.
     *
     * @param context Camel context
     * @param scheme component scheme, for example {@code sftp}
     * @param properties endpoint options from the install or the message
     * @return the endpoint URI
     * @throws IllegalArgumentException when the component jar is not on the worker, a value is
     *     rejected by {@link UntrustedValues} or a value would add or repeat an endpoint option
     */
    public static String build(CamelContext context, String scheme, Map<String, Object> properties) {
        return connectorUri(context, scheme, properties, Map.of());
    }

    /**
     * Builds the endpoint URI of a polling source, see
     * {@link #sourceUri(CamelContext, String, Map, Map, Integer)}; every option is checked with
     * {@link UntrustedValues}.
     *
     * @param context Camel context
     * @param scheme component scheme
     * @param properties endpoint options from the install or the message
     * @param pollLimit maximum messages of one scheduled poll, or {@code null} for no limit
     * @return the endpoint URI
     * @throws IllegalArgumentException when the component jar is not on the worker, a value is
     *     rejected by {@link UntrustedValues} or a value would add or repeat an endpoint option
     */
    public static String build(CamelContext context, String scheme, Map<String, Object> properties, Integer pollLimit) {
        return sourceUri(context, scheme, properties, Map.of(), pollLimit);
    }

    /**
     * Builds the endpoint URI of a connector. {@code options} (from the install or the message) are
     * checked with {@link UntrustedValues}; {@code trustedDefaults} are the developer's own values
     * (for example a {@code #bean:} reference), are not checked and only fill options the first
     * map leaves unset. Empty options are dropped. Components with a {@code disconnect} option (for
     * example remote file components) disconnect after each send, so cached producers don't keep
     * idle sessions open.
     *
     * @param context Camel context
     * @param scheme component scheme, for example {@code sftp}
     * @param options endpoint options from the install or the message
     * @param trustedDefaults the developer's default options
     * @return the endpoint URI
     * @throws IllegalArgumentException when the component jar is not on the worker, a value is
     *     rejected by {@link UntrustedValues} or a value would add or repeat an endpoint option
     */
    public static String connectorUri(
            CamelContext context, String scheme, Map<String, Object> options, Map<String, Object> trustedDefaults) {
        return build(context, scheme, options, trustedDefaults, false, null);
    }

    /**
     * Builds the endpoint URI of a polling source, with the same split into checked
     * {@code options} and unchecked {@code trustedDefaults} as
     * {@link #connectorUri(CamelContext, String, Map, Map)}. A scheduled poll is limited to
     * {@code pollLimit} messages through {@code maxMessagesPerPoll} / {@code maxResults} when the
     * component declares them (a smaller configured value is kept). Sources keep their session for
     * the whole batch, so no {@code disconnect} is added.
     *
     * @param context Camel context
     * @param scheme component scheme
     * @param options endpoint options from the install or the message
     * @param trustedDefaults the developer's default options
     * @param pollLimit maximum messages of one scheduled poll, or {@code null} for no limit
     * @return the endpoint URI
     * @throws IllegalArgumentException when the component jar is not on the worker, a value is
     *     rejected by {@link UntrustedValues} or a value would add or repeat an endpoint option
     */
    public static String sourceUri(
            CamelContext context,
            String scheme,
            Map<String, Object> options,
            Map<String, Object> trustedDefaults,
            Integer pollLimit) {
        return build(context, scheme, options, trustedDefaults, true, pollLimit);
    }

    private static String build(
            CamelContext context,
            String scheme,
            Map<String, Object> options,
            Map<String, Object> trustedDefaults,
            boolean source,
            Integer pollLimit) {
        Map<String, Object> props = compact(options);
        UntrustedValues.checkAll(props, "message or application install");
        compact(trustedDefaults).forEach(props::putIfAbsent);
        EndpointUriFactory factory = requireFactory(context, scheme);
        applyKnownDefaults(factory, props);
        if (source) {
            applyPollLimit(factory, props, pollLimit);
        } else {
            putIfKnown(factory.propertyNames(), props, "disconnect", true);
        }
        Set<String> pathOptions = pathOptions(scheme);
        checkValues(scheme, factory, props, pathOptions);
        String uri;
        try {
            uri = factory.buildUri(scheme, props, true);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "Failed to build Camel URI for scheme [" + scheme + "]: " + e.getMessage(), e);
        }
        verify(scheme, uri, factory, props, pathOptions);
        return uri;
    }

    /**
     * Path values are copied into the URI unencoded and Camel ends a secret's {@code RAW(...)}
     * at {@code )&}, so these characters would add endpoint options nobody checked.
     */
    private static void checkValues(
            String scheme, EndpointUriFactory factory, Map<String, Object> props, Set<String> pathOptions) {
        props.forEach((key, value) -> {
            String text = String.valueOf(value);
            if (pathOptions.contains(key)
                    && (text.indexOf('?') >= 0
                            || text.indexOf('&') >= 0
                            || text.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Path option [" + key + "] of Camel component [" + scheme
                        + "] must not contain ?, & or control characters.");
            }
            if (factory.secretPropertyNames().contains(key) && text.contains(")&")) {
                throw new IllegalArgumentException(
                        "Secret option [" + key + "] of Camel component [" + scheme + "] must not contain )&.");
            }
        });
    }

    /**
     * Parses the built URI the way Camel's {@code DefaultComponent} will and checks that only the
     * intended query options arrive, each once; path options must not reappear as query options.
     */
    static void verify(
            String scheme, String uri, EndpointUriFactory factory, Map<String, Object> props, Set<String> pathOptions) {
        Map<String, Object> parsed;
        try {
            String normalized = UnsafeUriCharactersEncoder.encode(EndpointHelper.normalizeEndpointUri(uri));
            parsed = URISupport.parseParameters(new URI(normalized));
        } catch (URISyntaxException | RuntimeException e) {
            throw unsafe(scheme, e);
        }
        Set<String> expected = new HashSet<>();
        Map<String, String> multiValuePrefixes = factory.multiValuePrefixes();
        props.forEach((key, value) -> {
            if (pathOptions.contains(key)) {
                return;
            }
            String prefix = multiValuePrefixes == null ? null : multiValuePrefixes.get(key);
            if (prefix != null && value instanceof Map<?, ?> map) {
                map.keySet().forEach(k -> expected.add(prefix + k));
            } else {
                expected.add(key);
            }
        });
        parsed.forEach((key, value) -> {
            if (!expected.contains(key) || value instanceof List) {
                throw unsafe(scheme, null);
            }
        });
    }

    private static IllegalArgumentException unsafe(String scheme, Exception cause) {
        return new IllegalArgumentException(
                "Options for Camel component [" + scheme
                        + "] cannot be passed safely; a value adds or repeats an endpoint option.",
                cause);
    }

    /**
     * Returns the component's path parameters from the Camel Catalog, cached per scheme.
     *
     * @param scheme component scheme
     * @return the path parameter names; empty for a component outside the Catalog
     */
    static Set<String> pathOptions(String scheme) {
        return PATH_OPTIONS.computeIfAbsent(scheme, s -> new CatalogFormGenerator().pathOptions(s));
    }

    /**
     * Adds endpoint options from a message payload. The payload can only <b>add</b> options: what
     * the install or the defaults already set is kept. An option is accepted only when the
     * registration allows it ({@code payloadOptions}), the component declares it and it is not
     * secret; a value {@link UntrustedValues} rejects (a placeholder, an expression, a Camel
     * reference or a raw value) stops the message.
     *
     * @param context Camel context
     * @param scheme component scheme
     * @param target options to add to
     * @param payloadOptions the payload's option map; anything other than a map is ignored
     * @param payloadKey payload key the options came from, for the error message
     * @param allowed options the registration lets a payload set
     * @throws IllegalArgumentException for an option that is not allowed, not declared or secret,
     *     or a value {@link UntrustedValues} rejects
     */
    public static void addPayloadOptions(
            CamelContext context,
            String scheme,
            Map<String, Object> target,
            Object payloadOptions,
            String payloadKey,
            Collection<String> allowed) {
        if (!(payloadOptions instanceof Map<?, ?> options) || options.isEmpty()) {
            return;
        }
        options.forEach((k, v) -> addPayloadOption(context, scheme, target, String.valueOf(k), v, payloadKey, allowed));
    }

    /**
     * Adds one endpoint option from a message payload, with the checks of
     * {@link #addPayloadOptions}.
     *
     * @param context Camel context
     * @param scheme component scheme
     * @param target options to add to
     * @param key option name
     * @param value option value; {@code null} or empty is ignored
     * @param payloadKey payload key the option came from, for the error message
     * @param allowed options the registration lets a payload set
     * @throws IllegalArgumentException for an option that is not allowed, not declared or secret,
     *     or a value {@link UntrustedValues} rejects
     */
    public static void addPayloadOption(
            CamelContext context,
            String scheme,
            Map<String, Object> target,
            String key,
            Object value,
            String payloadKey,
            Collection<String> allowed) {
        if (allowed == null || !allowed.contains(key)) {
            throw new IllegalArgumentException("Option [" + key + "] of Camel component [" + scheme
                    + "] may not be set by the message ('" + payloadKey + "'); allow it with payloadOptions.");
        }
        EndpointUriFactory factory = requireFactory(context, scheme);
        if (!factory.propertyNames().contains(key)) {
            throw new IllegalArgumentException(
                    "Camel component [" + scheme + "] has no option [" + key + "] ('" + payloadKey + "').");
        }
        if (factory.secretPropertyNames().contains(key)) {
            throw new IllegalArgumentException(
                    "Secret option [" + key + "] must come from the application install, not '" + payloadKey + "'.");
        }
        UntrustedValues.check(key, value, "message ('" + payloadKey + "')");
        if (value != null && !String.valueOf(value).isEmpty()) {
            target.putIfAbsent(key, value);
        }
    }

    /**
     * Returns the component's URI factory.
     *
     * @throws IllegalArgumentException when the component jar is not on the worker
     */
    static EndpointUriFactory requireFactory(CamelContext context, String scheme) {
        EndpointUriFactory factory = context.getCamelContextExtension().getEndpointUriFactory(scheme);
        if (factory == null || !factory.isEnabled(scheme)) {
            throw new IllegalArgumentException(
                    "Camel component [" + scheme + "] is not available; add its jar to the worker.");
        }
        return factory;
    }

    /**
     * Disables Camel's own reconnects and HTTP exceptions when the component has those options.
     * Options the component doesn't know are not added, because strict components reject
     * unknown query parameters.
     */
    static void applyKnownDefaults(EndpointUriFactory factory, Map<String, Object> props) {
        Set<String> names = factory.propertyNames();
        // 1 ms, not 0: Camel 4.20+ schedules the (single) connect attempt with this delay and the
        // JDK scheduler rejects 0 (IllegalArgumentException). With 0 reconnect attempts it fails fast.
        putIfKnown(names, props, "reconnectDelay", 1);
        putIfKnown(names, props, "maximumReconnectAttempts", 0);
        putIfKnown(names, props, "throwExceptionOnConnectFailed", true);
        putIfKnown(names, props, "throwExceptionOnFailure", false);
    }

    private static void applyPollLimit(EndpointUriFactory factory, Map<String, Object> props, Integer pollLimit) {
        Set<String> names = factory.propertyNames();
        if (names == null || pollLimit == null) {
            return;
        }
        for (String option : POLL_LIMIT_OPTIONS) {
            if (names.contains(option)) {
                Integer configured = asInt(props.get(option));
                props.put(option, configured != null && configured > 0 ? Math.min(configured, pollLimit) : pollLimit);
            }
        }
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putIfKnown(Set<String> names, Map<String, Object> props, String key, Object value) {
        if (names != null && names.contains(key)) {
            props.putIfAbsent(key, value);
        }
    }

    private static Map<String, Object> compact(Map<String, Object> properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (properties == null) {
            return props;
        }
        properties.forEach((k, v) -> {
            if (v != null && !String.valueOf(v).isEmpty()) {
                props.put(k, v);
            }
        });
        return props;
    }
}
