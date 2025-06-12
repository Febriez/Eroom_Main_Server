package com.febrie.eroom.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoAvailableKeyException extends IllegalStateException {

    private static final Logger log = LoggerFactory.getLogger(NoAvailableKeyException.class);

    public NoAvailableKeyException(String message) {
        super(message);
        log.error(message);
    }
}
