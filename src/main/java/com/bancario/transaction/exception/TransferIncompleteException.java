package com.bancario.transaction.exception;

public class TransferIncompleteException extends RuntimeException {
    public TransferIncompleteException(String message) {
        super(message);
    }
}