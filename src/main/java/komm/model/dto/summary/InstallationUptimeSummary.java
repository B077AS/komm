package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Response of {@code GET /api/installations/{id}/uptime} - mirrors the hub's
 *  {@code InstallationUptimeSummary} field-for-field. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstallationUptimeSummary {
    private UUID installationId;
    private InstallationSummary.InstallationStatus currentStatus;
    private LocalDateTime lastSeenAt;
    private int rangeDays;
    private double uptimePercentage;
    private List<InstallationUptimeDayPoint> days;
    private List<InstallationStatusEventSummary> recentEvents;
}
