package org.fribok.bookkeeping.service.backup;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Successful structural and checksum verification of a backup archive. */
public record BackupVerification(Path path, LocalDateTime createdAt, long size, List<String> entries) {
    public BackupVerification {
        entries = List.copyOf(entries);
    }
}
