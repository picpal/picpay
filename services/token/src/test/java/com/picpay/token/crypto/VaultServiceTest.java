package com.picpay.token.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultServiceTest {

    private VaultService vaultService;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        String base64Key = Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
        vaultService = new VaultService(base64Key);
    }

    @Test
    void encrypt_후_decrypt_하면_원문_복원() {
        String plaintext = "4111111111111111";

        String encrypted = vaultService.encrypt(plaintext);
        String decrypted = vaultService.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 동일_평문이라도_암호화_결과는_매번_다름() {
        String plaintext = "4111111111111111";

        String enc1 = vaultService.encrypt(plaintext);
        String enc2 = vaultService.encrypt(plaintext);

        assertThat(enc1).isNotEqualTo(enc2);
    }

    @Test
    void 변조된_암호문_복호화_시_예외() {
        String encrypted = vaultService.encrypt("test");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";

        assertThatThrownBy(() -> vaultService.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
