package com.university.docassistant.service.storage;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption of stored files (US-03), as streams: neither direction ever holds a whole file in memory.
 * <p>
 * Format: {@value #MAGIC_TEXT} with a zero byte (6 bytes), a version byte, a random 12-byte IV, then the ciphertext
 * in segments: every {@value #SEGMENT_SIZE} bytes of plaintext (the last segment shorter, possibly empty) are one GCM
 * message with its own 16-byte tag. A single GCM message cannot be decrypted as a stream (the JDK keeps all of it
 * until the tag is checked), hence the segments. Segment {@code i} uses the IV with {@code i} XOR-ed into its last 8
 * bytes as nonce, and authenticates the header, {@code i} and whether it is the last segment, so reordered, dropped
 * or cut-off segments and a changed header all fail to decrypt. The plaintext length follows from the file size.
 * <p>
 * Never logs or prints the key.
 */
public final class FileEncryption {

    static final String MAGIC_TEXT = "UDENC";
    private static final byte[] MAGIC = {'U', 'D', 'E', 'N', 'C', 0};
    static final byte VERSION = 1;
    static final int IV_LENGTH = 12;
    static final int HEADER_LENGTH = MAGIC.length + 1 + IV_LENGTH;
    static final int SEGMENT_SIZE = 64 * 1024;
    static final int TAG_LENGTH = 16;
    private static final int SEGMENT_CIPHERTEXT = SEGMENT_SIZE + TAG_LENGTH;
    private static final int KEY_LENGTH = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private FileEncryption(byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    /**
     * The key from STORAGE_ENCRYPTION_KEY: base64 of exactly 32 bytes. Throws IllegalStateException otherwise; the
     * message never contains the value.
     */
    public static FileEncryption fromBase64Key(String base64Key) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("STORAGE_ENCRYPTION_KEY is not valid base64; generate one as described in "
                    + "application.example.yml");
        }
        if (key.length != KEY_LENGTH) {
            int length = key.length;
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("STORAGE_ENCRYPTION_KEY must be base64 of 32 bytes (AES-256), got "
                    + length + " bytes");
        }
        FileEncryption encryption = new FileEncryption(key);
        Arrays.fill(key, (byte) 0);
        return encryption;
    }

    /** Whether {@code start} (the first bytes of a file, at least the header) begins with the magic header. */
    static boolean hasMagic(byte[] start, int length) {
        if (length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (start[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Plaintext length of an encrypted file of {@code fileSize} bytes; IOException when it cannot be one. */
    static long plaintextLength(long fileSize) throws IOException {
        long body = fileSize - HEADER_LENGTH;
        if (body < TAG_LENGTH) {
            throw new IOException("Encrypted file is too short");
        }
        long segments = (body + SEGMENT_CIPHERTEXT - 1) / SEGMENT_CIPHERTEXT;
        long length = body - segments * TAG_LENGTH;
        if (length < 0 || (body % SEGMENT_CIPHERTEXT != 0 && body % SEGMENT_CIPHERTEXT < TAG_LENGTH)) {
            throw new IOException("Encrypted file has an invalid length");
        }
        return length;
    }

    /** Writes the header now and the segments as data comes; {@code close()} writes the last one and closes {@code out}. */
    public OutputStream encrypt(OutputStream out) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = VERSION;
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        System.arraycopy(iv, 0, header, MAGIC.length + 1, IV_LENGTH);
        out.write(header);
        return new EncryptingStream(out, header, iv);
    }

    /** Reads and checks the header, then decrypts segment by segment; IOException on a wrong key or damaged file. */
    public InputStream decrypt(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_LENGTH);
        if (header.length < HEADER_LENGTH || !hasMagic(header, header.length)) {
            throw new IOException("Not an encrypted file");
        }
        if (header[MAGIC.length] != VERSION) {
            throw new IOException("Unsupported encrypted file version " + header[MAGIC.length]);
        }
        return new DecryptingStream(in, header, Arrays.copyOfRange(header, MAGIC.length + 1, HEADER_LENGTH));
    }

    private Cipher cipher(int mode, byte[] header, byte[] iv, long segment, boolean last) throws IOException {
        byte[] nonce = iv.clone();
        for (int i = 0; i < Long.BYTES; i++) {
            nonce[IV_LENGTH - 1 - i] ^= (byte) (segment >>> (8 * i));
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce));
            cipher.updateAAD(header);
            cipher.updateAAD(ByteBuffer.allocate(Long.BYTES + 1).putLong(segment).put((byte) (last ? 1 : 0)).array());
            return cipher;
        } catch (GeneralSecurityException ex) {
            throw new IOException("AES-GCM is not available", ex);
        }
    }

    private final class EncryptingStream extends FilterOutputStream {

        private final byte[] header;
        private final byte[] iv;
        private final byte[] buffer = new byte[SEGMENT_SIZE];
        private int buffered;
        private long segment;
        private boolean closed;

        private EncryptingStream(OutputStream out, byte[] header, byte[] iv) {
            super(out);
            this.header = header;
            this.iv = iv;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            while (length > 0) {
                if (buffered == SEGMENT_SIZE) {
                    // More data follows, so the full buffer is not the last segment.
                    writeSegment(false);
                }
                int n = Math.min(length, SEGMENT_SIZE - buffered);
                System.arraycopy(data, offset, buffer, buffered, n);
                buffered += n;
                offset += n;
                length -= n;
            }
        }

        private void writeSegment(boolean last) throws IOException {
            try {
                out.write(cipher(Cipher.ENCRYPT_MODE, header, iv, segment, last).doFinal(buffer, 0, buffered));
            } catch (GeneralSecurityException ex) {
                throw new IOException("Could not encrypt", ex);
            }
            segment++;
            buffered = 0;
        }

        @Override
        public void flush() {
            // Segments are written whole; flushing a partial one would break the format.
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writeSegment(true);
                out.flush();
            } finally {
                Arrays.fill(buffer, (byte) 0);
                out.close();
            }
        }
    }

    private final class DecryptingStream extends InputStream {

        private final InputStream in;
        private final byte[] header;
        private final byte[] iv;
        private final byte[] ciphertext = new byte[SEGMENT_CIPHERTEXT];
        private byte[] plaintext = new byte[0];
        private int position;
        private long segment;
        private boolean done;
        /** A byte read ahead to learn whether a full segment was the last one; -1 when none. */
        private int lookahead = -1;

        private DecryptingStream(InputStream in, byte[] header, byte[] iv) {
            this.in = in;
            this.header = header;
            this.iv = iv;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (position == plaintext.length) {
                if (done) {
                    return -1;
                }
                nextSegment();
            }
            int n = Math.min(length, plaintext.length - position);
            System.arraycopy(plaintext, position, target, offset, n);
            position += n;
            return n;
        }

        private void nextSegment() throws IOException {
            int filled = 0;
            if (lookahead >= 0) {
                ciphertext[filled++] = (byte) lookahead;
                lookahead = -1;
            }
            filled += in.readNBytes(ciphertext, filled, SEGMENT_CIPHERTEXT - filled);
            boolean last = filled < SEGMENT_CIPHERTEXT || (lookahead = in.read()) < 0;
            if (filled < TAG_LENGTH) {
                throw new IOException("Encrypted file is cut off");
            }
            try {
                plaintext = cipher(Cipher.DECRYPT_MODE, header, iv, segment, last).doFinal(ciphertext, 0, filled);
            } catch (AEADBadTagException ex) {
                throw new IOException("Could not decrypt the file: wrong STORAGE_ENCRYPTION_KEY or a damaged file");
            } catch (GeneralSecurityException ex) {
                throw new IOException("Could not decrypt the file", ex);
            }
            position = 0;
            segment++;
            done = last;
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(plaintext, (byte) 0);
            in.close();
        }
    }
}
