package app.claudeassist;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional passphrase encryption for backup files.
 * Layout: MAGIC(5) | kdf(1) | salt(16) | iv(12) | AES-256-GCM ciphertext+tag.
 * Unencrypted backups are plain JSON and start with '{'.
 */
final class BackupCodec {

    private static final byte[] MAGIC = {'C', 'A', 'B', 'K', '1'};
    private static final int KDF_SHA256 = 1;   // API 26+
    private static final int KDF_SHA1 = 0;     // older devices
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int HEADER_LEN = MAGIC.length + 1 + SALT_LEN + IV_LEN;

    private BackupCodec() {}

    static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < HEADER_LEN) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    static byte[] encrypt(byte[] plain, char[] passphrase) throws GeneralSecurityException {
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        int kdf = sha256Available() ? KDF_SHA256 : KDF_SHA1;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, kdf), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[HEADER_LEN + ct.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) kdf;
        System.arraycopy(salt, 0, out, MAGIC.length + 1, SALT_LEN);
        System.arraycopy(iv, 0, out, MAGIC.length + 1 + SALT_LEN, IV_LEN);
        System.arraycopy(ct, 0, out, HEADER_LEN, ct.length);
        return out;
    }

    /** @throws GeneralSecurityException on wrong passphrase or damaged data */
    static byte[] decrypt(byte[] data, char[] passphrase) throws GeneralSecurityException {
        if (!isEncrypted(data)) throw new GeneralSecurityException("not an encrypted backup");
        int kdf = data[MAGIC.length];
        byte[] salt = Arrays.copyOfRange(data, MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN);
        byte[] iv = Arrays.copyOfRange(data, MAGIC.length + 1 + SALT_LEN, HEADER_LEN);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, kdf), new GCMParameterSpec(128, iv));
        return cipher.doFinal(data, HEADER_LEN, data.length - HEADER_LEN);
    }

    private static boolean sha256Available() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static SecretKey deriveKey(char[] pass, byte[] salt, int kdf) throws GeneralSecurityException {
        String alg = kdf == KDF_SHA256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        int iterations = kdf == KDF_SHA256 ? 210000 : 120000;
        SecretKeyFactory f = SecretKeyFactory.getInstance(alg);
        byte[] key = f.generateSecret(new PBEKeySpec(pass, salt, iterations, 256)).getEncoded();
        return new SecretKeySpec(key, "AES");
    }
}
