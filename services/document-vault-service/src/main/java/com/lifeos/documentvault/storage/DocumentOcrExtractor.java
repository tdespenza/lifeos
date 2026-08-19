package com.lifeos.documentvault.storage;

import java.nio.file.Path;

@FunctionalInterface
interface DocumentOcrExtractor {

    String extract(Path objectPath, DocumentContentType contentType);

    static DocumentOcrExtractor disabled() {
        return (ignoredPath, ignoredType) -> "";
    }
}
