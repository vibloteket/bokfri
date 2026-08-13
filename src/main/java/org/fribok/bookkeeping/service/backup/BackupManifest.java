package org.fribok.bookkeeping.service.backup;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDateTime;

/** Human-readable, versioned metadata stored in new Bokfri backup archives. */
@JsonPropertyOrder({"format", "backupFormatVersion", "dataFormatVersion", "applicationVersion", "createdAt"})
public record BackupManifest(String format, int backupFormatVersion, int dataFormatVersion,
        String applicationVersion, LocalDateTime createdAt) {
    public static final String FORMAT = "bokfri-backup";
    public static final int CURRENT_BACKUP_FORMAT_VERSION = 1;
}
