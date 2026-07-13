package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Edit an existing server soundboard sound. Name and emoji are full replacement
 * values (emoji null clears it). If {@code contentBase64} is present the audio
 * file is replaced too — the server then assigns a new soundboard id.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundboardEditRequest {
    private String name;
    private String emoji;
    private String fileName;
    private String fileType;
    private String contentBase64;
}
