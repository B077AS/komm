package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    private UUID partnerId;
    private String partnerUsername;
    private String lastMessageContent;
    private LocalDateTime lastMessageSentAt;
    private String lastMessageType;
    private boolean lastMessageIsOwn;
    private boolean hasUnread;
}
