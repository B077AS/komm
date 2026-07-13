package komm.ui.modals.serversettings;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.ServerSummary;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import static komm.ui.modals.serversettings.ServerSettingsUi.sectionLabel;
import static komm.ui.modals.serversettings.ServerSettingsUi.wrapScroll;

/**
 * "Danger Zone" tab: permanently delete the server. Only added to the modal when the caller holds
 * {@code DELETE_SERVER}. Deletion is confirmed via a {@link ConfirmationModal}; the installation then
 * disconnects everyone and purges the data in the background.
 */
@Slf4j
public class DangerZoneTab implements ServerSettingsTab {

    private final ServerSummary serverDetails;
    private final ScrollPane pane;

    private final Service<Void> deleteService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getServerService().deleteServer(serverDetails.getServerId());
                    return null;
                }
            };
        }
    };

    public DangerZoneTab(ServerSettingsContext ctx) {
        this.serverDetails = ctx.serverDetails();
        this.pane = buildPane();

        deleteService.setOnSucceeded(e -> {
            App.closeModal();
            String name = serverDetails.getServerName() != null ? serverDetails.getServerName() : "Server";
            new CustomNotification("Server Deleted",
                    "\"" + name + "\" has been permanently deleted.",
                    new FontIcon(MaterialDesignD.DELETE)).showNotification();
        });
        deleteService.setOnFailed(e -> {
            Throwable ex = deleteService.getException();
            log.error("Failed to delete server", ex);
            new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
    }

    @Override public String name() { return "Danger Zone"; }
    @Override public String description() { return "Irreversible actions — proceed with caution"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignA.ALERT_OCTAGON); }
    @Override public Node getPane() { return pane; }

    private ScrollPane buildPane() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20, 28, 20, 28));
        content.setAlignment(Pos.TOP_LEFT);

        Label header = sectionLabel("Delete Server");

        Label warning = new Label(
                "Permanently delete this server and everything in it — channels, messages, "
                        + "attachments, roles, permissions and soundboards. All members are disconnected "
                        + "immediately. This cannot be undone.");
        warning.setWrapText(true);
        warning.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        Button deleteButton = new Button("Delete Server", new FontIcon(MaterialDesignD.DELETE_FOREVER));
        deleteButton.getStyleClass().addAll(Styles.DANGER);
        deleteButton.setFocusTraversable(false);
        deleteButton.setOnAction(e -> confirmDelete());

        VBox card = new VBox(12, warning, deleteButton);
        card.setPadding(new Insets(14));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-background-color: -color-bg-subtle;"
                + " -fx-border-color: -color-danger-emphasis;"
                + " -fx-border-radius: 6; -fx-background-radius: 6;");

        content.getChildren().addAll(header, card);
        return wrapScroll(content);
    }

    private void confirmDelete() {
        if (deleteService.isRunning()) return;
        String name = serverDetails.getServerName() != null ? serverDetails.getServerName() : "this server";
        App.showModal(new ConfirmationModal(
                "Delete Server",
                "Permanently delete \"" + name + "\" and all of its data? This cannot be undone.",
                new FontIcon(MaterialDesignD.DELETE_FOREVER),
                () -> deleteService.restart()));
    }
}
