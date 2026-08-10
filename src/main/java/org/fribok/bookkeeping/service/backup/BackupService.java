package org.fribok.bookkeeping.service.backup;

import se.swedsoft.bookkeeping.data.backup.SSBackup;
import se.swedsoft.bookkeeping.data.backup.util.SSBackupType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates, lists, and verifies full Bokfri database backups. */
public final class BackupService {
    private static final String INFO_ENTRY = "backup.info";
    private static final String HISTORY_FILE = "backup.history.cli";
    private static final List<String> DATABASE_SUFFIXES = List.of(
            ".properties", ".script", ".data", ".backup", ".log");

    private final Path dataDirectory;

    public BackupService(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public BackupDetails create(Path output, boolean overwrite) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target) && !overwrite) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        Path temporary = Files.createTempFile(target.getParent(), ".bokfri-backup-", ".zip");
        LocalDateTime createdAt = LocalDateTime.now();
        try {
            writeArchive(temporary, target, createdAt);
            moveIntoPlace(temporary, target, overwrite);
        } finally {
            Files.deleteIfExists(temporary);
        }

        BackupDetails details = new BackupDetails(target, createdAt, Files.size(target), true);
        appendHistory(details);
        return details;
    }

    public BackupVerification verify(Path input) throws IOException {
        Path archive = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Backup file does not exist: " + archive);
        }

        Set<String> entries = new HashSet<>();
        SSBackup metadata = null;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory() || !entries.add(entry.getName())) {
                    throw new IOException("Invalid or duplicate backup entry: " + entry.getName());
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    stream.transferTo(OutputStream.nullOutputStream());
                }
            }
            ZipEntry info = zip.getEntry(INFO_ENTRY);
            if (info == null) {
                throw new IOException("Backup metadata is missing");
            }
            try (ObjectInputStream stream = new ObjectInputStream(
                    new BufferedInputStream(zip.getInputStream(info)))) {
                Object value = stream.readObject();
                if (!(value instanceof SSBackup backup) || backup.getType() != SSBackupType.FULL) {
                    throw new IOException("Backup metadata is invalid");
                }
                metadata = backup;
            } catch (ClassNotFoundException exception) {
                throw new IOException("Backup metadata cannot be read", exception);
            }
        }

        if (!entries.contains("JFSDB.properties") || !entries.contains("JFSDB.script")) {
            throw new IOException("Backup database files are incomplete");
        }
        return new BackupVerification(archive, metadata.getLocalDateTime(), Files.size(archive),
                entries.stream().sorted().toList());
    }

    public BackupRestorePlan restore(Path input, Path targetDataDirectory,
            boolean overwrite, boolean commit) throws IOException {
        BackupVerification verification = verify(input);
        Path target = targetDataDirectory.toAbsolutePath().normalize();
        Path databaseDirectory = target.resolve("db");
        List<String> databaseFiles = verification.entries().stream()
                .filter(entry -> entry.startsWith("JFSDB.")).sorted().toList();
        boolean replacesExisting = databaseFiles.stream()
                .map(databaseDirectory::resolve).anyMatch(Files::exists);
        if (replacesExisting && !overwrite) {
            throw new FileAlreadyExistsException(databaseDirectory.resolve("JFSDB.*").toString());
        }
        BackupRestorePlan plan = new BackupRestorePlan(verification.path(), target,
                verification.createdAt(), databaseFiles, replacesExisting, commit);
        if (!commit) {
            return plan;
        }

        Files.createDirectories(target);
        Path staging = Files.createTempDirectory(target, ".bokfri-restore-");
        try {
            extractDatabaseFiles(verification.path(), staging, databaseFiles);
            Files.createDirectories(databaseDirectory);
            for (String name : databaseFiles) {
                moveIntoPlace(staging.resolve(name), databaseDirectory.resolve(name), overwrite);
            }
        } finally {
            try (var paths = Files.walk(staging)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            } catch (java.io.UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
        return plan;
    }

    public List<BackupDetails> list() throws IOException {
        Path history = dataDirectory.resolve(HISTORY_FILE);
        if (!Files.exists(history)) {
            return List.of();
        }
        List<BackupDetails> backups = new ArrayList<>();
        for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", 3);
            if (fields.length != 3) {
                continue;
            }
            try {
                LocalDateTime createdAt = LocalDateTime.parse(fields[0]);
                Path file = Path.of(new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8));
                long recordedSize = Long.parseLong(fields[2]);
                boolean exists = Files.isRegularFile(file);
                backups.add(new BackupDetails(file, createdAt,
                        exists ? Files.size(file) : recordedSize, exists));
            } catch (IllegalArgumentException ignored) {
                // Ignore damaged history rows; archive verification remains authoritative.
            }
        }
        backups.sort(Comparator.comparing(BackupDetails::createdAt).reversed());
        return List.copyOf(backups);
    }

    private void writeArchive(Path temporary, Path target, LocalDateTime createdAt) throws IOException {
        Path database = dataDirectory.resolve("db").resolve("JFSDB");
        List<Path> files = DATABASE_SUFFIXES.stream().map(suffix -> Path.of(database + suffix))
                .filter(Files::isRegularFile).toList();
        if (files.stream().noneMatch(path -> path.getFileName().toString().equals("JFSDB.properties"))
                || files.stream().noneMatch(path -> path.getFileName().toString().equals("JFSDB.script"))) {
            throw new IOException("Bokfri database files are incomplete in " + database.getParent());
        }

        SSBackup metadata = new SSBackup(SSBackupType.FULL);
        metadata.setLocalDateTime(createdAt);
        metadata.setFilename(target.toString());
        Path info = Files.createTempFile("bokfri-backup-info-", ".bin");
        try {
            SSBackup.storeBackup(info.toFile(), metadata);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Path file : files) {
                    add(zip, file, file.getFileName().toString());
                }
                add(zip, info, INFO_ENTRY);
            }
        } finally {
            Files.deleteIfExists(info);
        }
    }

    private static void extractDatabaseFiles(Path archive, Path staging, List<String> names)
            throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String name : names) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || name.contains("/") || name.contains("\\")) {
                    throw new IOException("Invalid backup database entry: " + name);
                }
                Path output = staging.resolve(name).normalize();
                if (!output.getParent().equals(staging)) {
                    throw new IOException("Backup entry escapes restore directory: " + name);
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    Files.copy(stream, output);
                }
            }
        }
    }

    private static void add(ZipOutputStream zip, Path file, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void moveIntoPlace(Path source, Path target, boolean overwrite) throws IOException {
        if (overwrite) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target);
        }
    }

    private void appendHistory(BackupDetails details) throws IOException {
        Files.createDirectories(dataDirectory);
        String encodedPath = Base64.getEncoder().encodeToString(
                details.path().toString().getBytes(StandardCharsets.UTF_8));
        String row = details.createdAt() + "\t" + encodedPath + "\t" + details.size() + System.lineSeparator();
        Files.writeString(dataDirectory.resolve(HISTORY_FILE), row, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
