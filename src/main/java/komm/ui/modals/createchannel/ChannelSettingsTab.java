package komm.ui.modals.createchannel;

import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * One section/tab of the {@code CreateChannelModal}. Each tab owns its own pane and logic.
 * Tabs that take part in the shared "Save" button (edit mode only — create mode has a single
 * "Create Channel" action instead) override {@link #participatesInSave()} and the save-related
 * defaults.
 */
public interface ChannelSettingsTab {

    String name();

    /** Short blurb shown under the tab name in the modal header. */
    default String description() { return ""; }

    FontIcon icon();

    Node getPane();

    /** Called every time the tab becomes active. Use for lazy loading; must be idempotent. */
    default void onShown() {}

    /** Whether the shared footer Save button applies to this tab (edit mode only). */
    default boolean participatesInSave() { return false; }

    default boolean isDirty() { return false; }

    /** True while a background save/load is running, used to disable the Save button. */
    default boolean isBusy() { return false; }

    default String saveButtonText() { return "Save Changes"; }

    default void save() {}
}
