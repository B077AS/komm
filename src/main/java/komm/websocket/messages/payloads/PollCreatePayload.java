package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The {@code poll} part of a {@code CHANNEL_MESSAGE_SENT} with {@code messageType = POLL}; the question is {@code content}. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PollCreatePayload {
    private List<String> options;
    private boolean allowMultiple;
    private boolean anonymous;
    /** Minutes until the poll closes; null = no end. */
    private Integer durationMinutes;
}
