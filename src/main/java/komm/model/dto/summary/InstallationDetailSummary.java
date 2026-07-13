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
public class InstallationDetailSummary {

    private UUID installationId;
    private String installationName;
    private int installationPort;
    private int signalPort;
    private int tcpPort;
    private int mediaPort;
    private int hostedServersCount;
    private InstallationSummary.InstallationStatus status;
    private String ipAddress;
}
