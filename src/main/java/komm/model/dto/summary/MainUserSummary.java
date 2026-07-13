package komm.model.dto.summary;

import java.util.List;
import java.util.UUID;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MainUserSummary {
	
	private UUID userId;
    private String username;
    private String email;
    private byte[] avatar;
    private UserStatus status;
    private String statusMessage;
    private String statusEmoji;
    private String avatarImageFormat;
    private boolean micEnabled;
    private boolean speakerEnabled;
    private DmPrivacy dmPrivacy;
    private List<BadgeSummary> badges;

    /** Controls who may send this user a direct message. */
    @Getter
    public enum DmPrivacy {
        EVERYONE("Everyone"),
        FRIENDS_ONLY("Only friends"),
        NONE("No one");

        private final String label;

        DmPrivacy(String label) {
            this.label = label;
        }
    }

    @Getter
    public enum UserStatus {
        ONLINE("Online", "status-online"),
        AWAY("Away", "status-away"),
        DO_NOT_DISTURB("Do Not Disturb", "status-dnd"),
        INVISIBLE("Invisible", "status-invisible"),
        OFFLINE("Offline", "status-invisible"),
        UNKNOWN("Unknown", "status-invisible");

        private final String value;
        private final String cssClass;

        UserStatus(String value, String cssClass) {
            this.value = value;
            this.cssClass = cssClass;
        }
    }
}