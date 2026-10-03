package komm.ui.cards;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fleet-wide status sidebar docked next to the installation card list - same status-page idea
 * as {@link InstallationCard}, just rolled up across every installation the user owns or belongs
 * to. Exists mainly so the Installations view still has something meaningful to show (and the
 * page doesn't look half-empty) when there are only one or two installations, and so it stays
 * put as a point of reference while the card list underneath it scrolls independently.
 * <p>
 * Each {@link InstallationCard} reports its own async-loaded uptime percentage back here via
 * {@link #reportUptime} instead of this class making its own redundant network calls.
 */
public class InstallationFleetSummary extends VBox {

    private static final int ICON_SIZE = 28;

    private HBox statusRow;
    private FontIcon statusIcon;
    private Label headlineLabel;
    private Label installationsValue;
    private Label serversValue;
    private Label uptimeValue;

    private final Map<UUID, Double> uptimeReports = new HashMap<>();

    public InstallationFleetSummary() {
        getStyleClass().add("installation-status-card");
        setMaxWidth(Double.MAX_VALUE);
        setSpacing(16);
        setPadding(new Insets(18));
        build();
    }

    private void build() {
        Label sectionLbl = new Label("FLEET STATUS");
        sectionLbl.setStyle("-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");

        statusIcon = IconColorUtil.colored(MaterialDesignC.CHECK_CIRCLE_OUTLINE, "-color-accent-emphasis", ICON_SIZE);
        headlineLabel = new Label("All systems operational");
        headlineLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        headlineLabel.setWrapText(true);
        HBox.setHgrow(headlineLabel, Priority.ALWAYS);

        statusRow = new HBox(10, statusIcon, headlineLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Separator divider = new Separator();

        installationsValue = new Label("0");
        serversValue = new Label("0");
        uptimeValue = new Label("—");

        VBox stats = new VBox(12,
                statRow("Installations", installationsValue),
                statRow("Servers hosted", serversValue),
                statRow("Avg uptime (90d)", uptimeValue)
        );

        getChildren().addAll(sectionLbl, statusRow, divider, stats);
    }

    private HBox statRow(String labelText, Label value) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");
        value.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(label, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Call whenever the installation list (re)loads - resets the uptime aggregation. */
    public void setInstallations(List<InstallationSummary> installations) {
        uptimeReports.clear();

        long online = installations.stream()
                .filter(i -> i.getStatus() == InstallationSummary.InstallationStatus.ONLINE).count();
        long offline = installations.stream()
                .filter(i -> i.getStatus() == InstallationSummary.InstallationStatus.OFFLINE).count();
        long notVerified = installations.stream()
                .filter(i -> i.getStatus() == InstallationSummary.InstallationStatus.NOT_VERIFIED).count();
        int servers = installations.stream().mapToInt(InstallationSummary::getHostedServersCount).sum();

        installationsValue.setText(String.valueOf(installations.size()));
        serversValue.setText(String.valueOf(servers));
        uptimeValue.setText("—");

        if (offline > 0) {
            setHeadline(offline + (offline == 1 ? " installation is offline" : " installations are offline"),
                    MaterialDesignA.ALERT_CIRCLE_OUTLINE, "-color-danger-emphasis");
        } else if (online == 0 && notVerified > 0) {
            setHeadline("Awaiting setup — no installations verified yet",
                    MaterialDesignA.ALERT_OUTLINE, "-color-warning-emphasis");
        } else {
            setHeadline("All systems operational", MaterialDesignC.CHECK_CIRCLE_OUTLINE, "-color-accent-emphasis");
        }
    }

    private void setHeadline(String text, Ikon icon, String color) {
        headlineLabel.setText(text);
        statusRow.getChildren().remove(statusIcon);
        statusIcon = IconColorUtil.colored(icon, color, ICON_SIZE);
        statusRow.getChildren().add(0, statusIcon);
    }

    /** Called by each card once its own async uptime fetch resolves (or fails, with {@code null}). */
    public void reportUptime(UUID installationId, Double uptimePercentage) {
        if (uptimePercentage != null) uptimeReports.put(installationId, uptimePercentage);
        if (uptimeReports.isEmpty()) {
            uptimeValue.setText("—");
            return;
        }
        double avg = uptimeReports.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        uptimeValue.setText(String.format("%.2f%%", avg));
    }
}
