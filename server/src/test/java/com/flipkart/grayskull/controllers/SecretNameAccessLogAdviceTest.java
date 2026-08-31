package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretNameAccessLogAdvice Unit Tests")
class SecretNameAccessLogAdviceTest {

    private final SecretNameAccessLogAdvice advice = new SecretNameAccessLogAdvice();

    private MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("supports should apply only to the create secret body")
    void supports_onlyCreateSecretRequest() {
        assertThat(advice.supports(null, CreateSecretRequest.class, null)).isTrue();
        assertThat(advice.supports(null, UpgradeSecretDataRequest.class, null)).isFalse();
        assertThat(advice.supports(null, String.class, null)).isFalse();
    }

    @Test
    @DisplayName("afterBodyRead should publish the secret name for the access log")
    void afterBodyRead_publishesSecretName() {
        MockHttpServletRequest request = bindRequest();
        CreateSecretRequest body = new CreateSecretRequest();
        body.setName("db-password");

        Object returned = advice.afterBodyRead(body, null, null, CreateSecretRequest.class, null);

        assertThat(returned).isSameAs(body);
        assertThat(request.getAttribute(SecretNameAccessLogAdvice.SECRET_NAME_ATTRIBUTE)).isEqualTo("db-password");
    }

    @Test
    @DisplayName("afterBodyRead should leave the attribute unset for a blank or missing name")
    void afterBodyRead_ignoresBlankName() {
        MockHttpServletRequest request = bindRequest();
        CreateSecretRequest body = new CreateSecretRequest();
        body.setName("   ");

        advice.afterBodyRead(body, null, null, CreateSecretRequest.class, null);

        assertThat(request.getAttribute(SecretNameAccessLogAdvice.SECRET_NAME_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("afterBodyRead should not fail when there is no bound request")
    void afterBodyRead_withoutBoundRequest() {
        RequestContextHolder.resetRequestAttributes();
        CreateSecretRequest body = new CreateSecretRequest();
        body.setName("db-password");

        assertThat(advice.afterBodyRead(body, null, null, CreateSecretRequest.class, null)).isSameAs(body);
    }
}
