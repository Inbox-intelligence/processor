package com.inboxintelligence.processor.persistence.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Slf4j
public class LocalEmailStorageProvider implements EmailStorageProvider {

    @Override
    public String readContent(String storagePath) {

        if (storagePath == null || storagePath.isBlank()) {
            return "";
        }

        Path path = Path.of(storagePath);

        if (!Files.exists(path)) {
            log.warn("Storage file not found: {}", storagePath);
            return "";
        }

        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Failed to read storage file: {}", storagePath, e);
            throw new RuntimeException("Failed to read storage file: " + storagePath, e);
        }
    }

}
