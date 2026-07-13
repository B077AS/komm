package komm.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalSoundboardEntry {
    private UUID id;
    private int slotIndex;
    private String name;
    private String emoji;
    private String fileName;
    private String fileType;
    private long fileSize;
}
