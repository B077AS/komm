package komm.model.dto.summary;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChannelUserSummary {

    private UUID userId;
    private String username;
    private byte[] avatar;
    private String avatarImageFormat;
    private boolean micEnabled;
    private boolean speakerEnabled;
    private boolean screenSharingEnabled;
    private boolean serverMicEnabled = true;
    private boolean serverSpeakerEnabled = true;
    private boolean pokesEnabled = true;
}