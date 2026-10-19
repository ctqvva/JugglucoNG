package tk.glucodata.aidex;

import java.util.Arrays;

/**
 * Pure Java implementation of Ascon (v1.2).
 * Supports Ascon-128 and Ascon-128a.
 */
public class Ascon {

    public enum Variant {
        ASCON_128,
        ASCON_128A
    }

    // IV Constants
    // Ascon-128: k=128, r=64, a=12, b=6 -> IV = 0x80400c0600000000
    private static final long IV_128 = 0x80400c0600000000L;

    // Ascon-128a: k=128, r=128, a=12, b=8 -> IV = 0x80800c0800000000
    private static final long IV_128A = 0x80800c0800000000L;

    // Round Constants
    private static final long[] ROUND_CONSTANTS = {
            0x00000000000000F0L, 0x00000000000000E1L, 0x00000000000000D2L, 0x00000000000000C3L,
            0x00000000000000B4L, 0x00000000000000A5L, 0x0000000000000096L, 0x0000000000000087L,
            0x0000000000000078L, 0x0000000000000069L, 0x000000000000005AL, 0x000000000000004BL
    };

    /**
     * Decrypts ciphertext using specified Ascon variant.
     */
    public static byte[] decrypt(Variant variant, byte[] key, byte[] nonce, byte[] associatedData, byte[] ciphertext,
            byte[] tag) {
        if (key == null || key.length != 16)
            throw new IllegalArgumentException("Key must be 16 bytes");
        if (nonce == null || nonce.length != 16)
            throw new IllegalArgumentException("Nonce must be 16 bytes");
        if (associatedData == null)
            associatedData = new byte[0];
        if (ciphertext == null)
            ciphertext = new byte[0];

        int rate; // Bytes
        int aRounds = 12;
        int bRounds;
        long iv;

        if (variant == Variant.ASCON_128A) {
            rate = 16;
            bRounds = 8;
            iv = IV_128A;
        } else {
            rate = 8;
            bRounds = 6;
            iv = IV_128;
        }

        // State: x0, x1, x2, x3, x4
        long[] S = new long[5];

        // 1. Initialization
        S[0] = iv;
        long K0 = bytesToLong(key, 0);
        long K1 = bytesToLong(key, 8);
        S[1] = K0;
        S[2] = K1;
        S[3] = bytesToLong(nonce, 0);
        S[4] = bytesToLong(nonce, 8);

        permutation(S, aRounds);

        S[3] ^= K0;
        S[4] ^= K1;

        // 2. Associated Data
        if (associatedData.length > 0) {
            processData(S, associatedData, rate, bRounds, true); // Absorb
        }
        // Domain separation
        S[4] ^= 1L;

        // 3. Decryption
        byte[] plaintext = new byte[ciphertext.length];

        // P = C ^ S. Update S with C.
        // For Ascon-128a (Rate=16): Absorb C0, C1.
        // For Ascon-128 (Rate=8): Absorb C0.

        int len = ciphertext.length;
        int blocks = len / rate;
        int remain = len % rate;

        for (int i = 0; i < blocks; i++) {
            long C0 = bytesToLong(ciphertext, i * rate);
            long P0 = S[0] ^ C0;
            longToBytes(P0, plaintext, i * rate, 8);
            S[0] = C0;

            if (variant == Variant.ASCON_128A) {
                long C1 = bytesToLong(ciphertext, i * rate + 8);
                long P1 = S[1] ^ C1;
                longToBytes(P1, plaintext, i * rate + 8, 8);
                S[1] = C1;
            }

            permutation(S, bRounds);
        }

        // Partial block
        if (remain > 0) {
            int off = blocks * rate;
            for (int i = 0; i < remain; i++) {
                byte c = ciphertext[off + i];
                byte s = (byte) (getByte(S, i) & 0xFF);
                plaintext[off + i] = (byte) (s ^ c);
            }

            // Padding logic for State Update
            // S = S ^ (plaintext padded with 0x80)
            // Wait, for Decryption:
            // "The ciphertext is decrypted... S is updated with the ciphertext."
            // For partial: C is padded? No.
            // "The last ciphertext block... is absorbed."
            // Standard:
            // P_last = S_rate ^ C_last (truncated)
            // S_rate = S_rate ^ (P_last || 1 || 0...)

            byte[] p_pad = new byte[rate]; // Zero init
            System.arraycopy(plaintext, off, p_pad, 0, remain);
            p_pad[remain] = (byte) 0x80;

            long P0_pad = bytesToLong(p_pad, 0);
            S[0] ^= P0_pad;

            if (variant == Variant.ASCON_128A && rate > 8) {
                long P1_pad = bytesToLong(p_pad, 8);
                S[1] ^= P1_pad;
            }
        } else {
            // No partial data, just apply padding 0x80 to state
            S[0] ^= 0x8000000000000000L;
        }

        // 4. Finalization
        if (variant == Variant.ASCON_128) {
            S[1] ^= K0;
            S[2] ^= K1;
        } else {
            S[2] ^= K0;
            S[3] ^= K1;
        }

        permutation(S, aRounds);

        S[3] ^= K0;
        S[4] ^= K1;

        // 5. Verify Tag
        long T0 = S[3];
        long T1 = S[4];

        byte[] calcTag = new byte[16];
        longToBytes(T0, calcTag, 0, 8);
        longToBytes(T1, calcTag, 8, 8);

        if (tag != null) {
            int diff = 0;
            for (int i = 0; i < 16; i++) {
                diff |= (calcTag[i] ^ tag[i]);
            }
            if (diff != 0)
                return null;
        }

        return plaintext;
    }

