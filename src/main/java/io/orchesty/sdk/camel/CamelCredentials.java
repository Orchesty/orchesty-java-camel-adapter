package io.orchesty.sdk.camel;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.authorization.OAuth2StateNonce;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes an application's {@code authorization_form} into <b>Camel endpoint options</b>,
 * regardless of how the credentials were obtained. This lets a Camel source/connector treat
 * BASIC (manually-entered) and OAuth2 (redirect-flow) applications uniformly:
 *
 * <ul>
 *   <li>flat config/credential fields (e.g. {@code applicationName}, {@code spreadsheetId},
 *       {@code range}, and — in BASIC mode — {@code clientId}/{@code clientSecret}/
 *       {@code accessToken}/{@code refreshToken}) are passed through as-is;</li>
 *   <li>the OAuth-standard {@code client_id}/{@code client_secret} keys (used by OAuth2 apps)
 *       are mapped to Camel's {@code clientId}/{@code clientSecret};</li>
 *   <li>the flow-issued token stored under {@code authorization_form.token} (an
 *       {@link io.orchesty.sdk.authorization.IToken} map) is exposed as {@code accessToken}/
 *       {@code refreshToken}, taking precedence over any manually-entered token fields;</li>
 *   <li>UI-only and flow-internal keys ({@code redirect_url}, {@code frontend_redirect_url},
 *       {@code oauth_state_nonce}, and {@code token} when it is the flow's token map) are dropped so
 *       they never leak into the endpoint URI; a text {@code token} is an ordinary option.</li>
 * </ul>
 *
 * <p>Every value is checked with {@link UntrustedValues}: a tenant's install must not make Camel
 * resolve placeholders, references or expressions in the shared worker.
 */
public final class CamelCredentials {

    /** Nested map (IToken) written by the OAuth2 redirect flow. */
    public static final String TOKEN = "token";

    public static final String REDIRECT_URL = "redirect_url";
    public static final String FRONTEND_REDIRECT_URL = "frontend_redirect_url";

    public static final String CLIENT_ID_OAUTH = "client_id";
    public static final String CLIENT_SECRET_OAUTH = "client_secret";
    public static final String CLIENT_ID_CAMEL = "clientId";
    public static final String CLIENT_SECRET_CAMEL = "clientSecret";
    public static final String ACCESS_TOKEN = "accessToken";
    public static final String REFRESH_TOKEN = "refreshToken";

    private CamelCredentials() {}

    /**
     * Resolves the effective Camel endpoint options from the install's authorization form.
     *
     * @param install application install, or {@code null}
     * @return the endpoint options; empty when there is no install or form
     * @throws IllegalArgumentException when a value contains a placeholder, an expression, a Camel
     *     reference or a raw marker (see {@link UntrustedValues})
     */
    public static Map<String, Object> resolve(ApplicationInstall install) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (install == null) {
            return out;
        }
        Object formObj = install.getSettings().get(CoreForm.AUTHORIZATION_FORM);
        if (!(formObj instanceof Map<?, ?> form)) {
            return out;
        }

        // 1) Copy flat, non-secret-nested fields (skip UI-only + the OAuth-standard client keys).
        form.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (v == null || String.valueOf(v).isEmpty()) {
                return;
            }
            if (TOKEN.equals(key) && v instanceof Map) {
                return; // the flow-issued IToken, handled below
            }
            switch (key) {
                case REDIRECT_URL,
                        FRONTEND_REDIRECT_URL,
                        OAuth2StateNonce.KEY,
                        CLIENT_ID_OAUTH,
                        CLIENT_SECRET_OAUTH -> {
                    // handled below / dropped
                }
                default -> out.put(key, v);
            }
        });

        // 2) Map OAuth-standard client keys onto Camel option names (don't overwrite BASIC keys).
        Object clientId = ((Map<?, ?>) form).get(CLIENT_ID_OAUTH);
        if (notBlank(clientId)) {
            out.putIfAbsent(CLIENT_ID_CAMEL, clientId);
        }
        Object clientSecret = ((Map<?, ?>) form).get(CLIENT_SECRET_OAUTH);
        if (notBlank(clientSecret)) {
            out.putIfAbsent(CLIENT_SECRET_CAMEL, clientSecret);
        }

        // 3) A flow-issued token (IToken map) wins over any manually-entered token fields.
        Object token = ((Map<?, ?>) form).get(TOKEN);
        if (token instanceof Map<?, ?> t) {
            if (notBlank(t.get(ACCESS_TOKEN))) {
                out.put(ACCESS_TOKEN, t.get(ACCESS_TOKEN));
            }
            if (notBlank(t.get(REFRESH_TOKEN))) {
                out.put(REFRESH_TOKEN, t.get(REFRESH_TOKEN));
            }
        }

        UntrustedValues.checkAll(out, "application install");
        return out;
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }
}
