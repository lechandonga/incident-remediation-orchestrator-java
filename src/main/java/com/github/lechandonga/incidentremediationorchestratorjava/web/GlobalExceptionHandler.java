package com.github.lechandonga.incidentremediationorchestratorjava.web;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** 字段缺失/格式非法请求返回 400 与明确原因。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .toList();
        return ResponseEntity.badRequest().body(ApiError.badRequest("请求参数校验失败", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.badRequest("请求体缺失或格式非法: " + ex.getMessage(), List.of()));
    }
}
