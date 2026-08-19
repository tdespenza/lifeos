package com.lifeos.documentvault.storage;

/** The object-store boundary cannot safely complete or clean up an operation. */
public class DocumentObjectStorageException extends RuntimeException {

    public DocumentObjectStorageException() {
        super(null, null, false, false);
    }

    public DocumentObjectStorageException(Throwable cause) {
        super(null, cause, false, false);
    }
}
