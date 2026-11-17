package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.HeartbeatRequest;
import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("HeartbeatController Unit Tests")
class HeartbeatControllerTest {

    private final HeartbeatService heartbeatService = mock(HeartbeatService.class);
    private final HeartbeatController controller = new HeartbeatController(heartbeatService);

    private static final String WORKLOAD = "v1::hyd-prod::payments::Deployment::payment-api";

    @Test
    @DisplayName("Records the heartbeat and returns 204 with no body")
    void recordHeartbeat_returnsNoContent() {
        List<HeldSecret> secrets = List.of(new HeldSecret("payments", "db-password", 7, 7));

        var response = controller.recordHeartbeat(WORKLOAD, "grayskull-java/0.3.0", new HeartbeatRequest(secrets));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(heartbeatService).recordHeartbeat(WORKLOAD, "grayskull-java/0.3.0", secrets);
    }
}
