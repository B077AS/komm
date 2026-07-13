package komm.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuccessResponse {

    private String message;
    private Object data;
    private Integer status;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    public static ResponseEntity<SuccessResponse> of(String message) {
        return ResponseEntity.ok(
                SuccessResponse.builder()
                        .status(200)
                        .message(message)
                        .build()
        );
    }

    public static ResponseEntity<SuccessResponse> of(String message, Object data) {
        return ResponseEntity.ok(
                SuccessResponse.builder()
                        .status(200)
                        .message(message)
                        .data(data)
                        .build()
        );
    }
}