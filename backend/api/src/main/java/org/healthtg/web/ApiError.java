package org.healthtg.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String requestId, List<FieldError> fieldErrors) {
    public ApiError(String code, String message, String requestId) {
        this(code, message, requestId, null);
    }

    public record FieldError(String field, String message, String code) { }
}
