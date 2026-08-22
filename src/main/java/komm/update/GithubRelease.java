package komm.update;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Minimal mirror of the fields this client reads off GitHub's
 * {@code GET /repos/{owner}/{repo}/releases/latest} response. GitHub computes
 * and returns a {@code sha256:<hex>} digest for every uploaded release asset,
 * which is what {@link LauncherUpdateService} verifies the download against —
 * no separate checksum file or hub involved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GithubRelease {

    @SerializedName("tag_name")
    private String tagName;
    private List<GithubAsset> assets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GithubAsset {
        private String name;
        @SerializedName("browser_download_url")
        private String browserDownloadUrl;
        private String digest;
    }

    /** The matching asset, or null if this release doesn't have one by that name yet. */
    public GithubAsset findAsset(String name) {
        if (assets == null) return null;
        return assets.stream().filter(a -> name.equals(a.getName())).findFirst().orElse(null);
    }
}
