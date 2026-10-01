package org.healthtg.web;

import jakarta.servlet.http.HttpServletRequest;
import org.healthtg.auth.AccessDeniedException;
import org.healthtg.auth.AuthFailureException;
import org.healthtg.security.ResourceNotFoundException;
import org.healthtg.core.entry.EntryNotFoundException;
import org.healthtg.core.entry.EntryStatusConflictException;
import org.healthtg.core.entry.EntryValidationException;
import org.healthtg.core.entry.EntryVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

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

    @ExceptionHandler(EntryNotFoundException.class)
    ResponseEntity<ApiError> entryNotFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(EntryVersionConflictException.class)
    ResponseEntity<ApiError> versionConflict(HttpServletRequest request, EntryVersionConflictException exception) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("VERSION_CONFLICT",
                "Entry revision is outdated", requestId, java.util.List.of(new ApiError.FieldError(
                "expected_revision", "Current revision is " + exception.current().revision(), "STALE_REVISION"))));
    }

    @ExceptionHandler(EntryStatusConflictException.class)
    ResponseEntity<ApiError> statusConflict(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Entry status transition is not allowed");
    }

    @ExceptionHandler({EntryValidationException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> entryValidation(HttpServletRequest request) {
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(HttpServletRequest request) {
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson(HttpServletRequest request) {
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> invalidHttpInput(HttpServletRequest request) {
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
