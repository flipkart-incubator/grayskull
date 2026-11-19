package com.flipkart.grayskull.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.audit.outbox.OutboxWriteAuditRecorder;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AuditAspect Unit Tests")
class AuditAspectTest {

    private OutboxWriteAuditRecorder writeAuditRecorder;
    private RequestUtils requestUtils;
    private AuditAspect auditAspect;
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        writeAuditRecorder = mock(OutboxWriteAuditRecorder.class);
        requestUtils = mock(RequestUtils.class);
        auditAspect = new AuditAspect(writeAuditRecorder, requestUtils);

        securityContext = mock(SecurityContext.class);
        SecurityContextHolder.setContext(securityContext);
    }

    @Audit(action = AuditAction.READ_SECRET)
    public void dummyMethod(String projectId) {
    }

    @Test
    @DisplayName("auditSuccess should save AuditEntry with additional metadata")
    void auditSuccess_shouldSaveAuditEntry_withAdditionalMetadata() throws NoSuchMethodException {
        // Setup JoinPoint
        JoinPoint joinPoint = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        Method dummyMethod = this.getClass().getMethod("dummyMethod", String.class);
        when(signature.getMethod()).thenReturn(dummyMethod);
        when(signature.getParameterNames()).thenReturn(new String[]{"projectId"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{"project123"});

        // Setup RequestUtils
        Map<String, String> additionalMetadata = new HashMap<>();
        additionalMetadata.put("customKey", "customValue");
        when(requestUtils.getAdditionalMetadata()).thenReturn(additionalMetadata);
        Map<String, String> ips = new HashMap<>();
        ips.put("Remote-Conn-Addr", "127.0.0.1");
        when(requestUtils.getRemoteIPs()).thenReturn(ips);

        // Setup Security Context
        GrayskullAuthentication authentication = mock(GrayskullAuthentication.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getName()).thenReturn("user1");
        when(authentication.getActor()).thenReturn("actor1");

        // Execute
        auditAspect.auditSuccess(joinPoint, null);

        // Verify
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writeAuditRecorder).record(captor.capture());

        AuditEntry savedEntity = captor.getValue();
        assertThat(savedEntity.getProjectId()).isEqualTo("project123");
        assertThat(savedEntity.getAction()).isEqualTo(AuditAction.READ_SECRET.name());
        assertThat(savedEntity.getUserId()).isEqualTo("user1");
        assertThat(savedEntity.getActorId()).isEqualTo("actor1");
        assertThat(savedEntity.getIps()).containsEntry("Remote-Conn-Addr", "127.0.0.1");
    }

    @Test
    @DisplayName("auditSuccess should save AuditEntry with default user and null actor when context is empty")
    void auditSuccess_shouldSaveWithDefaultUser() throws NoSuchMethodException {
        // Setup JoinPoint
        JoinPoint joinPoint = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        Method dummyMethod = this.getClass().getMethod("dummyMethod", String.class);
        when(signature.getMethod()).thenReturn(dummyMethod);
        when(signature.getParameterNames()).thenReturn(new String[]{"projectId"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{"project123"});

        // Setup RequestUtils
        when(requestUtils.getAdditionalMetadata()).thenReturn(new HashMap<>());
        Map<String, String> ips = new HashMap<>();
        ips.put("Remote-Conn-Addr", "127.0.0.1");
        when(requestUtils.getRemoteIPs()).thenReturn(ips);

        // Empty Security Context
        when(securityContext.getAuthentication()).thenReturn(null);

        // Execute
        auditAspect.auditSuccess(joinPoint, null);

        // Verify
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writeAuditRecorder).record(captor.capture());

        AuditEntry savedEntity = captor.getValue();
        assertThat(savedEntity.getUserId()).isEqualTo("system");
        assertThat(savedEntity.getActorId()).isNull();
    }
}
