package komm.ui.modals.createchannel;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import komm.App;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.model.dto.summary.ChannelSummary.ChannelType;
import komm.ui.customnodes.CustomNotification;
import komm.ui.emojis.EmojiFilterTextField;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

/** "General" tab: channel name and voice/text type selection. */
public class GeneralChannelTab implements ChannelSettingsTab {

    private final ChannelSettingsContext ctx;

    private final TextField channelNameField;
    private HBox voiceCard, textCard;

    /**
     * Suppresses dirty-state evaluation while the text field is initialised programmatically,
     * so the Save button stays disabled until the user actually edits the field.
     */
    private boolean suppressGeneralDirtyCheck = false;
    private boolean generalDirty = false;

    private final VBox pane;
    private ChannelUpdateRequest pendingGeneralRequest;

    private final Service<Void> saveGeneralService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final ChannelUpdateRequest req = pendingGeneralRequest;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().installation().getChannelService()
                            .updateChannel(ctx.editChannel().getChannelId(), req);
                    return null;
                }
            };
        }
    };

    public GeneralChannelTab(ChannelSettingsContext ctx) {
        this.ctx = ctx;
        this.channelNameField = EmojiFilterTextField.maxLength(100);
        this.pane = buildPane();

        if (ctx.isEditMode()) {
            saveGeneralService.runningProperty().addListener((obs, was, isRunning) -> {
                ctx.setSaving(isRunning);
                ctx.refreshSaveButton();
            });
            saveGeneralService.setOnSucceeded(e -> {
                ctx.setOrigName(pendingGeneralRequest.getChannelName());
                generalDirty = false;
                ctx.refreshSaveButton();
                new CustomNotification("Channel Updated", "Channel settings have been saved.",
                        new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
            });
            saveGeneralService.setOnFailed(e -> ChannelSettingsUi.showSaveError(saveGeneralService.getException()));
        }
    }

    // ── ChannelSettingsTab ────────────────────────────────────────────────────────

    @Override public String name() { return "General"; }
    @Override public String description() { return "Set the channel name and type"; }
    @Override public FontIcon icon() { return new FontIcon(Feather.SETTINGS); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return ctx.isEditMode(); }
    @Override public boolean isDirty() { return generalDirty; }
    @Override public boolean isBusy() { return saveGeneralService.isRunning(); }
    @Override public String saveButtonText() { return "Save General"; }
    @Override public void save() { handleSaveGeneral(); }

    // ── Create-mode accessors (read by the shell when building the create request) ─

    public String getChannelName() { return channelNameField.getText().trim(); }
    public void focusNameField() { channelNameField.requestFocus(); }

    // ── Pane ─────────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        VBox pane = new VBox(24);
        pane.setPadding(new Insets(24, 24, 8, 24));
        pane.setMaxWidth(Double.MAX_VALUE);

        voiceCard = buildTypeCard(MaterialDesignM.MICROPHONE, "Voice Channel",
                "Talk, screen share, and send messages");
        textCard = buildTypeCard(Feather.HASH, "Text Channel",
                "Send messages and share files");

        voiceCard.setOnMouseClicked(e -> { if (!ctx.isEditMode()) selectType(true); });
        textCard.setOnMouseClicked(e -> { if (!ctx.isEditMode()) selectType(false); });

        if (ctx.isEditMode()) {
            boolean channelIsVoice = ctx.editChannel().getChannelType() == ChannelType.VOICE;
            ctx.setVoice(channelIsVoice);
            applyCardStyle(voiceCard, channelIsVoice);
            applyCardStyle(textCard, !channelIsVoice);
            voiceCard.setDisable(true);
            textCard.setDisable(true);
            voiceCard.setOpacity(0.5);
            textCard.setOpacity(0.5);
        } else {
            ctx.setVoice(true);
            applyCardStyle(voiceCard, true);
            applyCardStyle(textCard, false);
        }

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        col1.setHgrow(Priority.ALWAYS);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        col2.setHgrow(Priority.ALWAYS);

        GridPane typeRow = new GridPane();
        typeRow.getColumnConstraints().addAll(col1, col2);
        typeRow.setHgap(10);
        typeRow.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(voiceCard, Priority.ALWAYS);
        GridPane.setHgrow(textCard, Priority.ALWAYS);
        voiceCard.setMaxWidth(Double.MAX_VALUE);
        textCard.setMaxWidth(Double.MAX_VALUE);
        typeRow.add(voiceCard, 0, 0);
        typeRow.add(textCard, 1, 0);

        channelNameField.setPromptText("e.g. general, announcements");
        channelNameField.setMaxWidth(Double.MAX_VALUE);
        channelNameField.textProperty().addListener((obs, o, n) -> {
            if (ctx.isEditMode() && !suppressGeneralDirtyCheck) updateGeneralDirtyState();
        });

        if (ctx.isEditMode()) {
            // Suppress the listener during programmatic init so the button stays
            // disabled until the user actually edits the field.
            suppressGeneralDirtyCheck = true;
            channelNameField.setText(ctx.getOrigName());
            suppressGeneralDirtyCheck = false;
        }

        pane.getChildren().addAll(
                new VBox(6, ChannelSettingsUi.sectionLabel("CHANNEL NAME"), channelNameField),
                new VBox(8, ChannelSettingsUi.sectionLabel("CHANNEL TYPE"), typeRow)
        );
        return pane;
    }

    private HBox buildTypeCard(Ikon iconCode, String title, String description) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(20);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label descLbl = new Label(description);
        descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        descLbl.setWrapText(true);

        VBox text = new VBox(2, titleLbl, descLbl);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox card = new HBox(12, icon, text);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setMinHeight(70);
        return card;
    }

    private void applyCardStyle(HBox card, boolean selected) {
        card.setStyle(
                (selected
                        ? "-fx-background-color: -color-accent-subtle; -fx-border-color: -color-accent-emphasis;"
                        : "-fx-background-color: -color-bg-subtle;    -fx-border-color: -color-border-default;") +
                        " -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 1.5; -fx-cursor: hand;"
        );
    }

    private void selectType(boolean voice) {
        ctx.setVoice(voice);
        applyCardStyle(voiceCard, voice);
        applyCardStyle(textCard, !voice);
    }

    // ── Edit-mode dirty state / save ────────────────────────────────────────────

    private void updateGeneralDirtyState() {
        generalDirty = !channelNameField.getText().trim().equals(ctx.getOrigName());
        ctx.refreshSaveButton();
    }

    private void handleSaveGeneral() {
        String name = channelNameField.getText().trim();
        if (name.isEmpty()) {
            new CustomNotification("Validation Error", "Channel name is required.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            channelNameField.requestFocus();
            return;
        }
        if (name.length() > 100) {
            new CustomNotification("Validation Error", "Channel name must be 100 characters or fewer.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            channelNameField.requestFocus();
            return;
        }
        pendingGeneralRequest = ChannelUpdateRequest.builder()
                .channelName(name)
                .icon(ctx.getOrigIcon())
                .build();
        saveGeneralService.restart();
    }
}
