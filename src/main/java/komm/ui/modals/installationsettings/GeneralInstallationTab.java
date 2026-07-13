package komm.ui.modals.installationsettings;

import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationDetailSummary;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import static komm.ui.modals.installationsettings.InstallationSettingsUi.*;

public class GeneralInstallationTab implements InstallationSettingsTab {

    private final InstallationSettingsContext ctx;

    private final TextField nameField = readOnlyField();
    private final TextField ipField = readOnlyField();
    private final TextField apiPortField = readOnlyField();
    private final TextField signalPortField = readOnlyField();
    private final TextField tcpPortField = readOnlyField();
    private final TextField mediaPortField = readOnlyField();
    private final HBox statusPlaceholder = new HBox();

    private boolean loaded = false;
    private final ScrollPane pane;

    private final Service<InstallationDetailSummary> loadService = new Service<>() {
        @Override
        protected Task<InstallationDetailSummary> createTask() {
            return new Task<>() {
                @Override
                protected InstallationDetailSummary call() throws Exception {
                    return App.getServices().hub().getInstallationService()
                            .getInstallationDetails(ctx.installation().getInstallationId());
                }
            };
        }
    };

    public GeneralInstallationTab(InstallationSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();
    }

    @Override
    public String name() {
        return "General";
    }

    @Override
    public String description() {
        return "Manage this installation's basic info";
    }

    @Override
    public FontIcon icon() {
        return new FontIcon(MaterialDesignE.ETHERNET);
    }

    @Override
    public Node getPane() {
        return pane;
    }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;

        loadService.setOnSucceeded(e -> populate(loadService.getValue()));
        loadService.setOnFailed(e -> {
            Throwable ex = loadService.getException();
            new CustomNotification("Load Error", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignL.LAN_DISCONNECT)).showNotification();
        });
        loadService.start();
    }

    // ── Build pane ────────────────────────────────────────────────────────────

    private ScrollPane buildPane() {
        nameField.setPromptText("Loading…");
        ipField.setPromptText("Loading…");
        apiPortField.setPromptText("—");
        signalPortField.setPromptText("—");
        tcpPortField.setPromptText("—");
        mediaPortField.setPromptText("—");

        VBox content = new VBox(20);
        content.setPadding(new Insets(20, 28, 20, 28));
        content.setAlignment(Pos.TOP_LEFT);

        // ── Overview ──────────────────────────────────────────────────────────
        Region nameSpacer = new Region();
        HBox.setHgrow(nameSpacer, Priority.ALWAYS);
        HBox nameLabelRow = new HBox(nameSpacer, statusPlaceholder);
        nameLabelRow.setAlignment(Pos.CENTER_LEFT);
        nameLabelRow.getChildren().add(0, sectionLabel("Installation Name"));

        VBox nameRow = new VBox(6, nameLabelRow, nameField);

        VBox ipRow = new VBox(6, sectionLabel("IP Address"), ipField);

        // ── Ports ─────────────────────────────────────────────────────────────
        HBox portsRow = new HBox(10);
        portsRow.getChildren().addAll(
                buildPortBox("API Port", "TCP", apiPortField),
                buildPortBox("Signaling Port", "TCP", signalPortField),
                buildPortBox("TCP Port", "TCP", tcpPortField),
                buildPortBox("Media Port", "UDP", mediaPortField)
        );

        VBox portsSection = new VBox(8, sectionLabel("Ports"), portsRow);

        content.getChildren().addAll(nameRow, ipRow, portsSection);
        return wrapScroll(content);
    }

    private VBox buildPortBox(String portName, String protocol, TextField field) {
        VBox box = new VBox(5);
        HBox.setHgrow(box, Priority.ALWAYS);
        Label nameLabel = new Label(portName);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        HBox header = new HBox(6, nameLabel, buildProtocolBadge(protocol));
        header.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(header, field);
        return box;
    }

    // ── Populate ──────────────────────────────────────────────────────────────

    private void populate(InstallationDetailSummary d) {
        nameField.setText(d.getInstallationName() != null ? d.getInstallationName() : "—");
        ipField.setText(d.getIpAddress() != null && !d.getIpAddress().isBlank() ? d.getIpAddress() : "—");
        apiPortField.setText(d.getInstallationPort() != 0 ? String.valueOf(d.getInstallationPort()) : "—");
        signalPortField.setText(d.getSignalPort() != 0 ? String.valueOf(d.getSignalPort()) : "—");
        tcpPortField.setText(d.getTcpPort() != 0 ? String.valueOf(d.getTcpPort()) : "—");
        mediaPortField.setText(d.getMediaPort() != 0 ? String.valueOf(d.getMediaPort()) : "—");
        statusPlaceholder.getChildren().setAll(buildStatusBadge(d.getStatus()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static TextField readOnlyField() {
        TextField f = new TextField();
        f.setEditable(false);
        f.setFocusTraversable(false);
        f.setMaxWidth(Double.MAX_VALUE);
        f.setStyle("-fx-opacity: 0.75;");
        return f;
    }
}
