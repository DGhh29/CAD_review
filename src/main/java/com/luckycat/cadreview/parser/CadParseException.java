package com.luckycat.cadreview.parser;

public class CadParseException extends RuntimeException {
    public CadParseException(String message) {
        super(message);
    }
    public CadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
