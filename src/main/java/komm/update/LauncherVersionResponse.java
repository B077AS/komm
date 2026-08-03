package komm.update;

import lombok.Data;

/**
 * Mirror of the hub's {@code LauncherVersionResponse} returned by
 * {@code GET /api/launcher/latest?os=windows|linux}.
 */
@Data
public class LauncherVersionResponse {
    private String version;
    private String downloadUrl;
    private String sha256;
}
