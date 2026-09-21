package io.orchesty.sdk.camel;

import io.orchesty.sdk.application.ApplicationInstall;
import io.orchesty.sdk.application.ApplicationInstallRepository;
import java.util.List;

/** Loads the application install a Camel node runs with; shared by connectors and batches. */
final class InstallLoader {

    private InstallLoader() {}

    /**
     * Loads the enabled install of the user and SDK.
     *
     * @return the install, or {@code null} when the node has no application
     * @throws IllegalArgumentException when the node has an application but no repository is
     *     available (no database injected), the user or SDK is missing or the user has no enabled
     *     install
     */
    static ApplicationInstall load(
            String applicationName, ApplicationInstallRepository repository, String user, String sdk) {
        if (applicationName == null || applicationName.isEmpty()) {
            return null;
        }
        if (repository == null) {
            throw new IllegalArgumentException("Application [" + applicationName
                    + "] has no install repository; run the node in a WorkerServer or set one.");
        }
        if (user == null || user.isEmpty() || sdk == null || sdk.isEmpty()) {
            throw new IllegalArgumentException(
                    "Application [" + applicationName + "] needs the message's user and sdk headers.");
        }
        ApplicationInstall install = repository.findByNameAndUser(applicationName, user, List.of(sdk), true);
        if (install == null) {
            throw new IllegalArgumentException("Application [" + applicationName
                    + "] is not installed or enabled for user [" + user + "] and sdk [" + sdk + "].");
        }
        return install;
    }
}
