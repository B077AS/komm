package komm.model.dto.response;

import komm.model.dto.summary.ServerMemberSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPageResponse {
    private List<ServerMemberSummary> members;
    private long total;
    private int page;
    private int size;
}
