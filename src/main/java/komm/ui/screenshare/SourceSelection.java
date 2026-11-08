package komm.ui.screenshare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceSelection {
    public long sourceId;
    public boolean isWindow;
    public ScreenShareQuality quality;
    public boolean audioEnabled;
    /** Requested codec; resolved against this machine's encoders when the share starts. */
    @Builder.Default
    public komm.webrtc.VideoCodecChoice codec = komm.webrtc.VideoCodecChoice.AUTO;
}