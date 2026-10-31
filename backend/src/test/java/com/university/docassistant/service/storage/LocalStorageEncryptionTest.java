package com.university.docassistant.service.storage;

import com.university.docassistant.service.storage.LocalStorageService.MigrationResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-03: AES-256-GCM encryption at rest of the local storage, plain legacy files and their migration. */
class LocalStorageEncryptionTest {

    private static final String KEY = key(1);
    private static final String OTHER_KEY = key(2);
    private static final int SEGMENT = FileEncryption.SEGMENT_SIZE;

    @TempDir
    Path root;

    private static String key(int seed) {
        byte[] key = new byte[32];
        new Random(seed).nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private Path dir() {
        return root.resolve("uploads");
    }

    private LocalStorageService storage(String enabled, String key) throws IOException {
        return new LocalStorageService(dir().toString(), enabled, key);
    }

    private LocalStorageService encrypted() throws IOException {
        return storage("", KEY);
    }

    private static byte[] content(int length) {
        byte[] content = new byte[length];
        new Random(length).nextBytes(content);
        return content;
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Nested
    class Encrypted {

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {0, 1, 100, SEGMENT - 1, SEGMENT, SEGMENT + 1, 3 * SEGMENT, 3 * SEGMENT + 17})
        void roundTripKeepsContentAndReportsThePlaintextLength(int length) throws IOException {
            LocalStorageService storage = encrypted();
            byte[] content = content(length);

            String name = storage.store(content);

            Resource loaded = storage.load(name);
            assertThat(loaded.contentLength()).isEqualTo(length);
            assertThat(read(loaded)).isEqualTo(content);
            assertThat(read(loaded)).isEqualTo(content); // Every getInputStream() decrypts afresh.
        }

        @Test
        void multipartUploadIsEncryptedToo() throws IOException {
            LocalStorageService storage = encrypted();
            byte[] content = content(2 * SEGMENT + 5);

            String name = storage.store(new MockMultipartFile("file", "lab.pdf", "application/pdf", content));

            assertThat(Files.readAllBytes(dir().resolve(name))).startsWith("UDENC".getBytes(StandardCharsets.US_ASCII));
            assertThat(read(storage.load(name))).isEqualTo(content);
        }

        @Test
        void contentOnDiskDiffersFromThePlaintext() throws IOException {
            LocalStorageService storage = encrypted();
            byte[] content = "ИИН 990101300013, Иванов А. — приказ о зачислении".repeat(50).getBytes(StandardCharsets.UTF_8);

            String name = storage.store(content);

            byte[] onDisk = Files.readAllBytes(dir().resolve(name));
            assertThat(onDisk).startsWith('U', 'D', 'E', 'N', 'C', 0, FileEncryption.VERSION);
            assertThat(onDisk).hasSize(FileEncryption.HEADER_LENGTH + content.length + FileEncryption.TAG_LENGTH);
            assertThat(new String(onDisk, StandardCharsets.UTF_8)).doesNotContain("990101300013").doesNotContain("Иванов");
            // A random IV per file: the same content never gives the same bytes.
            assertThat(Files.readAllBytes(dir().resolve(storage.store(content)))).isNotEqualTo(onDisk);
            try (var files = Files.list(dir())) {
                assertThat(files).allSatisfy(path -> assertThat(path.getFileName().toString()).doesNotEndWith(".part"));
            }
        }

        @Test
        void wrongOrMissingKeyFailsAtLoad() throws IOException {
            String name = encrypted().store(content(500));

            assertThatThrownBy(() -> storage("", OTHER_KEY).load(name)).isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("wrong STORAGE_ENCRYPTION_KEY");
            assertThatThrownBy(() -> new LocalStorageService(dir().toString()).load(name))
                    .isInstanceOf(UncheckedIOException.class).hasMessageContaining("STORAGE_ENCRYPTION_KEY is not set");
        }

        @Test
        void changedByteOrCutOffSegmentIsDetected() throws IOException {
            LocalStorageService storage = encrypted();
            Path tampered = dir().resolve(storage.store(content(SEGMENT + 10)));
            byte[] bytes = Files.readAllBytes(tampered);
            bytes[FileEncryption.HEADER_LENGTH + 3] ^= 1;
            Files.write(tampered, bytes);

            assertThatThrownBy(() -> storage.load(tampered.getFileName().toString()))
                    .isInstanceOf(UncheckedIOException.class);

            // Drop the last segment exactly at a segment boundary: what is left must not pass as the whole file.
            Path cut = dir().resolve(storage.store(content(2 * SEGMENT + 10)));
            byte[] whole = Files.readAllBytes(cut);
            Files.write(cut, Arrays.copyOf(whole, FileEncryption.HEADER_LENGTH + 2 * (SEGMENT + FileEncryption.TAG_LENGTH)));
            Resource loaded = storage.load(cut.getFileName().toString()); // The first segment is still intact.
            assertThatThrownBy(() -> read(loaded)).isInstanceOf(IOException.class).hasMessageContaining("decrypt");
        }

        @Test
        void encryptionSwitchedOffWithAKeyWritesPlainAndStillReadsEncryptedFiles() throws IOException {
            byte[] old = content(300);
            String encryptedName = encrypted().store(old);
            LocalStorageService off = storage("false", KEY);
            byte[] fresh = content(200);

            String plainName = off.store(fresh);

            assertThat(dir().resolve(plainName)).hasBinaryContent(fresh);
            assertThat(read(off.load(encryptedName))).isEqualTo(old);
            assertThat(off.encryptLegacyFiles()).isEqualTo(new MigrationResult(0, 0));
            assertThat(dir().resolve(plainName)).hasBinaryContent(fresh);
        }
    }

    @Nested
    class Legacy {

        @Test
        void plainFileFromBeforeEncryptionIsStillReadable() throws IOException {
            byte[] content = "%PDF-1.7 старый файл".getBytes(StandardCharsets.UTF_8);
            String name = new LocalStorageService(dir().toString()).store(content);

            Resource loaded = encrypted().load(name);

            assertThat(loaded.contentLength()).isEqualTo(content.length);
            assertThat(read(loaded)).isEqualTo(content);
        }

        @Test
        void migrationEncryptsPlainFilesOnceAndIsIdempotent() throws IOException {
            LocalStorageService plain = new LocalStorageService(dir().toString());
            byte[] first = content(10);
            byte[] second = content(SEGMENT * 2 + 1);
            String a = plain.store(first);
            String b = plain.store(second);
            LocalStorageService storage = encrypted();
            String already = storage.store(content(50));
            byte[] alreadyOnDisk = Files.readAllBytes(dir().resolve(already));
            Path foreign = Files.writeString(dir().resolve("README.txt"), "not a stored file");

            assertThat(storage.encryptLegacyFiles()).isEqualTo(new MigrationResult(2, 0));

            for (String name : new String[]{a, b}) {
                assertThat(Files.readAllBytes(dir().resolve(name))).startsWith('U', 'D', 'E', 'N', 'C', 0);
            }
            assertThat(read(storage.load(a))).isEqualTo(first);
            assertThat(read(storage.load(b))).isEqualTo(second);
            assertThat(dir().resolve(already)).hasBinaryContent(alreadyOnDisk); // Not encrypted twice.
            assertThat(foreign).hasContent("not a stored file");

            byte[] aOnDisk = Files.readAllBytes(dir().resolve(a));
            assertThat(storage.encryptLegacyFiles()).isEqualTo(new MigrationResult(0, 0));
            assertThat(dir().resolve(a)).hasBinaryContent(aOnDisk);
        }

        @Test
        void interruptedMigrationLeavesTheOriginalAndItsTemporaryFileIsRemoved() throws IOException {
            byte[] content = content(1_000);
            String name = new LocalStorageService(dir().toString()).store(content);
            // What a run killed before its rename leaves behind: a half-written temporary file next to the original.
            Path leftover = Files.write(dir().resolve(name + LocalStorageService.MIGRATION_SUFFIX), new byte[]{1, 2, 3});
            LocalStorageService storage = encrypted();

            assertThat(storage.encryptLegacyFiles()).isEqualTo(new MigrationResult(1, 0));

            assertThat(leftover).doesNotExist();
            assertThat(read(storage.load(name))).isEqualTo(content);
        }
    }

    @Nested
    class Startup {

        @Test
        void enabledWithoutAKeyFailsFast() {
            assertThatThrownBy(() -> storage("true", "")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STORAGE_ENCRYPTION_KEY");
        }

        @Test
        void invalidKeysFailFastWithoutShowingTheKey() {
            String notBase64 = "not base64 at all!";
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> storage("", notBase64)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not valid base64").hasMessageNotContaining(notBase64);
            assertThatThrownBy(() -> storage("true", tooShort)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes").hasMessageNotContaining(tooShort);
            // Also when encryption is off: a key that is set must be usable.
            assertThatThrownBy(() -> storage("false", tooShort)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void enabledMustBeTrueFalseOrEmpty() {
            assertThatThrownBy(() -> storage("yes", KEY)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.storage.encryption.enabled");
        }

        @Test
        void defaultIsOnExactlyWhenAKeyIsSet() throws IOException {
            assertThat(storage("", KEY).isEncrypting()).isTrue();
            assertThat(storage("", "").isEncrypting()).isFalse();
            assertThat(storage("false", KEY).isEncrypting()).isFalse();
            assertThat(storage("true", KEY).isEncrypting()).isTrue();
        }
    }
}
