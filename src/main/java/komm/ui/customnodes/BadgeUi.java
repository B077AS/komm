package komm.ui.customnodes;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import komm.model.dto.summary.BadgeSummary;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders hub profile badges as icon pills, reusing the icon-literal +
 * hex-color conventions from channel icons and custom roles. One place builds
 * every badge pill so profile modal, popup and settings all look identical.
 */
public final class BadgeUi {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private BadgeUi() {}

    /** A single badge pill: colored icon + name, description as tooltip. */
    public static HBox pill(BadgeSummary badge) {
        FontIcon icon = resolveIcon(badge.getIcon());
        IconColorUtil.apply(icon, IconColorUtil.safeRoleColor(badge.getColor()), 14);

        Label lbl = new Label(badge.getName());
        lbl.setStyle("-fx-font-size: 11px;");

        HBox pill = new HBox(6, icon, lbl);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setPadding(new Insets(4, 10, 4, 8));
        pill.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 6;");

        StringBuilder tip = new StringBuilder();
        if (badge.getDescription() != null && !badge.getDescription().isBlank()) {
            tip.append(badge.getDescription());
        }
        if (badge.getAwardedAt() != null) {
            if (!tip.isEmpty()) tip.append("\n");
            tip.append("Awarded ").append(badge.getAwardedAt().format(DATE_FMT));
        }
        if (!tip.isEmpty()) {
            Tooltip.install(pill, new Tooltip(tip.toString()));
        }
        return pill;
    }

    /** A wrapping row of badge pills; returns null when there are no badges. */
    public static FlowPane flow(List<BadgeSummary> badges) {
        if (badges == null || badges.isEmpty()) return null;
        FlowPane flow = new FlowPane(6, 6);
        for (BadgeSummary badge : badges) {
            flow.getChildren().add(pill(badge));
        }
        return flow;
    }

    /** Resolves a badge's icon literal, falling back to a generic seal. */
    public static FontIcon resolveIcon(String literal) {
        if (literal != null && !literal.isBlank()) {
            try {
                return new FontIcon(literal);
            } catch (Exception ignored) {
                // Unknown literal (older client than hub) — fall back below
            }
        }
        return new FontIcon(MaterialDesignS.SEAL);
    }
}
