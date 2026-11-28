package com.draftagent.business.document;

import java.util.Locale;
import java.util.Optional;

/**
 * 支持上传的文档类型（V1：TXT / Markdown / PDF）。
 */
public enum DocumentFileType {

    PDF("pdf"),
    TXT("txt"),
    MARKDOWN("md");

    private final String extension;

    DocumentFileType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }

    public static Optional<DocumentFileType> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        for (DocumentFileType type : values()) {
            if (type.extension.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
