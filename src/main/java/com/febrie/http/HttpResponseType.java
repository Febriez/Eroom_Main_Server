package com.febrie.http;

public enum HttpResponseType {

    OK(200), NOT_FOUND(404), BAD_REQUEST(400), SERVER_ERROR(500), INVALID_METHOD(405);

    private final int code;

    HttpResponseType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
