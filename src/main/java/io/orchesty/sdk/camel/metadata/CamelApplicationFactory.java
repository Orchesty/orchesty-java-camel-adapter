package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.AApplication;

/**
 * Builds the right descriptor-driven application for a {@link CamelDescriptor}, choosing
 * between BASIC and OAuth2 <b>at registration time</b> based on {@code authorization.type}:
 * <ul>
 *   <li>{@code oauth2}: a {@link DescriptorOAuth2Application} (redirect/token flow, with an
 *       optional manual-token fallback);</li>
 *   <li>{@code basic} (default): a {@link DescriptorApplication} (flat credential fields).</li>
 * </ul>
 * OAuth2 apps are created without a provider; the {@link io.orchesty.sdk.WorkerServer}
 * injects the shared {@link io.orchesty.sdk.authorization.OAuth2Provider} once it exists.
 */
public final class CamelApplicationFactory {

    private CamelApplicationFactory() {}

    public static AApplication create(CamelDescriptor descriptor) {
        return descriptor.isOAuth2()
                ? new DescriptorOAuth2Application(descriptor)
                : new DescriptorApplication(descriptor);
    }
}