    private static void processData(long[] S, byte[] data, int rate, int rounds, boolean absorb) {
        int len = data.length;
        int blocks = len / rate;
        int remain = len % rate;

        for (int i = 0; i < blocks; i++) {
            S[0] ^= bytesToLong(data, i * rate);
            if (rate == 16) {
                S[1] ^= bytesToLong(data, i * rate + 8);
            }
            permutation(S, rounds);
        }

        byte[] block = new byte[rate];
        if (remain > 0) {
            System.arraycopy(data, blocks * rate, block, 0, remain);
        }
        block[remain] = (byte) 0x80;

        S[0] ^= bytesToLong(block, 0);
        if (rate == 16) {
            S[1] ^= bytesToLong(block, 8);
        }

        permutation(S, rounds);
    }

    private static void permutation(long[] S, int rounds) {
        int startRound = 12 - rounds;
        for (int r = startRound; r < 12; r++) {
            S[2] ^= ROUND_CONSTANTS[r];
            S[0] ^= S[4];
            S[4] ^= S[3];
            S[2] ^= S[1];
            long T0 = ~S[0];
            long T1 = ~S[1];
            long T2 = ~S[2];
            long T3 = ~S[3];
            long T4 = ~S[4];
            T0 &= S[1];
            T1 &= S[2];
            T2 &= S[3];
            T3 &= S[4];
            T4 &= S[0];
            S[0] ^= T1;
            S[1] ^= T2;
            S[2] ^= T3;
            S[3] ^= T4;
            S[4] ^= T0;
            S[1] ^= S[0];
            S[0] ^= S[4];
            S[3] ^= S[2];
            S[2] = ~S[2];
            S[0] ^= rotr(S[0], 19) ^ rotr(S[0], 28);
            S[1] ^= rotr(S[1], 61) ^ rotr(S[1], 39);
            S[2] ^= rotr(S[2], 1) ^ rotr(S[2], 6);
            S[3] ^= rotr(S[3], 10) ^ rotr(S[3], 17);
            S[4] ^= rotr(S[4], 7) ^ rotr(S[4], 41);
        }
    }

    private static long rotr(long x, int n) {
        return (x >>> n) | (x << (64 - n));
    }

    private static long bytesToLong(byte[] b, int offset) {
        long l = 0;
        for (int i = 0; i < 8; i++) {
            if (offset + i < b.length)
                l = (l << 8) | (b[offset + i] & 0xFF);
            else
                l = (l << 8);
        }
        return l;
    }

    private static void longToBytes(long l, byte[] b, int offset, int len) {
        for (int i = 7; i >= 0; i--) {
            if (offset + i < b.length && i < len)
                b[offset + i] = (byte) (l & 0xFF);
            l >>>= 8;
        }
    }

    private static byte getByte(long[] S, int idx) {
        int wordIdx = idx / 8;
        int byteIdx = idx % 8;
        if (wordIdx > 1)
            return 0;
        long w = S[wordIdx];
        int shift = (7 - byteIdx) * 8;
        return (byte) ((w >>> shift) & 0xFF);
    }
}
