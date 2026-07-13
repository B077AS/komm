package komm.ui.avatar;

import javafx.scene.paint.Color;

public class AvatarColor {

    private static final String[] PALETTE = {
        "#9580ff",  // purple
        "#c0392b",  // red
        "#c2185b",  // pink
        "#4527a0",  // indigo
        "#1565c0",  // blue
        "#00695c",  // teal
        "#2e7d32",  // green
        "#558b2f",  // olive green
        "#e65100",  // deep orange
        "#827717",  // dark amber
        "#5d4037",  // brown
        "#78281f",  // maroon
    };

    private AvatarColor() {}

    public static String forName(String name) {
        if (name == null || name.isEmpty()) return PALETTE[0];
        int hash = 0;
        for (char c : name.toCharArray()) hash = 31 * hash + c;
        return PALETTE[Math.abs(hash) % PALETTE.length];
    }

    public static Color forNameJfx(String name) {
        return Color.web(forName(name));
    }
}
