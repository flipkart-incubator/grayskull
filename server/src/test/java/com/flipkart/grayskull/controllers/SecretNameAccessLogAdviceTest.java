package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.service.interfaces.SecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

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

    private static MethodParameter bodyParameter(Class<?> controller, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = controller.getDeclaredMethod(methodName, parameterTypes);
        return new MethodParameter(method, method.getParameterCount() - 1);
    }

    @Test
    @DisplayName("supports should apply only to the create secret handler on SecretController")
    void supports_onlyCreateSecretHandler() throws NoSuchMethodException {
        MethodParameter create =
                bodyParameter(SecretController.class, "createSecret", String.class, CreateSecretRequest.class);
        MethodParameter upgrade = bodyParameter(SecretController.class, "upgradeSecretData", String.class,
                String.class, UpgradeSecretDataRequest.class);
        MethodParameter outsideController =
                bodyParameter(SecretService.class, "createSecret", String.class, CreateSecretRequest.class);

        assertThat(advice.supports(create, CreateSecretRequest.class, null)).isTrue();
        assertThat(advice.supports(upgrade, UpgradeSecretDataRequest.class, null)).isFalse();
        assertThat(advice.supports(create, String.class, null)).isFalse();
        assertThat(advice.supports(outsideController, CreateSecretRequest.class, null)).isFalse();
        assertThat(advice.supports(null, CreateSecretRequest.class, null)).isFalse();
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
    @DisplayName("afterBodyRead should leave the attribute unset when the name is null")
    void afterBodyRead_ignoresNullName() {
        MockHttpServletRequest request = bindRequest();
        CreateSecretRequest body = new CreateSecretRequest();

        advice.afterBodyRead(body, null, null, CreateSecretRequest.class, null);

        assertThat(request.getAttribute(SecretNameAccessLogAdvice.SECRET_NAME_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("afterBodyRead should ignore a body that is not a create secret request")
    void afterBodyRead_ignoresUnrelatedBody() {
        MockHttpServletRequest request = bindRequest();
        Object body = "not a create secret request";

        assertThat(advice.afterBodyRead(body, null, null, String.class, null)).isSameAs(body);
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
