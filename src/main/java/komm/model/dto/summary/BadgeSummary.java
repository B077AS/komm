package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A profile badge as returned by the hub. {@code icon} is an Ikonli
 * materialdesign2 literal (e.g. "mdi2s-shield-crown") rendered with FontIcon,
 * {@code color} a #RRGGBB hex like custom role colors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeSummary {

    private UUID badgeId;
    private String code;
    private String name;
    private String description;
    private String icon;
    private String color;
    private String type;
    private int position;
    private LocalDateTime awardedAt;
}
