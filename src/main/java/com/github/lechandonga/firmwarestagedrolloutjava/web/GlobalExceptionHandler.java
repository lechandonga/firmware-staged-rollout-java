package com.github.lechandonga.firmwarestagedrolloutjava.web;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ErrorBody;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务拒绝：幂等类重复请求返回 200（结果可区分，见 code），其余拒绝返回 409。
     */
    @ExceptionHandler(RolloutException.class)
    public ResponseEntity<ErrorBody> handleRollout(RolloutException ex) {
        HttpStatus status = ex.getReason().isIdempotent() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new ErrorBody(ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorBody> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_FAILED", message));
    }
}
