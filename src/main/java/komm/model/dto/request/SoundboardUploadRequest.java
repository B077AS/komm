package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundboardUploadRequest {
    private int slotIndex;
    private String name;
    private String emoji;
    private String fileName;
    private String fileType;
    private String contentBase64;
}
