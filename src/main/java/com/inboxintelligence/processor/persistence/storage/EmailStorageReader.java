package com.inboxintelligence.processor.persistence.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Component
@Slf4j
public class EmailStorageReader {

    public Optional<String> readContent(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(storagePath);
            if (!Files.exists(path)) {
                log.warn("Storage file not found: {}", storagePath);
                return Optional.empty();
            }
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            log.error("Failed to read storage file: {}", storagePath, e);
            return Optional.empty();
        }
    }
}
