package komm.ui.emojis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmojiReactionEntry {
    private String emojiChar;
    private int count;
    private boolean selfReacted;

}
