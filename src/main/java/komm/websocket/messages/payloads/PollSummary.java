package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Current state of a channel poll, as sent by the installation (history, new message, {@code CHANNEL_POLL_UPDATED}). */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PollSummary {
    private boolean allowMultiple;
    private boolean anonymous;
    private LocalDateTime closesAt;
    private boolean closed;
    /** Distinct users who voted. */
    private int totalVoters;
    private List<Option> options;
    /** This user's own choices; null in broadcasts, meaning "keep what you have". */
    private List<UUID> myOptionIds;

    /** Closed by hand/scheduler, or its end time has passed locally (before the server's sweep). */
    public boolean isEnded() {
        return closed || (closesAt != null && !LocalDateTime.now().isBefore(closesAt));
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Option {
        private UUID optionId;
        private String text;
        private int votes;
        /** Who picked this option; null for anonymous polls. */
        private List<UUID> voterIds;
    }
}
