package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import komm.ui.utils.IconColorUtil;
import komm.utils.AppConfig;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignO;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.awt.Desktop;
import java.net.URI;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Info" tab: app identity, build/runtime details, and project links. Read-only, no Save button. */
public class InfoUserTab implements UserSettingsTab {

    private static final String REPO_URL = "https://github.com/B077AS/komm";
    private static final String WEBSITE_URL = "https://kommvoice.com";
    private static final int LOGO_SIZE = 64;

    private final ScrollPane pane;

    public InfoUserTab() {
        pane = wrapScroll(buildContent());
    }

    private VBox buildContent() {
        VBox root = new VBox(22);
        root.setPadding(new Insets(24, 28, 24, 28));
        root.setAlignment(Pos.TOP_LEFT);

        root.getChildren().addAll(buildHero(), buildSystemCard(), buildLinksSection());
        return root;
    }

    // ── Hero ──────────────────────────────────────────────────────────────────

    private HBox buildHero() {
        double r = LOGO_SIZE / 2.0;

        // Same badge-circle + waveform-bars geometry the komm-launcher draws
        // (from icon.svg's 64x64 viewBox) rather than clipping the raster
        // rounded-square icon.png to a circle — that left visible corner
        // artifacts since the square art doesn't fill an inscribed circle.
        Group logo = buildLogoGraphic(LOGO_SIZE);
        logo.setStyle("-fx-effect: dropshadow(gaussian, -color-accent-emphasis, 10, 0.25, 0, 0);");

        Circle ring = new Circle(r + 2);
        ring.setStyle("-fx-fill: transparent; -fx-stroke: -color-accent-emphasis; -fx-stroke-width: 1.5; -fx-opacity: 0.45;");

        StackPane logoWrap = new StackPane(ring, logo);
        logoWrap.setMinSize(LOGO_SIZE + 8, LOGO_SIZE + 8);
        logoWrap.setPrefSize(LOGO_SIZE + 8, LOGO_SIZE + 8);
        logoWrap.setMaxSize(LOGO_SIZE + 8, LOGO_SIZE + 8);

        Label name = new Label("Komm");
        name.setStyle("-fx-font-size: 22px; -fx-font-weight: 800;");

        String version = AppConfig.getInstance().getProperty("client.version");
        Label versionPill = new Label("v" + (version != null && !version.isBlank() ? version : "dev"));
        versionPill.setStyle(
                "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-emphasis;" +
                "-fx-background-color: -color-accent-emphasis; -fx-background-radius: 20px;" +
                "-fx-padding: 3 10 3 10;");
        HBox.setMargin(versionPill, new Insets(2, 0, 0, 0));

        HBox titleRow = new HBox(10, name, versionPill);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label tagline = new Label("Voice, video and chat — built for your own communities.");
        tagline.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        tagline.setWrapText(true);

        VBox textBlock = new VBox(6, titleRow, tagline);
        textBlock.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        HBox hero = new HBox(18, logoWrap, textBlock);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(20));
        hero.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, -color-accent-muted, rgba(255,255,255,0.03));" +
                "-fx-background-radius: 14px;" +
                "-fx-border-color: -color-accent-emphasis;" +
                "-fx-border-radius: 14px;" +
                "-fx-border-width: 1px;");
        return hero;
    }

    /** Redraws the komm mark (badge circle + waveform bars) from icon.svg's 64x64 geometry. */
    private static Group buildLogoGraphic(double size) {
        double s = size / 64.0;

        Circle badge = new Circle(32 * s, 32 * s, 32 * s);
        badge.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#b8a4ff")),
                new Stop(0.55, Color.web("#9580ff")),
                new Stop(1, Color.web("#6b5bb3"))));

        Group logo = new Group(badge);
        double[][] bars = {{9, 25, 14}, {19, 19, 26}, {29, 13, 38}, {39, 19, 26}, {49, 25, 14}};
        for (double[] bar : bars) {
            Rectangle barRect = new Rectangle(bar[0] * s, bar[1] * s, 6 * s, bar[2] * s);
            barRect.setArcWidth(6 * s);
            barRect.setArcHeight(6 * s);
            barRect.setFill(Color.web("#0d0e12"));
            logo.getChildren().add(barRect);
        }
        return logo;
    }

    // ── System card ───────────────────────────────────────────────────────────

    private VBox buildSystemCard() {
        VBox card = new VBox(0);
        card.getChildren().addAll(
                sectionLabel("SYSTEM"), spacer(8),
                infoRow(MaterialDesignT.TAG_OUTLINE, "App Version", displayOrUnknown(AppConfig.getInstance().getProperty("client.version"))),
                divider(),
                infoRow(osIcon(), "Operating System", osDescription()),
                divider(),
                infoRow(MaterialDesignL.LANGUAGE_JAVA, "Java Runtime", javaDescription()),
                divider(),
                infoRow(MaterialDesignA.APPLICATION_OUTLINE, "JavaFX", javafxDescription())
        );
        return card;
    }

    private HBox infoRow(Ikon icon, String key, String value) {
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("custom-accent-icon");

        Label keyLbl = new Label(key);
        keyLbl.setStyle("-fx-font-size: 12.5px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label valueLbl = new Label(value);
        valueLbl.setStyle("-fx-font-size: 12.5px; -fx-font-weight: bold;");

        HBox row = new HBox(10, fi, keyLbl, spacer, valueLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(9, 4, 9, 4));
        return row;
    }

    private Region divider() {
        Region r = new Region();
        r.setMinHeight(1);
        r.setMaxHeight(1);
        r.setStyle("-fx-background-color: -color-border-default;");
        return r;
    }

    // ── Links section ─────────────────────────────────────────────────────────

    private VBox buildLinksSection() {
        HBox tiles = new HBox(10,
                linkTile(MaterialDesignW.WEB, "Website", WEBSITE_URL),
                linkTile(MaterialDesignG.GITHUB, "GitHub", REPO_URL),
                linkTile(MaterialDesignB.BUG_OUTLINE, "Report Issue", REPO_URL + "/issues")
        );
        tiles.setFillHeight(true);

        VBox section = new VBox(10, sectionLabel("LINKS"), tiles);
        return section;
    }

    private VBox linkTile(Ikon icon, String label, String url) {
        FontIcon fi = IconColorUtil.colored(icon, "-color-accent-fg", 18);
        StackPane badge = new StackPane(fi);
        badge.setMinSize(38, 38);
        badge.setMaxSize(38, 38);
        badge.setStyle("-fx-background-color: -color-accent-muted; -fx-background-radius: 11px;");

        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold;");

        FontIcon openIcon = IconColorUtil.colored(MaterialDesignO.OPEN_IN_NEW, "-color-fg-subtle", 11);

        HBox labelRow = new HBox(4, lbl, openIcon);
        labelRow.setAlignment(Pos.CENTER);

        VBox tile = new VBox(10, badge, labelRow);
        tile.setAlignment(Pos.CENTER);
        tile.setPadding(new Insets(16, 8, 16, 8));
        tile.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tile, Priority.ALWAYS);

        applyTileStyle(tile, false);
        tile.setOnMouseEntered(e -> applyTileStyle(tile, true));
        tile.setOnMouseExited(e -> applyTileStyle(tile, false));
        tile.setOnMouseClicked(e -> openUrl(url));
        Tooltip.install(tile, new Tooltip(url));
        return tile;
    }

    private static void applyTileStyle(VBox tile, boolean hover) {
        tile.setStyle(
                "-fx-background-color: rgba(255,255,255," + (hover ? "0.06" : "0.03") + ");" +
                "-fx-background-radius: 12px;" +
                "-fx-border-color: " + (hover ? "-color-accent-emphasis" : "-color-border-default") + ";" +
                "-fx-border-radius: 12px;" +
                "-fx-border-width: 1px;" +
                "-fx-cursor: hand;");
    }

    private static void openUrl(String url) {
        Thread.ofVirtual().start(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                    return;
                }
            } catch (Exception ignored) {}
            try {
                ProcessBuilder pb = com.sun.jna.Platform.isWindows()
                        ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                        : new ProcessBuilder("xdg-open", url);
                pb.start();
            } catch (Exception ignored) {}
        });
    }

    // ── System detail helpers ─────────────────────────────────────────────────

    private static String displayOrUnknown(String s) {
        return (s != null && !s.isBlank()) ? s : "unknown";
    }

    private static Ikon osIcon() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return MaterialDesignM.MICROSOFT_WINDOWS;
        if (os.contains("linux")) return MaterialDesignL.LINUX;
        return MaterialDesignD.DESKTOP_TOWER_MONITOR;
    }

    private static String osDescription() {
        String name = System.getProperty("os.name", "unknown");
        String version = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        return (name + " " + version + " (" + arch + ")").trim();
    }

    private static String javaDescription() {
        String version = System.getProperty("java.version", "unknown");
        String vendor = System.getProperty("java.vendor", "");
        return vendor.isBlank() ? version : version + " (" + vendor + ")";
    }

    private static String javafxDescription() {
        String version = System.getProperty("javafx.runtime.version", "22");
        return version;
    }

    @Override public String name() { return "Info"; }
    @Override public String description() { return "App version and diagnostic information"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignI.INFORMATION_OUTLINE); }
    @Override public Node getPane() { return pane; }
}
