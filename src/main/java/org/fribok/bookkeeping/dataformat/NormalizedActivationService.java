package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Safely activates a validated normalized staging catalog with backup, swap and rollback. */
public final class NormalizedActivationService {
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final String DATABASE_NAME = "JFSDB";

    private final Clock clock;

    public NormalizedActivationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Activates {@code stagingDirectory} as the active database directory for {@code dataDirectory}.
     *
     * <p>Steps: verify both catalogs, create and read-verify a physical rollback ZIP, retain the
     * unpacked source under backups, move active db aside, move staging db into place. Any failure
     * restores the original catalog. The retained source and rollback archive are never deleted.
     */
    public ActivationResult activate(Path dataDirectory, Path stagingDirectory)
            throws IOException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path staging = Objects.requireNonNull(stagingDirectory, "stagingDirectory")
                .toAbsolutePath().normalize();
        Path activeDb = data.resolve("db");
        Path stagingDb = staging.resolve("db");
        if (!stagingDb.startsWith(data)) {
            throw new IOException("Staging directory must be inside the data directory: " + staging);
        }
        if (!staging.getFileName().toString()
                .startsWith(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX)) {
            throw new IOException("Not a normalized staging directory: " + staging);
        }
        requireCatalog(activeDb, "Active");
        requireCatalog(stagingDb, "Staging");

        Path backups = data.resolve("backups");
        Files.createDirectories(backups);
        String timestamp = DIRECTORY_TIME.format(clock.instant());
        Path rollbackArchive = backups.resolve("bokfri-before-normalized-" + timestamp + ".zip");
        createAndVerifyArchive(activeDb, rollbackArchive);

        Path retainedSource = backups.resolve(
                MigrationRecoveryInspector.ACTIVATION_SOURCE_PREFIX + timestamp);
        boolean moved = false;
        try {
            move(activeDb, retainedSource);
            moved = true;
            try {
                move(stagingDb, activeDb);
            } catch (IOException activationFailure) {
                move(retainedSource, activeDb);
                moved = false;
                throw activationFailure;
            }
            deleteTree(staging);
            return new ActivationResult(rollbackArchive, retainedSource, clock.instant());
        } catch (IOException | RuntimeException exception) {
            if (moved && !Files.exists(activeDb) && Files.exists(retainedSource)) {
                try {
                    move(retainedSource, activeDb);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            throw exception;
        }
    }

    private static void requireCatalog(Path dbDir, String label) throws IOException {
        Path properties = dbDir.resolve(DATABASE_NAME + ".properties");
        Path script = dbDir.resolve(DATABASE_NAME + ".script");
        if (!Files.isRegularFile(properties) || !Files.isRegularFile(script)) {
            throw new IOException(label + " catalog is incomplete: " + dbDir);
        }
    }

    private static void createAndVerifyArchive(Path databaseDirectory, Path archive)
            throws IOException {
        Path temporary = Files.createTempFile(archive.getParent(), ".normalized-rollback-", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary));
                 var files = Files.list(databaseDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(DATABASE_NAME + "."))
                        .sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                if (zip.getEntry(DATABASE_NAME + ".properties") == null
                        || zip.getEntry(DATABASE_NAME + ".script") == null) {
                    throw new IOException("Normalized rollback archive is incomplete");
                }
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    try (InputStream input = zip.getInputStream(entries.nextElement())) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                }
            }
            move(temporary, archive);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ActivationResult(Path rollbackArchive, Path retainedSource, Instant activatedAt) {}
}
