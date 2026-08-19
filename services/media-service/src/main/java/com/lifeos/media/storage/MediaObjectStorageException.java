package com.lifeos.media.storage;

/** Local/object-storage boundary failed without exposing provider paths or causes to callers. */
public class MediaObjectStorageException extends RuntimeException {

    public MediaObjectStorageException() {
    }

    public MediaObjectStorageException(Throwable cause) {
        super(cause);
    }
}
