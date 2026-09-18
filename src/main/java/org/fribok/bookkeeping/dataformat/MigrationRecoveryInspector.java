package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Detects abandoned migration state and refuses to infer a destructive recovery action. */
public final class MigrationRecoveryInspector {
    public static final String NORMALIZED_STAGING_PREFIX = ".bokfri-normalized-staging-";
    public static final String ACTIVATION_SOURCE_PREFIX = "bokfri-normalized-source-";

    private MigrationRecoveryInspector() {}

    /** Inspects names owned by the normalized migration flow without modifying any files. */
    public static RecoveryState inspect(Path dataDirectory) throws IOException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        List<Path> staging = childrenWithPrefix(data, NORMALIZED_STAGING_PREFIX);
        List<Path> retained = childrenWithPrefix(data.resolve("backups"), ACTIVATION_SOURCE_PREFIX);
        boolean activeDatabase = Files.isRegularFile(data.resolve("db/JFSDB.properties"));
        RecoveryKind kind;
        if (staging.isEmpty() && retained.isEmpty()) {
            kind = RecoveryKind.CLEAN;
        } else if (activeDatabase) {
            kind = RecoveryKind.ACTIVE_DATABASE_WITH_LEFTOVERS;
        } else if (!retained.isEmpty()) {
            kind = RecoveryKind.ACTIVE_DATABASE_MISSING_SOURCE_RETAINED;
        } else {
            kind = RecoveryKind.ACTIVE_DATABASE_MISSING_STAGING_ONLY;
        }
        return new RecoveryState(kind, activeDatabase, staging, retained);
    }

    /** Throws before migration/startup when manual or dedicated automatic recovery is required. */
    public static void requireClean(Path dataDirectory) throws IOException {
        RecoveryState state = inspect(dataDirectory);
        if (state.kind() != RecoveryKind.CLEAN) {
            throw new IOException("Unresolved normalized database migration state: " + state.kind()
                    + "; staging=" + state.stagingDirectories()
                    + "; retainedSources=" + state.retainedSourceDirectories());
        }
    }

    private static List<Path> childrenWithPrefix(Path parent, String prefix) throws IOException {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (var children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    public enum RecoveryKind {
        CLEAN,
        ACTIVE_DATABASE_WITH_LEFTOVERS,
        ACTIVE_DATABASE_MISSING_SOURCE_RETAINED,
        ACTIVE_DATABASE_MISSING_STAGING_ONLY
    }

    public record RecoveryState(RecoveryKind kind, boolean activeDatabaseExists,
                                List<Path> stagingDirectories,
                                List<Path> retainedSourceDirectories) {
        public RecoveryState {
            stagingDirectories = List.copyOf(new ArrayList<>(stagingDirectories));
            retainedSourceDirectories = List.copyOf(new ArrayList<>(retainedSourceDirectories));
        }
    }
}
