package io.orchesty.sdk.camel.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.orchesty.sdk.application.enums.AuthorizationType;
import io.orchesty.sdk.camel.RetryPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Declarative {@code runtime: camel} descriptor. Describes how a Camel component is
 * surfaced in Orchesty: which connection options become application form fields,
 * the connector name, and the (Orchesty-owned) retry policy.
 *
 * <pre>
 * runtime: camel
 * component: sftp
 * application:
 *   name: camel-sftp
 *   publicName: "Camel SFTP"
 *   description: "Upload files over SFTP"
 *   authForm: [host, port, username, password]
 * connector:
 *   name: camel-sftp-upload
 * retry:
 *   success: ["&lt;300"]
 *   repeat: [408, 429, "&gt;=500"]
 *   intervalSeconds: 60
 *   maxHops: 10
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CamelDescriptor {

    public String runtime;
    public String component;
    /** When set, this descriptor describes a Kamelet (template id) instead of a raw component. */
    public String kamelet;
    /**
     * Execution flavor: {@code "connector"} (producer/one-shot) or {@code "source"}
     * (polling consumer). When omitted, {@link #applyCatalogDefaults()} infers
     * {@code source} for {@code consumerOnly} components and {@code connector} otherwise.
     */
    public String kind;
    /**
     * For {@code kind: source}: {@code true} drains the component with one scheduled poll on a
     * private component instance, {@code false} with an on-demand consumer on the shared component.
     * When omitted, only {@code file}, {@code ftp}, {@code ftps} and {@code sftp} (which keep
     * credentials in the endpoint URI and have a real on-demand consumer) poll on demand; every
     * other source is scheduled.
     */
    public Boolean scheduled;
    /**
     * Endpoint options a message may add to the install's (connectors: {@code endpointOptions};
     * sources: {@code endpointOptions} and top-level path parameters). None when omitted.
     */
    public List<String> payloadOptions;
    /** {@code Camel*} headers a connector's message may set in {@code headers}. None when omitted. */
    public List<String> payloadHeaders;

    public ApplicationSpec application = new ApplicationSpec();
    public ConnectorSpec connector = new ConnectorSpec();
    public RetrySpec retry;
    /** Output shaping for {@code kind: source} batches (item / page / chunk). */
    public OutputSpec output = new OutputSpec();
    /**
     * How the application authorizes. The <b>developer decides at registration time</b>
     * (in the descriptor) whether the app runs the OAuth2 redirect flow or takes
     * manually-entered credentials — there is no end-user toggle in the UI. When
     * {@code type: oauth2} the worker exposes the authorize/token flow (redirect,
     * code exchange, refresh); otherwise it stays {@code basic} (flat credential fields).
     */
    public AuthorizationSpec authorization;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApplicationSpec {
        public String name;
        public String publicName;
        public String description = "";
        /**
         * Legacy authorization type on the application spec. Prefer
         * {@link AuthorizationSpec#type} ({@code ComponentOptions.oauth2(...)}).
         */
        public String authorizationType;

        public List<String> authForm = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectorSpec {
        public String name;
    }

    /**
     * OAuth2 wiring for descriptors whose {@code type} is {@code oauth2}. The endpoints
     * and scopes are provider-specific (e.g. Google) and are declared here so a
     * {@link DescriptorOAuth2Application} can run the redirect/token flow generically.
     *
     * <pre>
     * authorization:
     *   type: oauth2                 # basic | oauth2 (default basic)
     *   allowManualTokens: false     # oauth2 apps may also accept pasted tokens
     *   authUrl:  https://accounts.google.com/o/oauth2/v2/auth
     *   tokenUrl: https://oauth2.googleapis.com/token
     *   scopes:   [ "https://www.googleapis.com/auth/spreadsheets.readonly" ]
     *   scopeSeparator: space        # space | comma (default space)
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorizationSpec {
        /** {@code basic} or {@code oauth2}. */
        public String type;
        /**
         * When {@code true} an {@code oauth2} app additionally renders optional
         * {@code accessToken}/{@code refreshToken} fields so a developer who already has
         * tokens (and is not the one performing the authorization) can paste them instead
         * of running the redirect flow. The flow-issued token still takes precedence.
         */
        public boolean allowManualTokens = false;

        public String authUrl;
        public String tokenUrl;
        public List<String> scopes = new ArrayList<>();
        /** {@code space} (default, e.g. Google) or {@code comma}. */
        public String scopeSeparator = "space";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RetrySpec {
        public List<Object> success;
        public List<Object> repeat;
        public List<Object> stopAndFail;
        public Integer intervalSeconds;
        public Integer maxHops;
        public Boolean repeatOnException;
    }

    /**
     * How a {@code kind: source} batch turns polled items into downstream messages:
     * {@code mode} = {@code item} (one message per item, default), {@code page} (one
     * message with the whole {@code items[]}), or {@code chunk} (one message per
     * {@code chunkSize} slice). {@code batchSize}/{@code pollTimeoutMs} tune the poll.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputSpec {
        public String mode;
        public Integer chunkSize;
        public Integer batchSize;
        public Long pollTimeoutMs;
    }

    /** Whether this descriptor is a polling source ({@code kind: source}). */
    public boolean isSource() {
        return "source".equalsIgnoreCase(resolveKind());
    }

    /**
     * Effective kind: an explicit {@code kind} wins; otherwise {@code connector}
     * (catalog inference happens in {@link #applyCatalogDefaults()}).
     */
    public String resolveKind() {
        return kind == null || kind.isBlank() ? "connector" : kind.trim();
    }

    /**
     * Application key. Explicit {@code application.name} wins; otherwise
     * {@code camel-<component>} / {@code camel-<kamelet>}.
     */
    public String resolveApplicationName() {
        if (application != null && application.name != null && !application.name.isBlank()) {
            return application.name.trim();
        }
        String key = component != null && !component.isBlank() ? component : kamelet;
        return key == null || key.isBlank() ? "camel" : "camel-" + key.trim();
    }

    /**
     * Connector/batch name. Explicit {@code connector.name} wins; otherwise the
     * application key, with {@code -source} appended for polling sources.
     */
    public String resolveConnectorName() {
        if (connector != null && connector.name != null && !connector.name.isBlank()) {
            return connector.name.trim();
        }
        String app = resolveApplicationName();
        return isSource() && !app.endsWith("-source") ? app + "-source" : app;
    }

    /**
     * Fills identity, kind, form and connector name from the Camel Catalog or the Kamelet
     * definition where the descriptor left them blank. Safe to call more than once.
     *
     * @return this descriptor
     */
    public CamelDescriptor applyCatalogDefaults() {
        return applyCatalogDefaults(new CatalogFormGenerator());
    }

    public CamelDescriptor applyCatalogDefaults(CatalogFormGenerator generator) {
        // YAML can set the sections to null explicitly.
        if (application == null) {
            application = new ApplicationSpec();
        }
        if (connector == null) {
            connector = new ConnectorSpec();
        }
        if (output == null) {
            output = new OutputSpec();
        }
        if (component != null && !component.isBlank()) {
            CatalogComponentInfo info = generator.inspect(component);
            if (kind == null || kind.isBlank()) {
                kind = info.consumerOnly() ? "source" : "connector";
            }
            if (application.publicName == null || application.publicName.isBlank()) {
                application.publicName = info.title() == null || info.title().isBlank()
                        ? resolveApplicationName()
                        : "Camel " + info.title();
            }
            if (application.description == null || application.description.isBlank()) {
                application.description = info.description() == null ? "" : info.description();
            }
            if (application.authForm == null || application.authForm.isEmpty()) {
                application.authForm = new java.util.ArrayList<>(info.authOptions());
            }
        } else if (kamelet != null && !kamelet.isBlank()) {
            KameletDefinition def = KameletDefinitionLoader.loadByName(kamelet);
            if (application.publicName == null || application.publicName.isBlank()) {
                application.publicName = def.getTitle();
            }
            if (application.description == null || application.description.isBlank()) {
                application.description = def.getDescription() == null ? "" : def.getDescription();
            }
            if (application.authForm == null || application.authForm.isEmpty()) {
                application.authForm = new java.util.ArrayList<>(def.discoverAuthOptions());
            }
        }
        if (application.name == null || application.name.isBlank()) {
            application.name = resolveApplicationName();
        }
        if (connector.name == null || connector.name.isBlank()) {
            connector.name = resolveConnectorName();
        }
        return this;
    }

    /**
     * Effective Orchesty authorization type, decided at registration time: {@code authorization.type}
     * if set, otherwise the legacy {@code application.authorizationType}, otherwise {@code basic}.
     */
    public String resolveAuthorizationType() {
        if (authorization != null && authorization.type != null && !authorization.type.isBlank()) {
            return authorization.type.trim().toLowerCase(Locale.ROOT);
        }
        if (application != null && application.authorizationType != null && !application.authorizationType.isBlank()) {
            return application.authorizationType.trim().toLowerCase(Locale.ROOT);
        }
        return AuthorizationType.BASIC;
    }

    /** Whether this descriptor should run the OAuth2 redirect/token flow. */
    public boolean isOAuth2() {
        return AuthorizationType.OAUTH2.equals(resolveAuthorizationType());
    }

    /** Whether an OAuth2 app may also accept manually-entered tokens (fallback). */
    public boolean allowsManualTokens() {
        return authorization != null && authorization.allowManualTokens;
    }

    /**
     * Builds the retry policy from the descriptor; defaults fill whatever it leaves out.
     *
     * @return the retry policy
     */
    public RetryPolicy toRetryPolicy() {
        RetryPolicy.Builder b = RetryPolicy.builder();
        if (retry != null) {
            if (retry.success != null) {
                b.successRanges(retry.success);
            }
            if (retry.repeat != null) {
                b.repeatRanges(retry.repeat);
            }
            if (retry.stopAndFail != null) {
                b.stopAndFailRanges(retry.stopAndFail);
            }
            if (retry.intervalSeconds != null) {
                b.repeatIntervalSeconds(retry.intervalSeconds);
            }
            if (retry.maxHops != null) {
                b.repeatMaxHops(retry.maxHops);
            }
            if (retry.repeatOnException != null) {
                b.repeatOnException(retry.repeatOnException);
            }
        }
        return b.build();
    }
}
