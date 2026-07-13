package komm.ui.modals;

import atlantafx.base.theme.Styles;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.util.Duration;
import komm.App;
import komm.ui.customnodes.CustomNotification;
import komm.utils.UserSettings;
import komm.ui.screenshare.SourceCard;
import komm.ui.screenshare.WindowIconFetcher;
import komm.ui.screenshare.ScreenShareQuality;
import komm.ui.screenshare.SourceSelection;
import komm.webrtc.audio.AudioLoopbackCapture;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ScreenShareModal extends HBox {

    // ── State ─────────────────────────────────────────────────────────────────
    private final Consumer<SourceSelection> onStart;

    private TilePane screenTile;
    private TilePane windowTile;
    private ScrollPane screenScroll;
    private ScrollPane windowScroll;
    private StackPane contentArea;

    private VBox screensNavItem;
    private VBox windowsNavItem;
    private VBox qualityNavItem;
    private VBox activeNavItem;
    private Node qualityView;

    private SourceCard selectedCard;
    private Button startButton;
    private Button cancelButton;
    private Button closeButton;
    private ProgressIndicator progressIndicator;
    private CheckBox audioCheck;
    private Label statusLabel;

    private int selectedResolution = UserSettings.getInstance().getScreenShareResolution();
    private int selectedFps = UserSettings.getInstance().getScreenShareFps();

    /** Whether this OS/build can capture system audio excluding our own process. */
    private final boolean audioSupported = AudioLoopbackCapture.isSupported();

    /**
     * Wayland sessions can't enumerate screens/windows — the xdg-desktop-portal
     * shows its own picker dialog when capture starts. The source grid is replaced
     * by an info view, and Start Sharing is enabled immediately.
     */
    private static final boolean WAYLAND = com.sun.jna.Platform.isLinux()
            && (System.getenv("WAYLAND_DISPLAY") != null
                || "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")));

    private Node portalInfoView;

    private final List<SourceCard> allCards = new ArrayList<>();

    // Thread pool for thumbnail/icon capture — bounded so we don't spawn hundreds.
    private final ExecutorService thumbPool =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "thumb-capture");
                t.setDaemon(true);
                return t;
            });

    // Serializes all WindowCapturer operations — the native object is not thread-safe.
    private final Semaphore windowCapturerSem = new Semaphore(1);

    private Timeline pollTimeline;

    public ScreenShareModal(Consumer<SourceSelection> onStart) {
        this.onStart = onStart;

        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(956, 600);
        setMinSize(956, 600);
        setPrefSize(956, 600);
        setSpacing(0);

        qualityView = createQualityView();

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(Insets.EMPTY);

        VBox rightColumn = createRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(createLeftPanel(), vDivider, rightColumn);

        if (!WAYLAND) loadSourcesAsync();
    }

    // ── Left panel ────────────────────────────────────────────────────────────

    private VBox createLeftPanel() {
        VBox pane = new VBox(0);
        pane.setPrefWidth(190);
        pane.setMinWidth(190);
        pane.setMaxWidth(190);
        pane.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        VBox nav = new VBox(2);
        nav.setPadding(new Insets(16, 8, 12, 8));

        String sectionLabelStyle =
                "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;";

        Label sourceLabel = new Label("SOURCE");
        sourceLabel.setStyle(sectionLabelStyle + " -fx-padding: 0 0 4px 10px;");

        if (WAYLAND) {
            screensNavItem = buildNavItem(new FontIcon(MaterialDesignM.MONITOR), "Screen or App", this::switchToPortalInfo);
        } else {
            screensNavItem = buildNavItem(new FontIcon(MaterialDesignM.MONITOR), "Screens", this::switchToScreens);
            windowsNavItem = buildNavItem(new FontIcon(MaterialDesignA.APPLICATION_OUTLINE), "Applications", this::switchToWindows);
        }

        Separator divider = new Separator(Orientation.HORIZONTAL);
        divider.setPadding(new Insets(6, 0, 2, 0));

        Label settingsLabel = new Label("SETTINGS");
        settingsLabel.setStyle(sectionLabelStyle + " -fx-padding: 4px 0 4px 10px;");

        qualityNavItem = buildNavItem(new FontIcon(MaterialDesignT.TUNE), "Quality", this::switchToQuality);

        setNavActive(screensNavItem);
        activeNavItem = screensNavItem;

        nav.getChildren().addAll(sourceLabel, screensNavItem);
        if (!WAYLAND) nav.getChildren().add(windowsNavItem);
        nav.getChildren().addAll(divider, settingsLabel, qualityNavItem);
        pane.getChildren().add(nav);
        return pane;
    }

    private VBox buildNavItem(FontIcon icon, String text, Runnable onSwitch) {
        icon.setIconSize(16);
        Label lbl = new Label(text);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> onSwitch.run());
        return item;
    }

    private void setNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    // ── Right column ──────────────────────────────────────────────────────────

    private VBox createRightColumn() {
        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(column, Priority.ALWAYS);
        column.getChildren().addAll(createHeader(), createContentArea(), createFooter());
        return column;
    }

    private HBox createHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 14, 24));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        VBox titleBox = new VBox(3);
        Label title = new Label("Share your screen");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label(WAYLAND
                ? "The system dialog will ask what to share"
                : "Choose what you'd like to share");
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        TextField searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.setPrefWidth(160);
        searchField.getStyleClass().add(Styles.SMALL);
        searchField.textProperty().addListener((obs, old, text) -> filterCards(text.trim().toLowerCase()));
        if (WAYLAND) {
            searchField.setVisible(false);
            searchField.setManaged(false);
        }

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(e -> {
            thumbPool.shutdownNow();
            stopWindowWatcher();
            App.closeModal();
        });

        header.getChildren().addAll(titleBox, spacer, searchField, closeButton);
        return header;
    }

    private StackPane createContentArea() {
        contentArea = new StackPane();
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        contentArea.setPadding(new Insets(12, 12, 0, 12));

        screenTile = buildTilePane();
        windowTile = buildTilePane();
        screenScroll = wrapScroll(screenTile);
        windowScroll = wrapScroll(windowTile);

        if (WAYLAND) {
            portalInfoView = createPortalInfoView();
            contentArea.getChildren().add(portalInfoView);
            return contentArea;
        }

        ProgressIndicator loadingSpinner = new ProgressIndicator();
        loadingSpinner.setMaxSize(32, 32);
        Label loadingLabel = new Label("Loading sources…");
        loadingLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        VBox loadingBox = new VBox(10, loadingSpinner, loadingLabel);
        loadingBox.setAlignment(Pos.CENTER);

        contentArea.getChildren().add(loadingBox);
        return contentArea;
    }

    private TilePane buildTilePane() {
        TilePane tile = new TilePane();
        tile.setHgap(10);
        tile.setVgap(10);
        tile.setPadding(new Insets(4));
        tile.setPrefTileWidth(172);
        tile.setPrefTileHeight(130);
        return tile;
    }

    private ScrollPane wrapScroll(TilePane tile) {
        ScrollPane scroll = new ScrollPane(tile);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 24, 18, 24));
        footer.setStyle("-fx-border-color: -color-border-default; -fx-border-width: 1px 0 0 0;");

        audioCheck = new CheckBox("Stream computer audio");
        audioCheck.setFocusTraversable(false);
        audioCheck.getStyleClass().add(Styles.SMALL);
        // On Wayland the source is picked in the system dialog, so audio can't be
        // gated on "full screen selected" — offer it whenever the platform supports it.
        audioCheck.setDisable(!(WAYLAND && audioSupported));
        audioCheck.setTooltip(new Tooltip(!audioSupported
                ? "System audio capture isn't available on this platform."
                : WAYLAND
                    ? "Sends your PC's audio (excluding Komm)."
                    : "Sends your PC's audio (excluding Komm). Available only when sharing a full screen."));

        statusLabel = new Label(WAYLAND ? "Source is picked in the system dialog" : "No source selected");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        cancelButton = new Button("Cancel");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
        cancelButton.setOnAction(e -> {
            thumbPool.shutdownNow();
            stopWindowWatcher();
            App.closeModal();
        });

        startButton = new Button("Start Sharing");
        startButton.setFocusTraversable(false);
        startButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        // Wayland: nothing to pre-select — the portal dialog picks the source at start.
        startButton.setDisable(!WAYLAND);
        startButton.setOnAction(e -> handleStartSharing());

        footer.getChildren().addAll(
                audioCheck,
                spacer,
                cancelButton, startButton
        );
        return footer;
    }

    // ── Quality tab ───────────────────────────────────────────────────────────

    private Node createQualityView() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20, 24, 16, 24));
        root.setAlignment(Pos.TOP_LEFT);

        Label resLabel = new Label("Resolution");
        resLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        ToggleGroup resGroup = new ToggleGroup();
        HBox resRow = new HBox(8);
        for (int res : new int[]{360, 480, 720, 1080, 1440}) {
            ToggleButton btn = new ToggleButton(res + "p");
            btn.setToggleGroup(resGroup);
            btn.setFocusTraversable(false);
            btn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
            btn.setPrefWidth(70);
            if (res == selectedResolution) btn.setSelected(true);
            int r = res;
            btn.selectedProperty().addListener((obs, was, is) -> {
                if (is) {
                    selectedResolution = r;
                    UserSettings.getInstance().setScreenShareResolution(r);
                }
            });
            resRow.getChildren().add(btn);
        }
        resGroup.selectedToggleProperty().addListener((obs, old, nv) -> {
            if (nv == null) old.setSelected(true);
        });

        Label fpsLabel = new Label("Frame Rate");
        fpsLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        ToggleGroup fpsGroup = new ToggleGroup();
        HBox fpsRow = new HBox(8);
        for (int fps : new int[]{15, 30, 60, 120}) {
            ToggleButton btn = new ToggleButton(fps + " fps");
            btn.setToggleGroup(fpsGroup);
            btn.setFocusTraversable(false);
            btn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
            btn.setPrefWidth(80);
            if (fps == selectedFps) btn.setSelected(true);
            int f = fps;
            btn.selectedProperty().addListener((obs, was, is) -> {
                if (is) {
                    selectedFps = f;
                    UserSettings.getInstance().setScreenShareFps(f);
                }
            });
            fpsRow.getChildren().add(btn);
        }
        fpsGroup.selectedToggleProperty().addListener((obs, old, nv) -> {
            if (nv == null) old.setSelected(true);
        });

        root.getChildren().addAll(resLabel, resRow, fpsLabel, fpsRow);
        return root;
    }

    // ── Source loading ────────────────────────────────────────────────────────

    private void loadSourcesAsync() {
        thumbPool.submit(() -> {
            List<SourceCard> screenCards = new ArrayList<>();
            List<SourceCard> windowCards = new ArrayList<>();

            try {
                ScreenCapturer sc = new ScreenCapturer();
                List<DesktopSource> screens = sc.getDesktopSources();
                sc.dispose();
                int screenIndex = 1;
                for (DesktopSource src : screens) {
                    String title = (src.title != null && !src.title.isBlank())
                            ? src.title : "Screen " + screenIndex;
                    screenIndex++;
                    screenCards.add(new SourceCard(src.id, title, false));
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            try {
                windowCapturerSem.acquire();
                try {
                    WindowCapturer wc = new WindowCapturer();
                    for (DesktopSource src : wc.getDesktopSources()) {
                        if (src.title != null && !src.title.isBlank())
                            windowCards.add(new SourceCard(src.id, src.title, true));
                    }
                    wc.dispose();
                } finally {
                    windowCapturerSem.release();
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            if (thumbPool.isShutdown()) return;
            Platform.runLater(() -> {
                allCards.addAll(screenCards);
                allCards.addAll(windowCards);

                for (SourceCard card : screenCards) {
                    card.setOnSelected(ScreenShareModal.this::onCardSelected);
                    screenTile.getChildren().add(card);
                }
                for (SourceCard card : windowCards) {
                    card.setOnSelected(ScreenShareModal.this::onCardSelected);
                    windowTile.getChildren().add(card);
                }

                switchToScreens();
                startWindowWatcher();

                for (SourceCard card : allCards) {
                    try {
                        thumbPool.submit(() -> fetchThumbnail(card));
                    } catch (RejectedExecutionException ignored) {
                    }
                }
                for (SourceCard card : windowCards) {
                    try {
                        thumbPool.submit(() -> WindowIconFetcher.fetchWindowIcon(card));
                    } catch (RejectedExecutionException ignored) {
                    }
                }
            });
        });
    }

    private void fetchThumbnail(SourceCard card) {
        if (card.isWindow()) {
            try {
                windowCapturerSem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        DesktopCapturer capturer = null;
        try {
            capturer = card.isWindow() ? new WindowCapturer() : new ScreenCapturer();

            DesktopSource source = null;
            for (DesktopSource s : capturer.getDesktopSources()) {
                if (s.id == card.getSourceId()) {
                    source = s;
                    break;
                }
            }
            if (source == null) return;

            capturer.selectSource(source);

            AtomicReference<Image> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            capturer.start((result, frame) -> {
                if (result == DesktopCapturer.Result.SUCCESS && frame != null) {
                    try {
                        Image img = videoFrameToFxImage(frame.buffer);
                        captured.set(img);
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                } else {
                    latch.countDown();
                }
            });

            capturer.captureFrame();
            latch.await(4, TimeUnit.SECONDS);

            Image img = captured.get();
            if (img != null) {
                Platform.runLater(() -> card.setThumbnail(img));
            }
        } catch (Throwable t) {
            // Non-fatal — card keeps its placeholder.
        } finally {
            if (capturer != null) try {
                capturer.dispose();
            } catch (Throwable ignored) {
            }
            if (card.isWindow()) windowCapturerSem.release();
        }
    }

    private Image videoFrameToFxImage(VideoFrameBuffer frameBuffer) throws Exception {
        int w = frameBuffer.getWidth();
        int h = frameBuffer.getHeight();
        if (w <= 0 || h <= 0) return null;

        BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
        byte[] buf = ((java.awt.image.DataBufferByte) full.getRaster().getDataBuffer()).getData();
        VideoBufferConverter.convertFromI420(frameBuffer, buf, FourCC.RGBA);

        int tileW = 156, tileH = 88;
        double scale = Math.min((double) tileW / w, (double) tileH / h);
        int dstW = Math.max(1, (int) (w * scale));
        int dstH = Math.max(1, (int) (h * scale));

        BufferedImage current = full;
        int curW = w, curH = h;
        while (curW > dstW * 2 || curH > dstH * 2) {
            curW = Math.max(curW / 2, dstW);
            curH = Math.max(curH / 2, dstH);
            BufferedImage half = new BufferedImage(curW, curH, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g2h = half.createGraphics();
            g2h.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2h.drawImage(current, 0, 0, curW, curH, null);
            g2h.dispose();
            current = half;
        }

        BufferedImage scaled = new BufferedImage(dstW, dstH, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(current, 0, 0, dstW, dstH, null);
        g2.dispose();

        return bufferedImageToFxImage(scaled);
    }

    private Image bufferedImageToFxImage(BufferedImage bi) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()), 0, 0, true, true);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private void switchToScreens() {
        if (activeNavItem != screensNavItem) {
            setNavInactive(activeNavItem);
            setNavActive(screensNavItem);
            activeNavItem = screensNavItem;
        }
        animateContentTo(screenScroll);
        if (selectedCard != null && selectedCard.isWindow()) {
            selectedCard.setSelected(false);
            selectedCard = null;
            updateSelectionState(null);
        }
    }

    private void switchToWindows() {
        if (activeNavItem != windowsNavItem) {
            setNavInactive(activeNavItem);
            setNavActive(windowsNavItem);
            activeNavItem = windowsNavItem;
        }
        animateContentTo(windowScroll);
        if (selectedCard != null && !selectedCard.isWindow()) {
            selectedCard.setSelected(false);
            selectedCard = null;
            updateSelectionState(null);
        }
    }

    /** Wayland: no source grid — explain that the desktop portal picks the source. */
    private Node createPortalInfoView() {
        FontIcon icon = new FontIcon(MaterialDesignM.MONITOR);
        icon.getStyleClass().add("custom-icon-35");
        icon.setOpacity(0.25);

        Label title = new Label("Your system will ask what to share");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label text = new Label(
                "Wayland doesn't let apps list screens and windows. When you press " +
                "Start Sharing, the system dialog will open — pick the screen or " +
                "application to share there.");
        text.setWrapText(true);
        text.setMaxWidth(420);
        text.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted; -fx-text-alignment: center;");

        VBox box = new VBox(14, icon, title, text);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void switchToPortalInfo() {
        if (activeNavItem != screensNavItem) {
            setNavInactive(activeNavItem);
            setNavActive(screensNavItem);
            activeNavItem = screensNavItem;
        }
        animateContentTo(portalInfoView);
    }

    private void switchToQuality() {
        if (activeNavItem != qualityNavItem) {
            setNavInactive(activeNavItem);
            setNavActive(qualityNavItem);
            activeNavItem = qualityNavItem;
        }
        animateContentTo(qualityView);
    }

    private void animateContentTo(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(120), contentArea);
        fade.setFromValue(0.6);
        fade.setToValue(1.0);
        contentArea.getChildren().setAll(node);
        fade.play();
    }

    // ── Card logic ────────────────────────────────────────────────────────────

    private void onCardSelected(SourceCard card) {
        if (selectedCard != null && selectedCard != card) selectedCard.setSelected(false);
        selectedCard = card;
        card.setSelected(true);
        updateSelectionState(card);
    }

    private void updateSelectionState(SourceCard card) {
        if (card == null) {
            startButton.setDisable(true);
            statusLabel.setText("No source selected");
        } else {
            startButton.setDisable(false);
            String truncated = card.getSourceName().length() > 38
                    ? card.getSourceName().substring(0, 35) + "…"
                    : card.getSourceName();
            statusLabel.setText("Selected: " + truncated);
        }
        // System audio is only offered for a full screen (not a single window), and only
        // on a platform that supports loopback capture (Windows process-loopback EXCLUDE
        // or Linux PulseAudio/PipeWire monitor source).
        boolean audioAllowed = audioSupported && card != null && !card.isWindow();
        audioCheck.setDisable(!audioAllowed);
        if (!audioAllowed) audioCheck.setSelected(false);
    }

    private void filterCards(String query) {
        for (SourceCard card : allCards) {
            boolean visible = query.isEmpty() || card.getSourceName().toLowerCase().contains(query);
            card.setVisible(visible);
            card.setManaged(visible);
        }
    }

    private void handleStartSharing() {
        if (WAYLAND) {
            // No card selection on Wayland — the xdg-desktop-portal dialog picks the
            // source once capture starts, and the PipeWire capturer ignores the id.
            startButton.setDisable(true);
            thumbPool.shutdownNow();
            SourceSelection selection = SourceSelection.builder()
                    .sourceId(0)
                    .isWindow(false)
                    .quality(new ScreenShareQuality(selectedFps, resolutionToWidth(selectedResolution), selectedResolution))
                    .audioEnabled(audioSupported && audioCheck.isSelected())
                    .build();
            App.closeModal();
            if (onStart != null) onStart.accept(selection);
            return;
        }

        if (selectedCard == null) return;
        startButton.setDisable(true);

        thumbPool.shutdownNow();
        stopWindowWatcher();

        if (!selectedCard.isWindow()) {
            doStartSharing(selectedCard);
            return;
        }

        // Verify the window still exists before committing.
        final SourceCard card = selectedCard;
        Thread.ofVirtual().start(() -> {
            boolean available = false;
            try {
                windowCapturerSem.acquire();
                try {
                    WindowCapturer wc = new WindowCapturer();
                    available = wc.getDesktopSources().stream()
                            .anyMatch(s -> s.id == card.getSourceId());
                    wc.dispose();
                } finally {
                    windowCapturerSem.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {}

            final boolean isAvailable = available;
            Platform.runLater(() -> {
                if (getScene() == null) return;
                if (isAvailable) {
                    doStartSharing(card);
                } else {
                    startButton.setDisable(false);
                    windowTile.getChildren().remove(card);
                    allCards.remove(card);
                    card.setSelected(false);
                    selectedCard = null;
                    updateSelectionState(null);
                    new CustomNotification(
                            "Screen Share Error",
                            "Application is no longer available.",
                            new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)
                    ).showNotification();
                }
            });
        });
    }

    private void doStartSharing(SourceCard card) {
        SourceSelection selection = SourceSelection.builder()
                .sourceId(card.getSourceId())
                .isWindow(card.isWindow())
                .quality(new ScreenShareQuality(selectedFps, resolutionToWidth(selectedResolution), selectedResolution))
                .audioEnabled(audioSupported && !card.isWindow() && audioCheck.isSelected())
                .build();
        App.closeModal();
        if (onStart != null) onStart.accept(selection);
    }

    private static int resolutionToWidth(int height) {
        return switch (height) {
            case 360 -> 640;
            case 480 -> 854;
            case 720 -> 1280;
            case 1440 -> 2560;
            default -> 1920;
        };
    }

    // ── Window watcher ────────────────────────────────────────────────────────

    private void startWindowWatcher() {
        pollTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshWindowList()));
        pollTimeline.setCycleCount(Animation.INDEFINITE);
        pollTimeline.play();
    }

    private void stopWindowWatcher() {
        if (pollTimeline != null) {
            pollTimeline.stop();
            pollTimeline = null;
        }
    }

    private void refreshWindowList() {
        if (thumbPool.isShutdown()) return;

        Set<Long> existing = new HashSet<>();
        for (SourceCard c : allCards) {
            if (c.isWindow()) existing.add(c.getSourceId());
        }

        try {
            thumbPool.submit(() -> {
                try {
                    windowCapturerSem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (thumbPool.isShutdown()) return;
                    WindowCapturer wc = new WindowCapturer();
                    List<SourceCard> newCards = new ArrayList<>();
                    for (DesktopSource src : wc.getDesktopSources()) {
                        if (src.title == null || src.title.isBlank()) continue;
                        if (existing.contains(src.id)) continue;
                        newCards.add(new SourceCard(src.id, src.title, true));
                    }
                    wc.dispose();
                    if (newCards.isEmpty()) return;
                    Platform.runLater(() -> {
                        for (SourceCard card : newCards) {
                            card.setOnSelected(this::onCardSelected);
                            allCards.add(card);
                            windowTile.getChildren().add(card);
                            try {
                                thumbPool.submit(() -> fetchThumbnail(card));
                                thumbPool.submit(() -> WindowIconFetcher.fetchWindowIcon(card));
                            } catch (RejectedExecutionException ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    windowCapturerSem.release();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
