package com.flipkart.grayskull.exception;

import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrayskullErrorControllerTest {

    private final GrayskullErrorController errorController = new GrayskullErrorController(new DefaultErrorAttributes());

    @Test
    void handleErrorWithoutException() {
        HttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Forbidden error");

        ResponseEntity<ResponseTemplate<Void>> response = errorController.handleError(request);

        assertEquals(403, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Forbidden error", response.getBody().getMessage());
        assertEquals("Forbidden", response.getBody().getCode());
    }

    @Test
    void handleErrorWithStackTrace() {
        HttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Forbidden error");
        Exception exception = new Exception("Forbidden error");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);

        GrayskullErrorController spyController = spy(errorController);
        ResponseEntity<ResponseTemplate<Void>> response = spyController.handleError(request);

        assertEquals(403, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Forbidden error", response.getBody().getMessage());
        assertEquals("Forbidden", response.getBody().getCode());
        verify(spyController).logException(any());
    }
}