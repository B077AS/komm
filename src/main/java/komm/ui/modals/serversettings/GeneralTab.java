package komm.ui.modals.serversettings;

import atlantafx.base.theme.Styles;
import javafx.beans.binding.BooleanExpression;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.ServerUpdateRequest;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;
import javafx.geometry.Bounds;
import komm.ui.customnodes.CustomNotification;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.emojis.EmojiPickerPopup;
import komm.ui.emojis.EmojiTextArea;
import komm.utils.UserSettings;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.Base64;
import java.util.List;

import static komm.ui.modals.serversettings.ServerSettingsUi.sectionLabel;
import static komm.ui.modals.serversettings.ServerSettingsUi.wrapScroll;

/** "General" tab: server name, description, installation and default channel panel width. */
@Slf4j
public class GeneralTab implements ServerSettingsTab {

    private final ServerSettingsContext ctx;
    private final ServerSummary serverDetails;
    private final boolean allowEdit;

    private static final int MAX_DESC_LENGTH = 500;

    private final EmojiTextArea descField = new EmojiTextArea(false);
    private final EmojiPickerPopup descPicker = new EmojiPickerPopup();
    private final Label descCounter = new Label("0/" + MAX_DESC_LENGTH);
    private final EmojiFilterTextField serverNameField = EmojiFilterTextField.maxLength(100);
    private final ComboBox<InstallationSummary> installationComboBox = new ComboBox<>();
    private final TextField installationDisplayField = new TextField();

    private String origServerName;
    private String origDescription;
    private Integer pendingDefaultChannelPanelWidth;

    private final ScrollPane pane;
    private ServerUpdateRequest currentRequest;

