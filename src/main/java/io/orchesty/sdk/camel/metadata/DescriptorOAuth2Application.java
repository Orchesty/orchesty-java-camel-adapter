package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.application.form.Field;
import io.orchesty.sdk.application.form.FieldType;
import io.orchesty.sdk.application.form.Form;
import io.orchesty.sdk.application.form.FormStack;
import io.orchesty.sdk.authorization.AOAuth2Application;
import io.orchesty.sdk.authorization.IToken;
import io.orchesty.sdk.authorization.OAuth2Provider;
import io.orchesty.sdk.camel.CamelCredentials;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuth2 (authorization-code) variant of {@link DescriptorApplication}. Selected at
 * <b>registration time</b> when the descriptor declares {@code authorization.type: oauth2}
 * (see {@link CamelApplicationFactory}). It renders in the Orchesty UI like any OAuth2
 * app: the config fields come from the Camel Catalog / Kamelet, the OAuth
 * {@code client_id}/{@code client_secret} fields and the read-only redirect URL are added
 * by this class / {@link AOAuth2Application}, and the platform drives the authorize/token
 * redirect flow via {@link OAuth2Provider}.
 *
 * <h2>Manual-token fallback</h2>
 * When {@code authorization.allowManualTokens: true}, optional {@code accessToken}/
 * {@code refreshToken} fields are also rendered so a developer who already holds tokens
 * (and is not the one performing the authorization) can paste them instead of running the
 * flow. A flow-issued token (stored under {@code authorization_form.token}) always wins.
 *
 * <h2>Credentials for Camel</h2>
 * The token is <b>not</b> attached here as an HTTP header; the Camel endpoint consumes the
 * credentials directly. {@link io.orchesty.sdk.camel.CamelCredentials} turns the stored form
 * (flow token or manual fields; OAuth {@code client_id} becomes Camel's {@code clientId})
 * into Camel endpoint options.
 */
public class DescriptorOAuth2Application extends AOAuth2Application {

    /** Optional manual-token form field keys (Camel option names, read by CamelCredentials). */
    public static final String ACCESS_TOKEN = "accessToken";

    public static final String REFRESH_TOKEN = "refreshToken";

    /** Camel option names this class renders as OAuth fields instead of Catalog fields. */
    private static final Set<String> OAUTH_MANAGED_OPTIONS =
            Set.of(CamelCredentials.CLIENT_ID_CAMEL, CamelCredentials.CLIENT_SECRET_CAMEL, ACCESS_TOKEN, REFRESH_TOKEN);

    private final CamelDescriptor descriptor;
    private final CatalogFormGenerator generator;

    public DescriptorOAuth2Application(CamelDescriptor descriptor) {
        this(descriptor, null, new CatalogFormGenerator());
    }

    public DescriptorOAuth2Application(CamelDescriptor descriptor, OAuth2Provider provider) {
        this(descriptor, provider, new CatalogFormGenerator());
    }

    public DescriptorOAuth2Application(
            CamelDescriptor descriptor, OAuth2Provider provider, CatalogFormGenerator generator) {
        super(provider);
        this.descriptor = descriptor.applyCatalogDefaults(generator);
        this.generator = generator;
    }

    public CamelDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public String getName() {
        return descriptor.resolveApplicationName();
    }

    @Override
    public String getPublicName() {
        return descriptor.application.publicName != null && !descriptor.application.publicName.isBlank()
                ? descriptor.application.publicName
                : descriptor.resolveApplicationName();
    }

    @Override
    public String getDescription() {
        return descriptor.application.description == null ? "" : descriptor.application.description;
    }

    @Override
    public String getAuthUrl() {
        return descriptor.authorization == null ? null : descriptor.authorization.authUrl;
    }

    @Override
    public String getTokenUrl() {
        return descriptor.authorization == null ? null : descriptor.authorization.tokenUrl;
    }

    @Override
    public List<String> getScopes(ApplicationInstall applicationInstall) {
        return descriptor.authorization == null ? List.of() : descriptor.authorization.scopes;
    }

    @Override
    protected String getScopesSeparator() {
        String sep = descriptor.authorization == null ? null : descriptor.authorization.scopeSeparator;
        return "comma".equalsIgnoreCase(sep)
                ? OAuth2Provider.SCOPE_SEPARATOR_COMMA
                : OAuth2Provider.SCOPE_SEPARATOR_SPACE;
    }

    @Override
    public FormStack getFormStack() {
        Form form = buildConfigForm();
        // OAuth credentials the token exchange/refresh needs (OAuth-standard keys).
        form.addField(new Field(FieldType.TEXT, CLIENT_ID, "Client ID", null, true));
        form.addField(new Field(FieldType.TEXT, CLIENT_SECRET, "Client Secret", null, true));
        if (descriptor.allowsManualTokens()) {
            // Optional: let a developer paste tokens instead of running the redirect flow.
            form.addField(new Field(FieldType.TEXT, ACCESS_TOKEN, "Access token (optional, skips the redirect flow)"));
            form.addField(new Field(FieldType.TEXT, REFRESH_TOKEN, "Refresh token (optional)"));
        }
        return new FormStack().addForm(form);
    }

    /**
     * Prefer the flow-issued token; fall back to manually-entered tokens when allowed so
     * {@link #isAuthorized(ApplicationInstall)}, {@link #getAccessToken(ApplicationInstall)} and
     * the HTTP helpers keep working without a flow.
     */
    @Override
    public IToken getTokens(ApplicationInstall applicationInstall) {
        IToken token = super.getTokens(applicationInstall);
        if (token != null
                && token.getAccessToken() != null
                && !token.getAccessToken().isEmpty()) {
            return token;
        }
        if (!descriptor.allowsManualTokens()) {
            return token;
        }
        Map<String, Object> authForm = asMap(applicationInstall.getSettings().get(CoreForm.AUTHORIZATION_FORM));
        if (authForm == null || !notBlank(authForm.get(ACCESS_TOKEN))) {
            return token;
        }
        return new IToken()
                .setAccessToken(String.valueOf(authForm.get(ACCESS_TOKEN)))
                .setRefreshToken(
                        notBlank(authForm.get(REFRESH_TOKEN)) ? String.valueOf(authForm.get(REFRESH_TOKEN)) : null);
    }

    /**
     * Builds the non-credential configuration form from the Camel Catalog or the Kamelet. The
     * component's own client and token options are left out: this class renders the OAuth
     * fields itself, and {@link io.orchesty.sdk.camel.CamelCredentials} maps them onto the Camel
     * option names.
     */
    private Form buildConfigForm() {
        Form form;
        if (descriptor.kamelet != null && !descriptor.kamelet.isEmpty()) {
            KameletDefinition def = KameletDefinitionLoader.loadByName(descriptor.kamelet);
            form = new KameletFormGenerator().generateAuthorizationForm(def, descriptor.application.authForm);
        } else {
            form = generator.generateAuthorizationForm(descriptor.component, descriptor.application.authForm);
        }
        form.getFields().removeIf(field -> OAUTH_MANAGED_OPTIONS.contains(field.getKey()));
        return form;
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }
}
