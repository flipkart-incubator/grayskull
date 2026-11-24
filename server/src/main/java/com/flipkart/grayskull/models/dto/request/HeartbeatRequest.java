package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /v1/heartbeat}: the secrets a workload holds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @NotNull
    @Size(min = 1, max = 50, message = "Must contain between 1 and 50 entries")
    @Valid
    private List<HeldSecret> secrets;
}
