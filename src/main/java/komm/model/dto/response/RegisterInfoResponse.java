package komm.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors GET /api/auth/register-info — whether the hub requires a beta key to register. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterInfoResponse {
    private boolean betaRequired;
}
