package komm.ui.modals.serversettings;

import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.PermissionManager;
import komm.ui.avatar.AvatarPreviewWidget;

import java.util.function.Consumer;

/**
 * Shared state and shell callbacks handed to every {@link ServerSettingsTab}. Owned by the
 * {@code EditServerModal} shell; tabs read from it and call back into the shell through it.
 */
public final class ServerSettingsContext {

    private final ServerSummary serverDetails;
    private final boolean allowEdit;
    private final boolean canViewPermsTab;
    private final AvatarPreviewWidget avatarWidget;
    private final PermissionManager localPermManager;

    private Runnable saveButtonRefresher;
    private Consumer<Boolean> savingStateHandler;
    private Runnable permissionsReloader;

    public ServerSettingsContext(ServerSummary serverDetails, boolean allowEdit, boolean canViewPermsTab,
                                 AvatarPreviewWidget avatarWidget, PermissionManager localPermManager) {
        this.serverDetails = serverDetails;
        this.allowEdit = allowEdit;
        this.canViewPermsTab = canViewPermsTab;
        this.avatarWidget = avatarWidget;
        this.localPermManager = localPermManager;
    }

    // ── Shared state ────────────────────────────────────────────────────────────
    public ServerSummary serverDetails()       { return serverDetails; }
    public boolean allowEdit()                  { return allowEdit; }
    public boolean canViewPermsTab()            { return canViewPermsTab; }
    public AvatarPreviewWidget avatarWidget()   { return avatarWidget; }
    public PermissionManager localPermManager() { return localPermManager; }

    // ── Shell callbacks (wired by the shell) ────────────────────────────────────
    public void setSaveButtonRefresher(Runnable r)        { this.saveButtonRefresher = r; }
    public void setSavingStateHandler(Consumer<Boolean> h) { this.savingStateHandler = h; }
    public void setPermissionsReloader(Runnable r)        { this.permissionsReloader = r; }

    /** Ask the shell to re-evaluate the footer Save button (text + enabled state). */
    public void refreshSaveButton() { if (saveButtonRefresher != null) saveButtonRefresher.run(); }

    /** Toggle the modal's "saving" state (disables cancel/close, marks the modal persistent). */
    public void setSaving(boolean saving) { if (savingStateHandler != null) savingStateHandler.accept(saving); }

    /** Ask the shell to reload server permissions from the hub. */
    public void reloadPermissions() { if (permissionsReloader != null) permissionsReloader.run(); }
}
