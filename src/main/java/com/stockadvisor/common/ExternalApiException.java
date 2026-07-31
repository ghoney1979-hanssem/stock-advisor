package com.stockadvisor.common;

/**
 * 외부 OpenAPI(DART, KIS) 연동 중 발생한 오류를 표현하는 예외.
 */
public class ExternalApiException extends RuntimeException {

    private final String source;

    public ExternalApiException(String source, String message) {
        super(message);
        this.source = source;
    }

    public ExternalApiException(String source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}