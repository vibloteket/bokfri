package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads and atomically writes the versioned CLI configuration file. */
public final class CliConfigStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public CliConfigStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public Path getPath() {
        return path;
    }

    public CliConfig load() throws IOException {
        if (!Files.exists(path)) {
            return new CliConfig();
        }
        CliConfig config = mapper.readValue(path.toFile(), CliConfig.class);
        if (config.getVersion() != 1) {
            throw new IOException("Unsupported CLI config version: " + config.getVersion());
        }
        return config;
    }

    public void save(CliConfig config) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), config);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
