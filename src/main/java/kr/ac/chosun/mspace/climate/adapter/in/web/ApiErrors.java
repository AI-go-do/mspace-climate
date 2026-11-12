package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.util.Map;
import kr.ac.chosun.mspace.climate.appliance.ApplianceException;
import kr.ac.chosun.mspace.climate.auth.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류는 {code, message} */
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(ApplianceException.class)
    ResponseEntity<Map<String, String>> appliance(ApplianceException e) {
        return ResponseEntity.status(502).body(Map.of("code", "APPLIANCE_UNREACHABLE", "message", "기기에 명령이 닿지 않았어요"));
    }
}
