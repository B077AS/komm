package komm.websocket.messages.payloads;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PingPayload {
    private long timestamp;
    /** Client's last self-measured RTT in ms — null on first ping before any PONG is received. */
    private Integer lastRttMs;
    /** Client's self-measured missed-heartbeat rate (0-100) over its local window — null until enough ticks have elapsed. */
    private Integer lastLossPct;
}
