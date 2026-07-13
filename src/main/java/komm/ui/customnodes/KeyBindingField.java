package komm.ui.customnodes;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import komm.App;
import komm.utils.GlobalHotkeyManager;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.util.Arrays;

/**
 * Compact chip-style key binding widget.
 * Renders each key as a keycap badge with "+" separators.
 * Click to enter recording mode; Escape cancels.
 */
public class KeyBindingField extends HBox {

    private static final String CHIP =
            "-fx-background-color: -color-bg-default;" +
            " -fx-border-color: -color-border-default;" +
            " -fx-border-radius: 5; -fx-background-radius: 5;" +
            " -fx-padding: 5 12 5 12; -fx-font-size: 13px;";

    private static final String CHIP_EMPTY =
            "-fx-border-color: -color-border-default;" +
            " -fx-border-style: segments(4, 3);" +
            " -fx-border-radius: 5; -fx-background-radius: 5;" +
            " -fx-padding: 5 12 5 12; -fx-font-size: 13px;" +
            " -fx-text-fill: -color-fg-subtle;";

    private static final String CHIP_RECORDING =
            "-fx-background-color: -color-accent-subtle;" +
            " -fx-border-color: -color-accent-emphasis;" +
            " -fx-border-radius: 5; -fx-background-radius: 5;" +
            " -fx-padding: 5 14 5 14; -fx-font-size: 13px;" +
            " -fx-text-fill: -color-accent-fg;";

    private int[] keys = new int[0];
    private final HBox chipsBox;
    private final Button clearButton;
    private GlobalHotkeyManager.RecordingSession activeSession;
    private Runnable onChange;

    public KeyBindingField() {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);

        chipsBox = new HBox(4);
        chipsBox.setAlignment(Pos.CENTER_LEFT);
        chipsBox.setStyle("-fx-cursor: hand;");

        clearButton = new Button(null, new FontIcon(MaterialDesignT.TRASH_CAN_OUTLINE));
        clearButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        clearButton.setStyle("-fx-padding: 4px;");
        clearButton.setFocusTraversable(false);
        clearButton.setVisible(false);
        clearButton.setManaged(false);
        clearButton.setOnAction(e -> {
            if (activeSession != null) {
                activeSession.cancel();
                activeSession = null;
            }
            setKeys(new int[0]);
        });

        chipsBox.setOnMouseClicked(e -> {
            if (activeSession != null) return;
            startRecording();
        });

        getChildren().addAll(chipsBox, clearButton);
        updateDisplay();
    }

    public void setOnChange(Runnable onChange) { this.onChange = onChange; }

    public void setKeys(int[] keys) {
        this.keys = keys != null ? keys : new int[0];
        updateDisplay();
        if (onChange != null) onChange.run();
    }

    public int[] getKeys() { return keys; }

    private void updateDisplay() {
        chipsBox.getChildren().clear();
        if (keys == null || keys.length == 0) {
            Label placeholder = new Label("Not set");
            placeholder.setStyle(CHIP_EMPTY);
            chipsBox.getChildren().add(placeholder);
            clearButton.setVisible(false);
            clearButton.setManaged(false);
        } else {
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) {
                    Label sep = new Label("+");
                    sep.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 2 0 2;");
                    chipsBox.getChildren().add(sep);
                }
                Label chip = new Label(GlobalHotkeyManager.keyDisplayName(keys[i]));
                chip.setStyle(CHIP);
                chipsBox.getChildren().add(chip);
            }
            clearButton.setVisible(true);
            clearButton.setManaged(true);
        }
    }

    private void startRecording() {
        if (!GlobalHotkeyManager.getInstance().isStarted()) return;
        chipsBox.getChildren().clear();
        Label rec = new Label("Press shortcut…");
        rec.setStyle(CHIP_RECORDING);
        chipsBox.getChildren().add(rec);
        clearButton.setVisible(false);
        clearButton.setManaged(false);

        App.getModalPane().setPersistent(true);
        activeSession = GlobalHotkeyManager.getInstance().startRecording(captured -> {
            Platform.runLater(() -> {
                activeSession = null;
                if (captured != null && captured.length > 0) {
                    setKeys(captured);
                } else {
                    updateDisplay();
                }
                Platform.runLater(() -> App.getModalPane().setPersistent(false));
            });
        });
    }

    public void cancelRecording() {
        if (activeSession != null) {
            activeSession.cancel();
            activeSession = null;
        }
        App.getModalPane().setPersistent(false);
        updateDisplay();
    }

    public boolean keysEqual(int[] other) {
        return Arrays.equals(keys, other);
    }
}
