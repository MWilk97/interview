package com.mwilk.ledger.core;

/**
 * A value that can never be valid (negative money, zero transfer, same source and target, malformed id).
 * Subclasses {@link IllegalArgumentException} so plain-Java callers see the usual precondition failure,
 * while adapters can map exactly this type to a client error without catching every IAE in the stack.
 */
public class InvalidRequestException extends IllegalArgumentException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
