package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A secret held by the calling workload, reported in a heartbeat (one SDK usage-ledger entry).
 * <p>
 * The first four fields are the original contract. The rest come from the v2 payload
 * ({@code POST /v1/client/heartbeat}) and are optional, so older SDKs that send only the
 * first four still validate.
 * </p>
 */
@Data
@NoArgsConstructor
public class HeldSecret {

    @NotBlank
    @Size(max = 255)
    private String projectId;

    @NotBlank
    @Size(max = 255)
    private String secretName;

    /**
     * The newest version of the secret the reporting instance holds. 0 when the secret has a refresh hook
     * but was never fetched and no update has been delivered yet.
     */
    @NotNull
    @Min(0)
    private Integer version;

    /**
     * Whether the reporting instance has a refresh hook registered for this secret. Defaults to false.
     */
    private boolean refreshHook;

    /** Cumulative successful / failed {@code getSecret} calls. */
    @Min(0)
    private Long getCount;

    @Min(0)
    private Long errorCount;

    /** Epoch millis of the first / last {@code getSecret} call; 0 when never fetched. */
    @Min(0)
    private Long firstAccessAt;

    @Min(0)
    private Long lastAccessAt;

    /** Newest version a refresh hook ran successfully for; 0 if none. */
    @Min(0)
    private Integer hookAppliedVersion;

    @Min(0)
    private Long hookFailureCount;

    /** Exception class and message of the last hook failure (truncated by the SDK); null if none. */
    @Size(max = 512)
    private String lastHookError;

    public HeldSecret(String projectId, String secretName, Integer version, boolean refreshHook) {
        this.projectId = projectId;
        this.secretName = secretName;
        this.version = version;
        this.refreshHook = refreshHook;
    }
}
