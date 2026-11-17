package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /v1/client/heartbeat}: a full snapshot of the secrets a workload holds.
 * <p>
 * Only {@code secrets} is required. {@code schemaVersion}, {@code trigger}, {@code sdk} and {@code sentAt}
 * are the v2 envelope; they are optional so the v1 body ({@code {"secrets": [...]}}) is still accepted.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @Min(1)
    private Integer schemaVersion;

    /** {@code PERIODIC} (the SDKs send no boot-time beat). */
    @Size(max = 32)
    private String trigger;

    @Valid
    private Sdk sdk;

    /** Epoch millis when the SDK built the beat. */
    @Min(0)
    private Long sentAt;

    @NotNull
    @Size(min = 1, max = 50, message = "Must contain between 1 and 50 entries")
    @Valid
    private List<HeldSecret> secrets;

    public HeartbeatRequest(List<HeldSecret> secrets) {
        this.secrets = secrets;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sdk {
        @Size(max = 32)
        private String language;

        @Size(max = 64)
        private String version;
    }
}
