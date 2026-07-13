package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.ChannelCreateRequest;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ChannelSummary.ChannelType;
import komm.model.dto.summary.ServerSummary;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

public class CreateDecorationChannelModal extends VBox {

    private enum DecorationOption {SPACER, DIVIDER, TITLE, CLOCK}

    private DecorationOption selectedOption = DecorationOption.SPACER;
    private TextField titleField;
    private VBox titleSection;
    private VBox spacerCard;
    private VBox dividerCard;
    private VBox titleCard;
    private VBox clockCard;

    private final ServerSummary server;
    private final ChannelSummary editChannel;
    private final boolean isEditMode;

    private ChannelCreateRequest pendingCreate;
    private ChannelUpdateRequest pendingUpdate;

    private final Service<ChannelSummary> createService = new Service<>() {
        @Override
        protected Task<ChannelSummary> createTask() {
            return new Task<>() {
                @Override
                protected ChannelSummary call() throws Exception {
                    return App.getServices().installation().getChannelService().createChannel(pendingCreate);
                }
            };
        }
    };

    private final Service<ChannelSummary> updateService = new Service<>() {
        @Override
        protected Task<ChannelSummary> createTask() {
            return new Task<>() {
                @Override
                protected ChannelSummary call() throws Exception {
                    return App.getServices().installation().getChannelService()
                            .updateChannel(editChannel.getChannelId(), pendingUpdate);
                }
            };
        }
    };

    /**
     * Create mode — lets user pick SPACER, DIVIDER, or TITLE.
     */
    public CreateDecorationChannelModal(ServerSummary server) {
        this(server, null);
    }

    /**
     * Edit mode — only for TITLE, pre-fills the title field.
     */
    public CreateDecorationChannelModal(ServerSummary server, ChannelSummary editChannel) {
        this.server = server;
        this.editChannel = editChannel;
        this.isEditMode = editChannel != null;
        buildUI();
    }

