package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

@ControllerAdvice(assignableTypes = SecretController.class)
public class SecretNameAccessLogAdvice extends RequestBodyAdviceAdapter {


    public static final String SECRET_NAME_ATTRIBUTE = "secret_name";

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return CreateSecretRequest.class.equals(targetType);
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (body instanceof CreateSecretRequest request) {
            publish(request.getName());
        }
        return body;
    }

    private static void publish(String secretName) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && secretName != null && !secretName.isBlank()) {
            attributes.setAttribute(SECRET_NAME_ATTRIBUTE, secretName, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
