package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundboardSummary {
    private UUID soundboardId;
    private UUID serverId;
    private int slotIndex;
    private String name;
    private String emoji;
    private String fileName;
    private String fileType;
    private long fileSize;
    private UUID uploaderId;
}