    private void buildUI() {
        getStyleClass().add("custom-modal");
        if (isEditMode) {
            setMaxSize(420, 210);
            setMinSize(420, 210);
            setPrefSize(420, 210);
        } else {
            setMaxSize(680, 370);
            setMinSize(680, 370);
            setPrefSize(680, 370);
        }
        setSpacing(0);

        getChildren().addAll(buildHeader(), buildContent(), buildFooter());

        createService.setOnSucceeded(e -> App.closeModal());
        createService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(createService.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());

        updateService.setOnSucceeded(e -> App.closeModal());
        updateService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(updateService.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
    }

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 8, 0, 16));

        Label title = new Label(isEditMode ? "Edit Title" : "Create Decoration Channel");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(title, spacer, closeBtn);
        return header;
    }

    private VBox buildContent() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(12, 16, 0, 16));
        VBox.setVgrow(content, Priority.ALWAYS);

        if (!isEditMode) {
            Label subtitle = new Label("Choose a decoration type to add between channels.");
            subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
            subtitle.setWrapText(true);

            spacerCard = buildOptionCard(DecorationOption.SPACER, "Spacer",
                    "Empty transparent space", buildSpacerPreview());
            dividerCard = buildOptionCard(DecorationOption.DIVIDER, "Divider",
                    "Horizontal separator line", buildDividerPreview());
            titleCard = buildOptionCard(DecorationOption.TITLE, "Title",
                    "Labeled section header", buildTitlePreview());
            clockCard = buildOptionCard(DecorationOption.CLOCK, "Clock",
                    "Live clock (one per server)", buildClockPreview());

            HBox optionsRow = new HBox(8);
            HBox.setHgrow(spacerCard, Priority.ALWAYS);
            HBox.setHgrow(dividerCard, Priority.ALWAYS);
            HBox.setHgrow(titleCard, Priority.ALWAYS);
            HBox.setHgrow(clockCard, Priority.ALWAYS);
            optionsRow.getChildren().addAll(spacerCard, dividerCard, titleCard, clockCard);

            content.getChildren().addAll(subtitle, optionsRow);
        }

        titleSection = new VBox(6);
        Label titleLabel = new Label("Title Text");
        titleLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        titleField = new TextField();
        titleField.setPromptText("Section title...");
        if (isEditMode) {
            titleField.setText(editChannel.getChannelName());
        }
        titleSection.getChildren().addAll(titleLabel, titleField);

        if (!isEditMode) {
            titleSection.managedProperty().bind(titleSection.visibleProperty());
            titleSection.setVisible(false);
        }

        content.getChildren().add(titleSection);
        return content;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 16, 12, 16));

        Button confirmBtn = new Button(isEditMode ? "Save" : "Create");
        confirmBtn.setDefaultButton(true);
        confirmBtn.getStyleClass().add(Styles.ACCENT);
        confirmBtn.setOnAction(e -> onConfirm());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> App.closeModal());

        footer.getChildren().addAll(confirmBtn, cancelBtn);
        return footer;
    }

    // ── Option cards ──────────────────────────────────────────────────────────

    private VBox buildOptionCard(DecorationOption option, String name, String desc, Node preview) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setAlignment(Pos.TOP_LEFT);
        card.setMinHeight(100);
        card.setPrefWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        applyCardStyle(card, option == selectedOption);

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label descLabel = new Label(desc);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        descLabel.setWrapText(true);

        card.getChildren().addAll(preview, nameLabel, descLabel);
        card.setOnMouseClicked(e -> selectOption(option));
        return card;
    }

    private void applyCardStyle(VBox card, boolean selected) {
        if (selected) {
            card.setStyle(" -fx-border-color: -color-accent-emphasis;"
                    + " -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px;");
        } else {
            card.setStyle("-fx-background-color: -color-bg-default;"
                    + " -fx-border-color: -color-border-muted;"
                    + " -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px;");
        }
    }

    private void selectOption(DecorationOption option) {
        selectedOption = option;
        applyCardStyle(spacerCard, option == DecorationOption.SPACER);
        applyCardStyle(dividerCard, option == DecorationOption.DIVIDER);
        applyCardStyle(titleCard, option == DecorationOption.TITLE);
        applyCardStyle(clockCard, option == DecorationOption.CLOCK);
        titleSection.setVisible(option == DecorationOption.TITLE);
    }

    // ── Previews ──────────────────────────────────────────────────────────────

    private Region buildSpacerPreview() {
        Region r = new Region();
        r.setPrefHeight(28);
        r.setMaxWidth(Double.MAX_VALUE);
        r.setStyle("-fx-border-color: -color-border-muted; -fx-border-width: 1px;"
                + " -fx-border-style: dashed; -fx-border-radius: 3px;");
        return r;
    }

    private HBox buildDividerPreview() {
        HBox box = new HBox();
        box.setAlignment(Pos.CENTER);
        box.setPrefHeight(28);
        box.setMaxWidth(Double.MAX_VALUE);
        Region line = new Region();
        line.setPrefHeight(1.5);
        line.setMaxHeight(1.5);
        line.setStyle("-fx-background-color: -color-border-default;");
        HBox.setHgrow(line, Priority.ALWAYS);
        box.getChildren().add(line);
        return box;
    }

    private HBox buildTitlePreview() {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER);
        box.setPrefHeight(28);
        box.setMaxWidth(Double.MAX_VALUE);
        Region left = makeLine();
        Label lbl = new Label("Title");
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;");
        lbl.setMinWidth(Region.USE_PREF_SIZE);
        Region right = makeLine();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        box.getChildren().addAll(left, lbl, right);
        return box;
    }

    private HBox buildClockPreview() {
        HBox box = new HBox();
        box.setAlignment(Pos.CENTER);
        box.setPrefHeight(28);
        box.setMaxWidth(Double.MAX_VALUE);
        Label lbl = new Label("12:34:56");
        lbl.setStyle("-fx-font-family: 'Pixelify Sans'; -fx-font-size: 18px;" +
                " -fx-text-fill: -color-accent-emphasis;");
        box.getChildren().add(lbl);
        return box;
    }

    private Region makeLine() {
        Region r = new Region();
        r.setPrefHeight(1.5);
        r.setMaxHeight(1.5);
        r.setStyle("-fx-background-color: -color-border-default;");
        return r;
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    private void onConfirm() {
        if (isEditMode) {
            String text = titleField.getText().trim();
            if (text.isBlank()) {
                new CustomNotification("Validation Error", "Title cannot be empty.",
                        new FontIcon(MaterialDesignC.CLOSE)).showNotification();
                return;
            }
            pendingUpdate = ChannelUpdateRequest.builder().channelName(text).build();
            updateService.restart();
        } else {
            ChannelType type = switch (selectedOption) {
                case SPACER -> ChannelType.SPACER;
                case DIVIDER -> ChannelType.DIVIDER;
                case TITLE -> ChannelType.TITLE;
                case CLOCK -> ChannelType.CLOCK;
            };
            if (selectedOption == DecorationOption.TITLE) {
                String text = titleField.getText().trim();
                if (text.isBlank()) {
                    new CustomNotification("Title cannot be empty",
                            new FontIcon(MaterialDesignC.CLOSE)).showNotification();
                    return;
                }
                pendingCreate = ChannelCreateRequest.builder()
                        .serverId(server.getServerId())
                        .channelName(text)
                        .channelType(type)
                        .build();
            } else {
                pendingCreate = ChannelCreateRequest.builder()
                        .serverId(server.getServerId())
                        .channelType(type)
                        .build();
            }
            createService.restart();
        }
    }
}
