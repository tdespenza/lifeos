package com.lifeos.media.storage;

/** HLS content cannot safely be accessed until a reviewed worker has marked it ready. */
public class MediaHlsNotReadyException extends RuntimeException {
}
