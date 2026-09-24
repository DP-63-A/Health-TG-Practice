package org.healthtg.web;

public record ApiError(String code, String message, String requestId) {
}
