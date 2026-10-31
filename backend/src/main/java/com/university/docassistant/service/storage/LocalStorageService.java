package com.university.docassistant.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores uploaded files (documents and task submissions) under {@code app.storage.upload-dir}, each under a random
 * UUID name. The default driver ({@code app.storage.driver=local} or unset).
 * <p>
 * US-03, encryption at rest: with {@code app.storage.encryption.enabled} (default: on when STORAGE_ENCRYPTION_KEY is
 * set) every file is written AES-256-GCM encrypted, see {@link FileEncryption}. Files without its header are older
 * plain files and are read as they are; {@link #encryptLegacyFiles()} encrypts them at startup. With encryption off
 * but a key set, encrypted files stay readable.
 * <p>
 * Content is streamed in both directions, so a file is never held in memory as a whole. A file is written to a
 * temporary name first and then renamed atomically, so a half-written file never appears under its stored name.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    /** Names this service generates; nothing else in the folder is ever encrypted or touched by the migration. */
    private static final Pattern STORED_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Suffix of a file being written by {@link #store}. */
    private static final String UPLOAD_SUFFIX = ".part";
    /** Suffix of a file being encrypted by the migration; leftovers of an interrupted run are removed. */
    static final String MIGRATION_SUFFIX = ".enc-tmp";
    static final int MIGRATION_BATCH_SIZE = 100;

    private final Path uploadDir;
    /** Reads encrypted files; null when no key is configured. */
    private final FileEncryption encryption;
    /** New files and the migration encrypt; implies {@code encryption != null}. */
    private final boolean encrypting;

    /** Plain storage without a key; for tests. */
    public LocalStorageService(String uploadDir) throws IOException {
        this(uploadDir, "false", "");
    }

    /**
     * Fails fast (IllegalStateException) on an invalid key, on {@code enabled=true} without a key, and on an
     * {@code enabled} value other than true, false or empty (empty = on exactly when a key is set).
     */
    @Autowired
    public LocalStorageService(@Value("${app.storage.upload-dir}") String uploadDir,
                               @Value("${app.storage.encryption.enabled:${STORAGE_ENCRYPTION_ENABLED:}}") String enabled,
                               @Value("${app.storage.encryption.key:${STORAGE_ENCRYPTION_KEY:}}") String key) throws IOException {
        this.encryption = StringUtils.hasText(key) ? FileEncryption.fromBase64Key(key) : null;
        String setting = enabled == null ? "" : enabled.strip();
        if (!setting.isEmpty() && !setting.equalsIgnoreCase("true") && !setting.equalsIgnoreCase("false")) {
            throw new IllegalStateException("app.storage.encryption.enabled must be true, false or empty");
        }
        this.encrypting = setting.isEmpty() ? encryption != null : Boolean.parseBoolean(setting);
        if (encrypting && encryption == null) {
            throw new IllegalStateException("app.storage.encryption.enabled=true needs STORAGE_ENCRYPTION_KEY "
                    + "(base64 of 32 bytes); see application.example.yml");
        }
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
        if (encrypting) {
            log.info("Document storage at {}, encrypted at rest (AES-256-GCM)", this.uploadDir);
        } else {
            log.warn("Document storage at {} is NOT encrypted at rest: set STORAGE_ENCRYPTION_KEY to encrypt files{}",
                    this.uploadDir, encryption != null ? " (a key is set, so encrypted files stay readable)" : "");
        }
    }

    public boolean isEncrypting() {
        return encrypting;
    }

    /** Writes the upload to disk; returns the generated stored file name. */
    @Override
    public String store(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return write(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the upload", ex);
        }
    }

    /** Writes content already in memory (e.g. a file from a ZIP archive); returns the generated stored file name. */
    @Override
    public String store(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            return write(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write file", ex);
        }
    }

    private String write(InputStream in) {
        String storedFileName = UUID.randomUUID().toString();
        Path target = resolve(storedFileName);
        Path temporary = uploadDir.resolve(storedFileName + UPLOAD_SUFFIX);
        try {
            writeFile(in, temporary, encrypting);
            move(temporary, target);
        } catch (IOException ex) {
            deleteQuietly(temporary);
            delete(storedFileName);
            throw new UncheckedIOException("Could not write file " + storedFileName, ex);
        }
        return storedFileName;
    }

    private void writeFile(InputStream in, Path path, boolean encrypt) throws IOException {
        try (OutputStream file = Files.newOutputStream(path);
             OutputStream out = encrypt ? encryption.encrypt(file) : file) {
            in.transferTo(out);
        }
    }

    /**
     * The file, decrypted when it is encrypted; {@code contentLength()} is the plaintext length. An encrypted file
     * is checked here (its first segment is decrypted), so a wrong key fails now and not halfway through a download.
     */
    @Override
    public Resource load(String storedFileName) {
        Path path = resolve(storedFileName);
        if (!Files.isReadable(path)) {
            throw new UncheckedIOException(new IOException("Stored file " + storedFileName + " is missing"));
        }
        try {
            if (!isEncrypted(path)) {
                return new FileSystemResource(path); // Written before encryption was on.
            }
            if (encryption == null) {
                throw new IOException("Stored file " + storedFileName
                        + " is encrypted, but STORAGE_ENCRYPTION_KEY is not set");
            }
            EncryptedFileResource resource = new EncryptedFileResource(path,
                    FileEncryption.plaintextLength(Files.size(path)));
            try (InputStream in = resource.getInputStream()) {
                in.read();
            }
            return resource;
        } catch (IOException ex) {
            throw new UncheckedIOException(new IOException("Could not read stored file " + storedFileName + ": "
                    + ex.getMessage(), ex));
        }
    }

    @Override
    public void delete(String storedFileName) {
        try {
            Files.deleteIfExists(resolve(storedFileName));
        } catch (IOException ex) {
            // The DB row is already gone; an orphaned file is harmless, so only log it.
            log.warn("Could not delete file {}: {}", storedFileName, ex.getMessage());
        }
    }

    /** Outcome of {@link #encryptLegacyFiles()}. */
    public record MigrationResult(int encrypted, int failed) {
    }

    /**
     * Encrypts the stored files that are still plain, in batches of {@value #MIGRATION_BATCH_SIZE} (one progress
     * line each). Each file is encrypted into a temporary file next to it, which then atomically replaces it, so an
     * interrupted run leaves every file either plain or encrypted, never half done; its temporary files are removed
     * on the next run. Idempotent: encrypted files are skipped. A file that fails (e.g. open elsewhere) is logged and
     * tried again on the next start. Does nothing while encryption is off.
     */
    public MigrationResult encryptLegacyFiles() {
        if (!encrypting) {
            return new MigrationResult(0, 0);
        }
        int encrypted = 0;
        int failed = 0;
        int seen = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(uploadDir)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (name.endsWith(MIGRATION_SUFFIX)) {
                    deleteQuietly(path); // Left by an interrupted run; the original is untouched.
                    continue;
                }
                if (!STORED_NAME.matcher(name).matches() || !Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (!isEncrypted(path)) {
                        encryptInPlace(path);
                        encrypted++;
                    }
                } catch (IOException | RuntimeException ex) {
                    failed++;
                    log.warn("Could not encrypt stored file {}; it stays plain until the next start: {}", name,
                            ex.getMessage());
                }
                if (++seen % MIGRATION_BATCH_SIZE == 0) {
                    log.info("Storage encryption: {} files checked, {} encrypted so far", seen, encrypted);
                }
            }
        } catch (IOException ex) {
            log.warn("Storage encryption of old files stopped: {}", ex.getMessage());
        }
        return new MigrationResult(encrypted, failed);
    }

    private void encryptInPlace(Path path) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + MIGRATION_SUFFIX);
        try {
            try (InputStream in = Files.newInputStream(path)) {
                writeFile(in, temporary, true);
            }
            move(temporary, path);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private boolean isEncrypted(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] start = in.readNBytes(FileEncryption.HEADER_LENGTH);
            return FileEncryption.hasMagic(start, start.length);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("Could not delete temporary file {}: {}", path.getFileName(), ex.getMessage());
        }
    }

    private Path resolve(String storedFileName) {
        Path path = uploadDir.resolve(storedFileName).normalize();
        if (!path.startsWith(uploadDir) || path.equals(uploadDir)) {
            throw new IllegalArgumentException("Invalid stored file name");
        }
        return path;
    }

    /** An encrypted stored file: decrypted on every {@link #getInputStream()}, with the plaintext length. */
    private final class EncryptedFileResource extends AbstractResource {

        private final Path path;
        private final long length;

        private EncryptedFileResource(Path path, long length) {
            this.path = path;
            this.length = length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream file = Files.newInputStream(path);
            try {
                return encryption.decrypt(file);
            } catch (IOException | RuntimeException ex) {
                file.close();
                throw ex;
            }
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public boolean exists() {
            return Files.exists(path);
        }

        @Override
        public String getFilename() {
            return path.getFileName().toString();
        }

        @Override
        public String getDescription() {
            return "Encrypted file [" + path + "]";
        }
    }
}
