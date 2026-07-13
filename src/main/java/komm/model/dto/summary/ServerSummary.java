package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerSummary {

    private UUID serverId;
    private String serverName;
    private String description;
    private UUID installationId;
    private String ipAddress;
    private Integer port;
    private Integer signalPort;
    private String avatar;
    private String avatarImageFormat;
    private byte[] avatarBytes;
    private Role role;
    private LocalDateTime joinedAt;
    private Integer displayOrder;
    private int totalMembers;
    private UUID ownerId;
    private String ownerUsername;
    private int activeUsers;
    private int textChannelCount;
    private int voiceChannelCount;
    private InstallationSummary.InstallationStatus status;
    private Integer defaultChannelPanelWidth;
    @Builder.Default
    private boolean channelNotificationsEnabled = true;
    private List<String> effectivePermissions;

    public enum Role {
        OWNER,
        ADMIN,
        MODERATOR,
        MEMBER;
    }
}