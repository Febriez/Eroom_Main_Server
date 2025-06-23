package com.febrie.eroom.exception;

/**
 * API 키를 사용할 수 없을 때 발생하는 예외
 */
public class NoAvailableKeyException extends RuntimeException {

    /**
     * 메시지와 함께 예외를 생성합니다.
     */
    public NoAvailableKeyException(String message) {
        super(message);
    }

    /**
     * 메시지와 원인 예외와 함께 예외를 생성합니다.
     */
    public NoAvailableKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}