    private final Service<List<InstallationSummary>> loadInstallationsService = new Service<>() {
        @Override
        protected Task<List<InstallationSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<InstallationSummary> call() throws Exception {
                    return App.getServices().hub().getInstallationService().getUserInstallations();
                }
            };
        }
    };

    private final Service<Void> saveServerService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getServerService().updateServer(currentRequest);
                    if (pendingDefaultChannelPanelWidth != null) {
                        try {
                            App.getServices().installation()
                                    .getInstallationPermissionService()
                                    .updateServerSettings(pendingDefaultChannelPanelWidth);
                        } catch (Exception e) {
                            log.warn("Failed to save default panel width: {}", e.getMessage());
                        }
                    }
                    return null;
                }
            };
        }
    };

    public GeneralTab(ServerSettingsContext ctx) {
        this.ctx = ctx;
        this.serverDetails = ctx.serverDetails();
        this.allowEdit = ctx.allowEdit();

        buildFields();
        this.pane = buildPane();

        saveServerService.runningProperty().addListener((obs, was, isRunning) -> {
            ctx.setSaving(isRunning);
            ctx.refreshSaveButton();
        });
        loadInstallationsService.runningProperty().addListener((obs, was, isRunning) -> ctx.refreshSaveButton());

        loadInstallations();
        populateExistingData();
    }

    // ── ServerSettingsTab ──────────────────────────────────────────────────────

    @Override public String name() { return "General"; }
    @Override public String description() { return "Manage your server's name, icon, and basic info"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignS.SERVER); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Changes"; }
    @Override public boolean isBusy() { return loadInstallationsService.isRunning() || saveServerService.isRunning(); }

    @Override
    public boolean isDirty() {
        String currentName = serverNameField.getText().trim();
        String currentDesc = descField.getText().trim();
        String storedName = origServerName != null ? origServerName.trim() : "";
        String storedDesc = origDescription != null ? origDescription.trim() : "";
        boolean widthChanged = pendingDefaultChannelPanelWidth != null
                && !pendingDefaultChannelPanelWidth.equals(serverDetails.getDefaultChannelPanelWidth());
        return !currentName.equals(storedName)
                || !currentDesc.equals(storedDesc)
                || ctx.avatarWidget().hasNewUpload()
                || widthChanged;
    }

    @Override
    public void save() {
        handleSaveServer();
    }

    /** Binding the shell uses to drive the footer progress indicator. */
    public BooleanExpression busyBinding() {
        return loadInstallationsService.runningProperty().or(saveServerService.runningProperty());
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private void buildFields() {
        serverNameField.setPromptText("e.g. My Gaming Server");
        serverNameField.setMaxWidth(Double.MAX_VALUE);

        installationComboBox.setMaxWidth(Double.MAX_VALUE);
        installationComboBox.setVisible(false);
        installationComboBox.setManaged(false);
        installationComboBox.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(InstallationSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getInstallationName());
            }
        });
        installationComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(InstallationSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getInstallationName());
            }
        });

        installationDisplayField.setEditable(false);
        installationDisplayField.setFocusTraversable(false);
        installationDisplayField.setMaxWidth(Double.MAX_VALUE);
        installationDisplayField.setStyle("-fx-opacity: 0.75;");
        installationDisplayField.setPromptText("Loading…");

        serverNameField.textProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());

        if (!allowEdit) {
            serverNameField.setEditable(false);
            serverNameField.setFocusTraversable(false);
            serverNameField.setStyle("-fx-opacity: 0.75;");
        }

        descField.getStyleClass().add("text-input");
        descField.setStyle("-fx-padding: 0;");
        descField.setPromptText("A short description of your server");
        descField.setMaxLength(MAX_DESC_LENGTH);
        descField.setMinLines(4);
        descField.setMaxLines(4);
        descCounter.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        descField.textProperty().addListener((obs, o, n) -> {
            int len = n.length();
            descCounter.setText(len + "/" + MAX_DESC_LENGTH);
            String color = len >= MAX_DESC_LENGTH ? "-color-danger-fg" : "-color-fg-muted";
            descCounter.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
            ctx.refreshSaveButton();
        });
        if (!allowEdit) {
            descField.setEditable(false);
            descField.setOpacity(0.75);
        }

        descPicker.setOnEmojiSelected(descField::insertEmoji);
    }

    private ScrollPane buildPane() {
        VBox pane = new VBox(20);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.setAlignment(Pos.TOP_LEFT);

        Label nameLabel = sectionLabel("Server Name");
        Label installLabel = sectionLabel("Installation");
        Label descLabel = sectionLabel("Description");

        Region descFiller = new Region();
        HBox.setHgrow(descFiller, Priority.ALWAYS);
        HBox descHeaderRow = new HBox(descLabel, descFiller, descCounter);
        descHeaderRow.setAlignment(Pos.CENTER_LEFT);

        VBox descSection;
        if (allowEdit) {
            Button emojiBtn = new Button(null, new FontIcon(MaterialDesignE.EMOTICON_OUTLINE));
            emojiBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
            emojiBtn.setFocusTraversable(false);
            emojiBtn.setTooltip(new Tooltip("Insert emoji"));
            emojiBtn.setOnAction(e -> {
                if (descPicker.isShowing()) {
                    descPicker.hide();
                    return;
                }
                Bounds b = emojiBtn.localToScreen(emojiBtn.getBoundsInLocal());
                if (b != null) {
                    double x = b.getMinX();
                    double y = b.getMinY() - 460 - 4;
                    if (y < 0) y = b.getMaxY() + 4;
                    descPicker.show(emojiBtn.getScene().getWindow(), x, y);
                }
            });
            Region footerFiller = new Region();
            HBox.setHgrow(footerFiller, Priority.ALWAYS);
            HBox descFooterRow = new HBox(footerFiller, emojiBtn);
            descSection = new VBox(4, descHeaderRow, descField, descFooterRow);
        } else {
            descSection = new VBox(6, descHeaderRow, descField);
        }

        pane.getChildren().addAll(
                new VBox(6, nameLabel, serverNameField),
                new VBox(6, installLabel, installationDisplayField, installationComboBox),
                descSection);

        if (allowEdit && App.getServices().hasInstallation()) {
            Integer currentDefault = serverDetails.getDefaultChannelPanelWidth();

            Separator widthDivider = new Separator();

            Label widthLabel = sectionLabel("Default Channel Panel Width");

            // Read-only display field mirrors the Installation section's field.
            TextField widthValueField = new TextField(currentDefault != null ? currentDefault + " px" : "Not set");
            widthValueField.setEditable(false);
            widthValueField.setFocusTraversable(false);
            widthValueField.setMaxWidth(Double.MAX_VALUE);
            widthValueField.getStyleClass().add(Styles.SMALL);
            widthValueField.setStyle("-fx-opacity: 0.75;");
            HBox.setHgrow(widthValueField, Priority.ALWAYS);

            Button setBtn = new Button("Use my current width");
            setBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
            setBtn.setFocusTraversable(false);
            setBtn.setOnAction(e -> {
                int px = (int) Math.round(UserSettings.getInstance()
                        .getServerSplitPosition(serverDetails.getServerId(), serverDetails.getDefaultChannelPanelWidth()));
                pendingDefaultChannelPanelWidth = px;
                widthValueField.setText(px + " px");
                ctx.refreshSaveButton();
            });

            HBox widthRow = new HBox(8, widthValueField, setBtn);
            widthRow.setAlignment(Pos.CENTER_LEFT);

            Label widthHint = new Label(
                    "The width new members see the channel list at, before they resize it themselves.");
            widthHint.setWrapText(true);
            widthHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

            VBox widthSection = new VBox(6, widthLabel, widthRow, widthHint);
            widthSection.setMaxWidth(Double.MAX_VALUE);

            pane.getChildren().addAll(widthDivider, widthSection);
        }

        return wrapScroll(pane);
    }

    private void populateExistingData() {
        if (serverDetails == null) return;
        origServerName = serverDetails.getServerName() != null ? serverDetails.getServerName() : "";
        origDescription = serverDetails.getDescription() != null ? serverDetails.getDescription() : "";
        if (serverDetails.getServerName() != null) serverNameField.setText(serverDetails.getServerName());
        if (serverDetails.getDescription() != null) descField.setText(serverDetails.getDescription());
    }

    // ── Installations ──────────────────────────────────────────────────────────

    private void loadInstallations() {
        loadInstallationsService.setOnSucceeded(e -> {
            List<InstallationSummary> list = loadInstallationsService.getValue();
            installationComboBox.getItems().addAll(list);
            if (serverDetails != null) {
                list.stream()
                        .filter(i -> i.getInstallationId().equals(serverDetails.getInstallationId()))
                        .findFirst()
                        .ifPresent(match -> {
                            installationComboBox.getSelectionModel().select(match);
                            installationDisplayField.setText(match.getInstallationName());
                        });
            }
            if (installationComboBox.getSelectionModel().isEmpty() && !list.isEmpty()) {
                installationComboBox.getSelectionModel().selectFirst();
                InstallationSummary first = installationComboBox.getSelectionModel().getSelectedItem();
                if (first != null) installationDisplayField.setText(first.getInstallationName());
            }
        });
        loadInstallationsService.setOnFailed(e ->
                new CustomNotification("Load Error", "Could not load installations. Please try again.",
                        new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                        .showNotification());
        loadInstallationsService.start();
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private void handleSaveServer() {
        String serverName = serverNameField.getText().trim();
        InstallationSummary selected = installationComboBox.getValue();

        if (serverName.isEmpty()) {
            new CustomNotification("Validation Error", "Server name is required.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            serverNameField.requestFocus();
            return;
        }
        if (serverName.length() > 100) {
            new CustomNotification("Validation Error", "Server name must be 100 characters or fewer.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            serverNameField.requestFocus();
            return;
        }
        if (selected == null) {
            new CustomNotification("Validation Error", "Please select an installation.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            return;
        }

        String base64Image = null;
        String format = null;
        if (ctx.avatarWidget().hasNewUpload()) {
            try {
                base64Image = Base64.getEncoder().encodeToString(ctx.avatarWidget().getFinalImageBytes());
                format = ctx.avatarWidget().getImageFormat();
            } catch (Exception e) {
                new CustomNotification("Image Error", e.getMessage(),
                        new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                        .showNotification();
                return;
            }
        }

        currentRequest = ServerUpdateRequest.builder()
                .serverId(serverDetails.getServerId())
                .serverName(serverName)
                .description(descField.getText().trim())
                .avatarBase64(base64Image)
                .avatarContentType(format)
                .build();

        saveServerService.setOnSucceeded(e -> {
            serverDetails.setServerName(currentRequest.getServerName());
            serverDetails.setDescription(currentRequest.getDescription());
            origServerName = currentRequest.getServerName();
            origDescription = currentRequest.getDescription() != null ? currentRequest.getDescription() : "";
            if (pendingDefaultChannelPanelWidth != null) {
                serverDetails.setDefaultChannelPanelWidth(pendingDefaultChannelPanelWidth);
                pendingDefaultChannelPanelWidth = null;
            }
            App.closeModal();
            new CustomNotification("Server Updated", "Server settings have been saved.",
                    new FontIcon(MaterialDesignL.LAN_CHECK)).showNotification();
        });
        saveServerService.setOnFailed(e -> {
            Throwable ex = saveServerService.getException();
            ex.printStackTrace();
            new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
        });
        saveServerService.restart();
    }
}
