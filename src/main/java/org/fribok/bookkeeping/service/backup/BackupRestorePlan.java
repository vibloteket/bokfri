package org.fribok.bookkeeping.service.backup;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Verified plan or result for restoring a full backup to a data directory. */
public record BackupRestorePlan(Path archive, Path targetDataDirectory,
                                LocalDateTime createdAt, List<String> databaseFiles,
                                boolean replacesExistingDatabase, boolean committed) {
    public BackupRestorePlan {
        databaseFiles = List.copyOf(databaseFiles);
    }
}
