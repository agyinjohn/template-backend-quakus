package com.apex.commerce.media;

import com.apex.commerce.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stores uploaded images on local disk under {@code app.upload-dir}, one folder per store.
 * Swap this class for an S3/Cloud Storage implementation in production if needed.
 */
@ApplicationScoped
public class MediaStorage {

    static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp",
            "image/gif", ".gif", "image/svg+xml", ".svg", "image/avif", ".avif");

    @ConfigProperty(name = "app.upload-dir")
    String uploadDir;

    @ConfigProperty(name = "app.upload-max-bytes")
    long maxBytes;

    /** @return path relative to the upload dir, e.g. "12/5f0c...jpg" */
    public String save(long storeId, Path source, String contentType, long size) {
        String ext = ALLOWED.get(contentType == null ? "" : contentType.toLowerCase());
        if (ext == null) {
            throw ApiException.badRequest("Only JPEG, PNG, WebP, GIF, SVG or AVIF images can be uploaded");
        }
        if (size > maxBytes) {
            throw ApiException.badRequest("Image is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        String name = storeId + "/" + UUID.randomUUID() + ext;
        Path target = root().resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }

    public Optional<Path> resolve(String relative) {
        Path p = root().resolve(relative).normalize();
        if (!p.startsWith(root()) || !Files.isRegularFile(p)) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    Path root() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}
