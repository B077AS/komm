package komm.ui.mention;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import lombok.Getter;

import java.util.UUID;

/**
 * Inline {@code @name} pill placed in a TextFlow. A Region + Text rather than a Label: its size
 * comes from the Text and the code-set padding, so it is right on the first layout pass (a Label
 * has no skin, and so no width, until CSS has been applied). Colours come from inline styles.
 */
public class MentionPill extends StackPane {

    @Getter
    private final UUID userId;
    private final Text text = new Text();

    MentionPill(UUID userId, Font font) {
        this.userId = userId;
        text.setFont(Font.font(font.getFamily(), FontWeight.BOLD, font.getSize()));
        getChildren().add(text);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0, 3, 0, 3));
        setMouseTransparent(true);
        setMinWidth(USE_PREF_SIZE);
        setMaxWidth(USE_PREF_SIZE);
    }

    void setLabel(String label) {
        text.setText(label);
    }

    public String getLabel() {
        return text.getText();
    }

    void setColors(String background, String foreground) {
        setStyle("-fx-background-radius: 3; -fx-background-color: " + background + ";");
        text.setStyle("-fx-fill: " + foreground + ";");
    }

    @Override
    public double getBaselineOffset() {
        return snappedTopInset() + text.getBaselineOffset();
    }
}
