package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One secret in a heartbeat: the version held and its refresh hook's applied version. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeldSecret {

    @NotBlank
    @Size(max = 255)
    private String projectId;

    @NotBlank
    @Size(max = 255)
    private String secretName;

    /** Newest version held; 0 for a hook-only secret not yet fetched. */
    @NotNull
    @Min(0)
    private Integer version;

    /** Newest version a refresh hook succeeded for; null when no hook is registered. */
    @Min(0)
    private Integer refreshHookAppliedVersion;
}
