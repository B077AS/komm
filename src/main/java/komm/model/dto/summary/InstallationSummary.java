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
public class InstallationSummary {

    private UUID installationId;
    private String installationName;
    private int installationPort;
    private int hostedServersCount;
    private InstallationStatus status;
    private String ipAddress;
    private InstallationRole role;

    public enum InstallationStatus {
        NOT_VERIFIED,
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    public enum InstallationRole {
        OWNER, MEMBER
    }

    public boolean isOwner() {
        return role == InstallationRole.OWNER;
    }
}