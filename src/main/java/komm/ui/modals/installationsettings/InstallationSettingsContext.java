package komm.ui.modals.installationsettings;

import komm.model.dto.summary.InstallationSummary;

public final class InstallationSettingsContext {

    private final InstallationSummary installation;

    public InstallationSettingsContext(InstallationSummary installation) {
        this.installation = installation;
    }

    public InstallationSummary installation() {
        return installation;
    }
}
