package komm.model.dto.summary;

import lombok.Data;

import java.util.UUID;

@Data
public class QueuedTrack {
    private String youtubeUrl;
    private String title;
    private String durationFormatted;
    private UUID requestedBy;
}
