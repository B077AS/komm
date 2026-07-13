package komm.ui.modals.serversettings;

import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * One section/tab of the {@code EditServerModal}. Each tab owns its own pane and logic.
 * Tabs that take part in the shared "Save" button override {@link #participatesInSave()} and the
 * save-related defaults; purely action-based tabs (e.g. Bans, Invites) leave them at their defaults.
 */
public interface ServerSettingsTab {

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

    /** True while a background save/load is running, used to disable the Save button. */
    default boolean isBusy() { return false; }

    default String saveButtonText() { return "Save Changes"; }

    default void save() {}
}
