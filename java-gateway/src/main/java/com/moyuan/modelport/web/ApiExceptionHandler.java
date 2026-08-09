package com.moyuan.modelport.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<Map<String, Object>> gateway(GatewayException error) {
        return response(error.status(), error.status().is4xxClientError() ? "invalid_request_error" : "upstream_error",
                error.getMessage());
    }

    @ExceptionHandler(WebClientRequestException.class)
    ResponseEntity<Map<String, Object>> upstream(WebClientRequestException error) {
        return response(HttpStatus.BAD_GATEWAY, "upstream_connection_error", "unable to connect to model provider");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        log.error("unexpected gateway error", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected gateway error");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(Map.of("error", Map.of("type", type, "message", message)));
    }
}
