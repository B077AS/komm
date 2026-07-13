package komm.ui.modals.usersettings;

import komm.model.dto.summary.MainUserSummary;
import komm.ui.avatar.AvatarPreviewWidget;

import java.util.function.Consumer;

/**
 * Shared state and shell callbacks handed to every {@link UserSettingsTab}.
 * Owned by the {@code EditUserModal} shell; tabs read from it and call back into the shell through it.
 */
public final class UserSettingsContext {

    private final MainUserSummary user;
    private final AvatarPreviewWidget avatarWidget;

    private Runnable saveButtonRefresher;
    private Consumer<Boolean> savingStateHandler;

    public UserSettingsContext(MainUserSummary user, AvatarPreviewWidget avatarWidget) {
        this.user = user;
        this.avatarWidget = avatarWidget;
    }

    public MainUserSummary user() {
        return user;
    }

    public AvatarPreviewWidget avatarWidget() {
        return avatarWidget;
    }

    public void setSaveButtonRefresher(Runnable r) {
        this.saveButtonRefresher = r;
    }

    public void setSavingStateHandler(Consumer<Boolean> h) {
        this.savingStateHandler = h;
    }

    /**
     * Ask the shell to re-evaluate the footer Save button (text + enabled state).
     */
    public void refreshSaveButton() {
        if (saveButtonRefresher != null) saveButtonRefresher.run();
    }

    /**
     * Toggle the modal's saving state (disables cancel/close, shows progress).
     */
    public void setSaving(boolean saving) {
        if (savingStateHandler != null) savingStateHandler.accept(saving);
    }
}
