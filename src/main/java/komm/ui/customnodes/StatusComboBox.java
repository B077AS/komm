package komm.ui.customnodes;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import komm.AppState;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

public class StatusComboBox extends ComboBox<StatusComboBox.StatusOption> {

    public record StatusOption(UserStatus status, String label, Ikon icon, String cssClass) {
    }

    public static final StatusOption[] STATUS_OPTIONS = {
            new StatusOption(UserStatus.ONLINE, UserStatus.ONLINE.getValue(), MaterialDesignC.CHECKBOX_BLANK_CIRCLE, UserStatus.ONLINE.getCssClass()),
            new StatusOption(UserStatus.AWAY, UserStatus.AWAY.getValue(), MaterialDesignC.CHECKBOX_BLANK_CIRCLE, UserStatus.AWAY.getCssClass()),
            new StatusOption(UserStatus.DO_NOT_DISTURB, UserStatus.DO_NOT_DISTURB.getValue(), MaterialDesignC.CHECKBOX_BLANK_CIRCLE, UserStatus.DO_NOT_DISTURB.getCssClass()),
            new StatusOption(UserStatus.INVISIBLE, UserStatus.INVISIBLE.getValue(), MaterialDesignC.CHECKBOX_BLANK_CIRCLE, UserStatus.INVISIBLE.getCssClass())
    };

    private final boolean liveUpdate;
    private boolean suppressAction = false;

    public StatusComboBox(UserStatus initialStatus) {
        this(initialStatus, true);
    }

    public StatusComboBox(UserStatus initialStatus, boolean liveUpdate) {
        this.liveUpdate = liveUpdate;

        setMaxWidth(Double.MAX_VALUE);
        setFocusTraversable(false);

        for (StatusOption opt : STATUS_OPTIONS) getItems().add(opt);
        setCellFactory(lv -> new StatusCell());
        setButtonCell(new StatusCell());

        UserStatus toSelect = (initialStatus != null) ? initialStatus : UserStatus.ONLINE;
        for (StatusOption opt : STATUS_OPTIONS) {
            if (opt.status() == toSelect) {
                getSelectionModel().select(opt);
                break;
            }
        }
        if (getSelectionModel().isEmpty()) getSelectionModel().selectFirst();

        setOnAction(e -> {
            if (suppressAction) return;
            StatusOption selected = getValue();
            if (selected != null && liveUpdate) {
                AppState.requestStatusChange(selected.status());
            }
        });

        // When liveUpdate, mirror external status changes (from ToolBar popup or other combos)
        if (liveUpdate) {
            AppState.userStatusProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && newVal != getSelectedStatus()) {
                    Platform.runLater(() -> setStatusSilently(newVal));
                }
            });
        }
    }

    /** Update the displayed status without triggering the onAction handler or an API call. */
    public void setStatusSilently(UserStatus status) {
        suppressAction = true;
        try {
            for (StatusOption opt : STATUS_OPTIONS) {
                if (opt.status() == status) {
                    getSelectionModel().select(opt);
                    break;
                }
            }
        } finally {
            suppressAction = false;
        }
    }

    public UserStatus getSelectedStatus() {
        StatusOption selected = getValue();
        return (selected != null) ? selected.status() : UserStatus.ONLINE;
    }

    private static class StatusCell extends ListCell<StatusOption> {
        private String lastCssClass = null;

        @Override
        protected void updateItem(StatusOption item, boolean empty) {
            super.updateItem(item, empty);
            if (lastCssClass != null) {
                getStyleClass().remove(lastCssClass);
                lastCssClass = null;
            }
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                FontIcon icon = new FontIcon(item.icon());
                icon.getStyleClass().add(item.cssClass());
                getStyleClass().add(item.cssClass());
                lastCssClass = item.cssClass();
                setGraphic(icon);
                setText(item.label());
            }
        }
    }
}
