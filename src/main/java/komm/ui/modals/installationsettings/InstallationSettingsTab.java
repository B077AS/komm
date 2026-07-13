package komm.ui.modals.installationsettings;

import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;

public interface InstallationSettingsTab {

    String name();

    /** Short blurb shown under the tab name in the modal header. */
    default String description() { return ""; }

    FontIcon icon();

    Node getPane();

    default void onShown() {}

    default boolean participatesInSave() { return false; }

    default boolean isDirty() { return false; }

    default boolean isBusy() { return false; }

    default String saveButtonText() { return "Save Changes"; }

    default void save() {}
}
