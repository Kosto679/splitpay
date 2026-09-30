package com.splitpay.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

/** Easy check-in passwords: case-insensitive, hashed at rest. */
@Component
public class PinService {

    private static final String[] WORDS = {
        "apple", "bear", "cedar", "cloud", "coral", "daisy", "eagle", "fern", "fox", "grape",
        "honey", "kiwi", "lemon", "lion", "lotus", "mango", "maple", "melon", "moon", "olive",
        "otter", "panda", "peach", "pearl", "piano", "plum", "river", "robin", "rose", "sage",
        "sky", "star", "storm", "sunny", "tiger", "tulip", "wave", "zebra"
    };
    private static final int ITERATIONS = 60_000;

    private final SecureRandom random = new SecureRandom();

    public String normalize(String pin) {
        return pin == null ? "" : pin.strip().toLowerCase(Locale.ROOT);
    }

    public String generate() {
        return WORDS[random.nextInt(WORDS.length)] + (10 + random.nextInt(90));
    }

    public String hash(String pin) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = derive(pin, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean matches(String pin, String stored) {
        if (stored == null || pin == null || pin.isBlank()) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] expected = b64.decode(parts[3]);
        byte[] actual = derive(pin, b64.decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] derive(String pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(normalize(pin).toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }
}
