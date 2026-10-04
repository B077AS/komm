package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.ChannelCreateRequest;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ChannelSummary.ChannelType;
import komm.model.dto.summary.ServerSummary;
import komm.ui.cards.ChannelCard;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateDecorationChannelModal extends VBox {

    private enum DecorationOption {SPACER, DIVIDER, TITLE, CLOCK}

    private record OptionMeta(DecorationOption option, String label, String desc) {}

    private static final List<OptionMeta> OPTIONS = List.of(
            new OptionMeta(DecorationOption.SPACER, "Spacer", "Empty transparent space"),
            new OptionMeta(DecorationOption.DIVIDER, "Divider", "Horizontal separator line"),
            new OptionMeta(DecorationOption.TITLE, "Title", "Labeled section header"),
            new OptionMeta(DecorationOption.CLOCK, "Clock", "Live clock (one per server)")
    );

    private static final String SECTION_LABEL_STYLE =
            "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;";

    private final Map<DecorationOption, VBox> optionRows = new EnumMap<>(DecorationOption.class);

    private DecorationOption selectedOption = DecorationOption.SPACER;
    private TextField titleField;
    private VBox titleSection;
    private Label helperLabel;
    private StackPane previewSlot;

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
     * Create mode — lets user pick SPACER, DIVIDER, TITLE, or CLOCK.
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
            setMaxSize(680, 440);
            setMinSize(680, 440);
            setPrefSize(680, 440);
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

        // Title field — shared between create (shown contextually) and edit (always shown) modes.
        Label titleLabel = new Label("Title Text");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        titleField = new TextField();
        titleField.setPromptText("Section title...");
        if (isEditMode) {
            titleField.setText(editChannel.getChannelName());
        }
        titleField.textProperty().addListener((obs, oldV, newV) -> {
            if (!isEditMode && selectedOption == DecorationOption.TITLE) refreshDecorationPreview();
        });
        titleSection = new VBox(6, titleLabel, titleField);

        if (isEditMode) {
            content.getChildren().add(titleSection);
            return content;
        }

        Label subtitle = new Label("Choose a decoration type to add between channels.");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        subtitle.setWrapText(true);

        Separator vDivider = new Separator(Orientation.VERTICAL);

        HBox mainRow = new HBox(16, buildOptionList(), vDivider, buildPreviewPanel());
        HBox.setHgrow(mainRow, Priority.ALWAYS);
        VBox.setVgrow(mainRow, Priority.ALWAYS);

        content.getChildren().addAll(subtitle, mainRow);

        // Now that the list, preview and detail area all exist, apply the initial selection.
        selectOption(selectedOption);

        return content;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 16, 12, 16));

        Button confirmBtn = new Button(isEditMode ? "Save" : "Create");
        confirmBtn.setDefaultButton(true);
        confirmBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        confirmBtn.setOnAction(e -> onConfirm());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> App.closeModal());

        footer.getChildren().addAll(cancelBtn, confirmBtn);
        return footer;
    }

    // ── Option list (left column) ────────────────────────────────────────────
    // A vertical, scrollable list rather than a fixed-width row of cards: adding
    // a 5th/6th decoration type just grows the list instead of squeezing cards.

    private ScrollPane buildOptionList() {
        VBox list = new VBox(4);
        list.setPadding(new Insets(2, 4, 2, 0));

        for (OptionMeta meta : OPTIONS) {
            VBox row = buildOptionRow(meta);
            optionRows.put(meta.option(), row);
            list.getChildren().add(row);
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefWidth(220);
        scroll.setMinWidth(220);
        scroll.setMaxWidth(220);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private VBox buildOptionRow(OptionMeta meta) {
        FontIcon icon = iconFor(meta.option());
        icon.setIconSize(16);

        Label nameLabel = new Label(meta.label());
        nameLabel.getStyleClass().add("nav-label");

        HBox headerRow = new HBox(10, icon, nameLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label descLabel = new Label(meta.desc());
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        descLabel.setWrapText(true);

        VBox row = new VBox(3, headerRow, descLabel);
        row.getStyleClass().add("nav-item");
        row.setPadding(new Insets(9, 10, 9, 10));
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> selectOption(meta.option()));
        return row;
    }

    private FontIcon iconFor(DecorationOption option) {
        return switch (option) {
            case SPACER -> new FontIcon(MaterialDesignA.ARROW_EXPAND_VERTICAL);
            case DIVIDER -> new FontIcon(MaterialDesignM.MINUS);
            case TITLE -> new FontIcon(MaterialDesignF.FORMAT_TITLE);
            case CLOCK -> new FontIcon(MaterialDesignC.CLOCK_OUTLINE);
        };
    }

    // ── Preview panel (right column) ─────────────────────────────────────────
    // Renders the actual ChannelCard component — the same one the real channel
    // list uses — sat between two mock text channels, so the preview is pixel-
    // identical to production (including the live LED ClockCanvas for Clock).
    // A detail slot below it — the title input for TITLE, or a short note for
    // the others — is always populated, so there is never dead empty space.

    private VBox buildPreviewPanel() {
        VBox panel = new VBox(10);
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label heading = new Label("PREVIEW");
        heading.setStyle(SECTION_LABEL_STYLE);

        previewSlot = new StackPane();

        VBox mockList = new VBox(2, mockTextChannel("general"), previewSlot, mockTextChannel("off-topic"));
        mockList.setPadding(new Insets(8));
        mockList.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4px;");

        helperLabel = new Label();
        helperLabel.setWrapText(true);
        helperLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-subtle;");

        VBox detailBox = new VBox(6, titleSection, helperLabel);

        panel.getChildren().addAll(heading, mockList, detailBox);
        return panel;
    }

    /** A real, inert ChannelCard for a plain text channel — gives the preview real context. */
    private ChannelCard mockTextChannel(String name) {
        ChannelSummary ch = ChannelSummary.builder()
                .channelId(UUID.randomUUID())
                .serverId(server.getServerId())
                .channelName(name)
                .channelType(ChannelType.TEXT)
                .build();
        ChannelCard card = new ChannelCard(ch, server);
        card.setMouseTransparent(true);
        return card;
    }

    /** Rebuilds the previewed decoration as a real ChannelCard for the currently selected type. */
    private void refreshDecorationPreview() {
        ChannelType type = switch (selectedOption) {
            case SPACER -> ChannelType.SPACER;
            case DIVIDER -> ChannelType.DIVIDER;
            case TITLE -> ChannelType.TITLE;
            case CLOCK -> ChannelType.CLOCK;
        };
        String name = selectedOption == DecorationOption.TITLE
                ? (titleField.getText().isBlank() ? "Title" : titleField.getText())
                : "";
        ChannelSummary mock = ChannelSummary.builder()
                .channelId(UUID.randomUUID())
                .serverId(server.getServerId())
                .channelName(name)
                .channelType(type)
                .build();
        ChannelCard card = new ChannelCard(mock, server);
        card.setMouseTransparent(true);
        previewSlot.getChildren().setAll(card);
    }

    private void selectOption(DecorationOption option) {
        selectedOption = option;

        optionRows.forEach((opt, row) -> {
            if (opt == option) {
                if (!row.getStyleClass().contains("nav-active")) row.getStyleClass().add("nav-active");
            } else {
                row.getStyleClass().remove("nav-active");
            }
        });

        refreshDecorationPreview();

        boolean isTitle = option == DecorationOption.TITLE;
        titleSection.setVisible(isTitle);
        titleSection.setManaged(isTitle);
        helperLabel.setVisible(!isTitle);
        helperLabel.setManaged(!isTitle);
        helperLabel.setText(helperTextFor(option));
    }

    private String helperTextFor(DecorationOption option) {
        return switch (option) {
            case SPACER -> "Adds empty space between channels. No additional configuration needed.";
            case DIVIDER -> "Adds a thin separator line between channels. No additional configuration needed.";
            case CLOCK -> "Shows a live clock in the channel list. Only one clock decoration is allowed per server.";
            case TITLE -> "";
        };
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
