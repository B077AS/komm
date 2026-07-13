package komm.ui.modals.usersettings;

import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * One section/tab of the {@code EditUserModal}. Each tab owns its own pane and logic.
 * Tabs that participate in the shared Save button override {@link #participatesInSave()} and
 * the related defaults; info-only tabs leave them at their defaults.
 */
public interface UserSettingsTab {

    String name();

    /** Short blurb shown under the tab name in the modal header. */
    default String description() { return ""; }

    FontIcon icon();

    Node getPane();

    /** Called every time the tab becomes active. Use for lazy loading; must be idempotent. */
    default void onShown() {}

    /** Whether the shared footer Save button applies to this tab. */
    default boolean participatesInSave() { return false; }

    default boolean isDirty() { return false; }

    /** True while a background save is running, used to disable the Save button. */
    default boolean isBusy() { return false; }

    default String saveButtonText() { return "Save " + name(); }

    default void save() {}

    /** Called when the modal is dismissed. Used for cleanup (e.g. restoring live-preview changes). */
    default void dispose() {}
}
