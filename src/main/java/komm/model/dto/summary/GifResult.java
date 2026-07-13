package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GifResult {
    private String id;
    private String title;
    private String previewUrl;
    private String fullUrl;
    private String previewMp4Url;
    private String fullMp4Url;
    private int width;
    private int height;
}