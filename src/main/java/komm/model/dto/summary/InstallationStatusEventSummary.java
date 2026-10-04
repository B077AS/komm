package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One row of the recent-events / incident log. Mirrors the hub's
 *  {@code InstallationStatusEventSummary} response DTO field-for-field. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstallationStatusEventSummary {
    private InstallationSummary.InstallationStatus previousStatus;
    private InstallationSummary.InstallationStatus status;
    private Reason reason;
    private LocalDateTime occurredAt;

    public enum Reason {
        CONNECTED, VALIDATED, WS_CLOSED, HEARTBEAT_TIMEOUT, HUB_SHUTDOWN
    }
}
