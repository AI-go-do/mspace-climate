package kr.ac.chosun.mspace.climate.auth;

import org.springframework.http.HttpStatus;

/** {code, message} 오류 응답 (팀 컨벤션) */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
