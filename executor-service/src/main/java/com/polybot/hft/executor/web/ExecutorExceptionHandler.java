package com.polybot.hft.executor.web;

import com.polybot.hft.polymarket.http.PolymarketHttpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ExecutorExceptionHandler {

  @ExceptionHandler(PolymarketHttpException.class)
  public ResponseEntity<UpstreamHttpErrorResponse> handle(PolymarketHttpException e) {
    log.warn("upstream error: status={} method={} url={}", e.statusCode(), e.method(), e.uri());
    return ResponseEntity.status(e.statusCode()).body(new UpstreamHttpErrorResponse(e.statusCode(), e.method(), e.uri().toString(), e.responseSnippet()));
  }

  public record UpstreamHttpErrorResponse(int status, String method, String url, String bodySnippet) {
  }
}

