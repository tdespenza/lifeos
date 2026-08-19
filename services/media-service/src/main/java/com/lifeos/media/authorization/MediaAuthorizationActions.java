package com.lifeos.media.authorization;

/** Exact actions registered in Identity's Media v2 descriptor table. */
public final class MediaAuthorizationActions {

    public static final String ASSET_CREATE = "media:asset-create";
    public static final String ASSET_LIST = "media:asset-list";
    public static final String ASSET_READ = "media:asset-read";
    public static final String ASSET_UPLOAD = "media:asset-upload";
    public static final String HLS_MANIFEST_READ = "media:hls-manifest-read";
    public static final String HLS_SEGMENT_READ = "media:hls-segment-read";
    public static final String SESSION_CREATE = "media:session-create";
    public static final String SESSION_LIST = "media:session-list";
    public static final String SESSION_READ = "media:session-read";
    public static final String SESSION_UPDATE = "media:session-update";
    public static final String SESSION_CANCEL = "media:session-cancel";
    public static final String SESSION_JOIN = "media:session-join";

    private MediaAuthorizationActions() {
    }
}
