package io.orchesty.sdk.camel.metadata;

import io.orchesty.sdk.application.ABasicApplication;
import io.orchesty.sdk.application.form.FormStack;

/**
 * An Orchesty application whose configuration form is generated entirely from a
 * {@link CamelDescriptor} + the Camel Catalog. Renders in the Orchesty UI exactly
 * like a hand-written application, but its fields are derived from the component's
 * connection options.
 */
public class DescriptorApplication extends ABasicApplication {

    private final CamelDescriptor descriptor;
    private final CatalogFormGenerator generator;

    public DescriptorApplication(CamelDescriptor descriptor) {
        this(descriptor, new CatalogFormGenerator());
    }

    public DescriptorApplication(CamelDescriptor descriptor, CatalogFormGenerator generator) {
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
    public String getAuthorizationType() {
        return descriptor.resolveAuthorizationType();
    }

    @Override
    public FormStack getFormStack() {
        if (descriptor.kamelet != null && !descriptor.kamelet.isEmpty()) {
            KameletDefinition def = KameletDefinitionLoader.loadByName(descriptor.kamelet);
            return new FormStack()
                    .addForm(
                            new KameletFormGenerator().generateAuthorizationForm(def, descriptor.application.authForm));
        }
        return new FormStack()
                .addForm(generator.generateAuthorizationForm(descriptor.component, descriptor.application.authForm));
    }
}
