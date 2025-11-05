package com.flipkart.grayskull.exception;

import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.models.dto.response.Violation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Global exception handler for the application.
 * This class provides centralized exception handling across all @RequestMapping
 * methods.
 * It ensures that all error responses are consistent and follow the
 * application's standard error format.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Handles validation exceptions for request parameters and bodies.
     * Triggered when a method parameter annotated with @Valid fails validation,
     * method argument types don't match, or request body validation fails.
     * 
     * This handler provides structured field-level violation information in the response.
     *
     * @param ex      The ConstraintViolationException that was thrown.
     * @param request The current web request.
     * @return A ResponseEntity containing a standardized error response with structured
     *         violations and a 400 Bad Request status.
     */
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ResponseTemplate<Void>> handleValidationException(Exception ex, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        
        if (ex instanceof ConstraintViolationException constraintEx) {
            violations = constraintEx.getConstraintViolations().stream()
                    .map(cv -> new Violation(cv.getPropertyPath().toString(), cv.getMessage()))
                    .collect(Collectors.toList());
        } else if (ex instanceof MethodArgumentTypeMismatchException typeMismatchEx) {
            String message = String.format("Could not convert value '%s' to type '%s'",
                    typeMismatchEx.getValue(),
                    Objects.requireNonNull(typeMismatchEx.getRequiredType()).getSimpleName());
            violations.add(new Violation(typeMismatchEx.getName(), message));
        }
        
        ResponseTemplate<Void> errorResponse = ResponseTemplate.validationError(
                "Validation failed",
                HttpStatus.BAD_REQUEST.name(),
                violations);
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles business logic exceptions that are mapped to specific HTTP statuses.
     * This is the primary handler for exceptions thrown from the service layer.
     *
     * @param ex      The ResponseStatusException that was thrown.
     * @param request The current web request.
     * @return A ResponseEntity with the status code and message from the exception.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ResponseTemplate<Void>> handleResponseStatusException(ResponseStatusException ex,
            WebRequest request) {
        String errorCode = "";
        if (ex.getStatusCode() instanceof HttpStatus) {
            errorCode = ((HttpStatus) ex.getStatusCode()).name();
        } else {
            errorCode = String.valueOf(ex.getStatusCode().value());
        }
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error(ex.getReason(), errorCode);
        return new ResponseEntity<>(errorResponse, ex.getStatusCode());
    }

    /**
     * Handles authorization failures.
     * Triggered when a user is authenticated but not authorized to perform an
     * action.
     *
     * @param ex      The AccessDeniedException that was thrown.
     * @param request The current web request.
     * @return A ResponseEntity containing a standardized error response with a 403
     *         Forbidden status.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseTemplate<Void>> handleAccessDeniedException(AccessDeniedException ex,
            WebRequest request) {
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error(ex.getMessage(), HttpStatus.FORBIDDEN.name());
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles optimistic locking failures when concurrent updates occur.
     * Triggered when the @Version field in an entity doesn't match during an
     * update,
     * indicating that another request modified the resource after it was read.
     * This ensures data integrity in concurrent environments with multiple
     * application instances.
     *
     * @param ex      The OptimisticLockingFailureException that was thrown.
     * @param request The current web request.
     * @return A ResponseEntity containing a standardized error response with a 409
     *         Conflict status.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ResponseTemplate<Void>> handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
            WebRequest request) {
        log.warn("Optimistic locking failure detected - concurrent modification: {}", ex.getMessage());
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error(
                "The resource was modified by another request. Please refresh and try again.",
                HttpStatus.CONFLICT.name());
        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }


    /**
     * A catch-all handler for any other unhandled exceptions.
     * This ensures that no exception goes unhandled and prevents stack traces from
     * being exposed to the client.
     *
     * @param ex      The exception that was thrown.
     * @param request The current web request.
     * @return A ResponseEntity containing a standardized error response with a 500
     *         Internal Server Error status.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseTemplate<Void>> handleAllExceptions(Exception ex, WebRequest request) {
        log.error("An unexpected error occurred", ex);
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error("An unexpected internal server error occurred.",
                HttpStatus.INTERNAL_SERVER_ERROR.name());
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = "Request method '" + ex.getMethod() + "' not supported. Supported methods are "
                + ex.getSupportedHttpMethods();
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error(message, HttpStatus.METHOD_NOT_ALLOWED.name());
        return new ResponseEntity<>(errorResponse, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseTemplate<Void> errorResponse = ResponseTemplate.error("Malformed JSON request",
                HttpStatus.UNPROCESSABLE_ENTITY.name());
        return new ResponseEntity<>(errorResponse, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new Violation(fieldError.getField(), fieldError.getDefaultMessage()))
                .collect(Collectors.toList());
        
        ResponseTemplate<Void> errorResponse = ResponseTemplate.validationError(
                "Validation failed",
                HttpStatus.BAD_REQUEST.name(),
                violations);
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }
}
