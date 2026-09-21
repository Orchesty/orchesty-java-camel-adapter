package io.orchesty.sdk.camel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.enums.CoreForm;
import io.orchesty.sdk.authorization.OAuth2StateNonce;
import io.orchesty.sdk.process.Json;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Identifies one tenant's credential set for {@link CamelRuntime#send(String, org.apache.camel.Exchange, String)}.
 * The key combines the application, user and SDK of the install with a fingerprint of its
 * {@code authorization_form}, so new credentials (e.g. a refreshed OAuth token) produce a new key
 * and therefore a new private Camel component. Other settings (for example the limiter form) and
 * the form's non-credential keys ({@code oauth_state_nonce}, {@code redirect_url},
 * {@code frontend_redirect_url}) don't change the key.
 */
public final class TenantKey {

    private static final Set<String> IGNORED_FORM_KEYS =
            Set.of(OAuth2StateNonce.KEY, CamelCredentials.REDIRECT_URL, CamelCredentials.FRONTEND_REDIRECT_URL);

    private TenantKey() {}

    /**
     * Returns the tenant key of an install.
     *
     * @param applicationName application the connector belongs to
     * @param install application install, or {@code null} when the connector uses none
     * @return the key, or {@code null} when there is no install (no tenant credentials)
     */
    public static String of(String applicationName, ApplicationInstall install) {
        if (install == null) {
            return null;
        }
        return applicationName + '|' + install.getUser() + '|' + install.getSdk() + '|' + fingerprint(install);
    }

    private static String fingerprint(ApplicationInstall install) {
        Map<String, Object> credentials = new TreeMap<>();
        if (install.getSettings().get(CoreForm.AUTHORIZATION_FORM) instanceof Map<?, ?> form) {
            form.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (!IGNORED_FORM_KEYS.contains(key)) {
                    credentials.put(key, v);
                }
            });
        }
        try {
            byte[] bytes = Json.MAPPER
                    .writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(credentials);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint application credentials: " + e.getMessage(), e);
        }
    }
}
