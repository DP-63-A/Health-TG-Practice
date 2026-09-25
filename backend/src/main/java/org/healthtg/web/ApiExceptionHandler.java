package org.healthtg.web;

import jakarta.servlet.http.HttpServletRequest;
import org.healthtg.auth.AccessDeniedException;
import org.healthtg.auth.AuthFailureException;
import org.healthtg.security.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AuthFailureException.class)
    ResponseEntity<ApiError> unauthorized(HttpServletRequest request) {
        return error(request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Telegram authentication failed");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(HttpServletRequest request) {
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson(HttpServletRequest request) {
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> dependencyUnavailable(HttpServletRequest request) {
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Required service is unavailable");
    }

    private static ResponseEntity<ApiError> error(HttpServletRequest request, HttpStatus status,
                                                   String code, String message) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId));
    }
}
