package komm.utils;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.Properties;

import com.google.gson.JsonObject;
import javafx.scene.paint.Color;
import komm.Launcher;

public class KommUtils {

	// Opens a URL in the system browser off the FX thread. Desktop.browse()
	// relies on libgnome on Linux and is frequently unsupported/broken there,
	// so fall back to shelling out to the native URL handler when it fails.
	public static void openUrl(String url) {
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

	public static Color generateColorFromName(String name) {
		int hash = name.hashCode();
		double hue = Math.abs(hash % 360) / 360.0;
		return Color.hsb(hue * 360, 0.85, 0.9);
	}
	
	public static String toHexString(Color color) {
		return String.format("#%02X%02X%02X",
				(int) (color.getRed() * 255),
				(int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}
	
	public static void saveRefreshToken(String refreshToken) {
        Properties props = new Properties();
        props.setProperty("refresh_token", refreshToken);

        File file = new File(Launcher.getCredentialsFile().toString());
        
        // Create the file if it does not exist
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                System.err.println("Failed to create token file: " + e.getMessage());
                return;
            }
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "Komm Session Token");
        } catch (IOException e) {
            System.err.println("Failed to save refresh token: " + e.getMessage());
        }
    }
	
	public static void clearSavedToken() {
        File tokenFile = new File(Launcher.getCredentialsFile().toString());
        if (tokenFile.exists()) {
            tokenFile.delete();
        }
    }

    public static String getStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
