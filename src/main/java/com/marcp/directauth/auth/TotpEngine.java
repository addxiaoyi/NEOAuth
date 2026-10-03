package com.marcp.directauth.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public final class TotpEngine {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpEngine() {}

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    public static boolean verify(String secret, String code, int window, int timeStepSeconds) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long current = System.currentTimeMillis() / 1000 / Math.max(1, timeStepSeconds);
        for (long offset = -Math.max(0, window); offset <= Math.max(0, window); offset++) {
            if (constantTimeEquals(generate(secret, current + offset), code)) return true;
        }
        return false;
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + issuer + ":" + account
                + "?secret=" + secret + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String generate(String secret, long counter) {
        try {
            byte[] key = decode(secret);
            byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid TOTP secret", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static String encode(byte[] bytes) {
        StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                output.append(ALPHABET.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) output.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return output.toString();
    }

    private static byte[] decode(String input) {
        String normalized = input.replace("=", "").replace(" ", "").toUpperCase();
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char value : normalized.toCharArray()) {
            int digit = ALPHABET.indexOf(value);
            if (digit < 0) throw new IllegalArgumentException("Invalid Base32 character");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        return index == output.length ? output : Arrays.copyOf(output, index);
    }
}
