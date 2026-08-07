package org.fribok.bookkeeping.service.backup;

import java.nio.file.Path;
import java.time.LocalDateTime;

/** Summary of a created or previously recorded backup. */
public record BackupDetails(Path path, LocalDateTime createdAt, long size, boolean exists) {}
