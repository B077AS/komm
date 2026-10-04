package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** One bar in the status-page uptime strip - a single UTC calendar day. Mirrors the hub's
 *  {@code InstallationUptimeDayPoint} response DTO field-for-field. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstallationUptimeDayPoint {
    private LocalDate day;
    private long uptimeSeconds;
    private long downtimeSeconds;
    private long unknownSeconds;
    private double uptimePercentage;
    private int outageCount;
    private long longestOutageSeconds;
    private boolean partial;
}
