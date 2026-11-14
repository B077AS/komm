package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Replaces this user's choices on a poll with {@code optionIds} (empty = take the vote back). */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PollVotePayload {
    private UUID messageId;
    private List<UUID> optionIds;
}
