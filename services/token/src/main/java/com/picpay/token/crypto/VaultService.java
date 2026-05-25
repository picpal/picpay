package com.picpay.token.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class VaultService {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] keyBytes;

    public VaultService(@Value("${vault.aes-key}") String base64Key) {
        this.keyBytes = Base64.getDecoder().decode(base64Key.trim());
        if (this.keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "vault.aes-key must be a 256-bit (32-byte) AES key; got " + this.keyBytes.length + " bytes");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BIT_LENGTH, nonce));

            byte[] cipherWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[NONCE_LENGTH + cipherWithTag.length];
            System.arraycopy(nonce, 0, result, 0, NONCE_LENGTH);
            System.arraycopy(cipherWithTag, 0, result, NONCE_LENGTH, cipherWithTag.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] data = Base64.getDecoder().decode(encryptedBase64);
            byte[] nonce = Arrays.copyOfRange(data, 0, NONCE_LENGTH);
            byte[] cipherWithTag = Arrays.copyOfRange(data, NONCE_LENGTH, data.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BIT_LENGTH, nonce));

            return new String(cipher.doFinal(cipherWithTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
