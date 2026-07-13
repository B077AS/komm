package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInstallationRequest {

    private String installationName;
    private int installationPort;
    private int signalPort;
    private int tcpPort;
    private int mediaPort;
    private String installationCsr;
}
