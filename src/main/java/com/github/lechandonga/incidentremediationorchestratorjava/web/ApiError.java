package com.github.lechandonga.incidentremediationorchestratorjava.web;

import java.util.List;

/** 统一错误响应体：字段校验错误逐字段列出，绝不静默吞掉。 */
public record ApiError(String code, String message, List<String> fieldErrors) {
    public static ApiError badRequest(String message, List<String> fieldErrors) {
        return new ApiError("BAD_REQUEST", message, fieldErrors);
    }

    public static ApiError notFound(String message) {
        return new ApiError("NOT_FOUND", message, List.of());
    }
}
