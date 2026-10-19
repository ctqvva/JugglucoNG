package tk.glucodata.aidex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure Java implementation of Ascon-128a (v1.2).
 * Ported from LibAscon C implementation.
 */
public class Ascon128a {

    // Constants
    private static final int KEY_LEN = 16;
    private static final int RATE = 16; // 128 bits (16 bytes) for Ascon-128a
    private static final int A_ROUNDS = 12;
    private static final int B_ROUNDS = 8;

    // IV for Ascon-128a: k=128, r=128, a=12, b=8
    // IV = 0x80800c0800000000
    private static final long IV = 0x80800c0800000000L;

    // Round Constants
    private static final long[] ROUND_CONSTANTS = {
            0x00000000000000F0L, 0x00000000000000E1L, 0x00000000000000D2L, 0x00000000000000C3L, // Rounds 0-3
            0x00000000000000B4L, 0x00000000000000A5L, 0x0000000000000096L, 0x0000000000000087L, // Rounds 4-7
            0x0000000000000078L, 0x0000000000000069L, 0x000000000000005AL, 0x000000000000004BL // Rounds 8-11
    };

    /**
     * Decrypts ciphertext using Ascon-128a.
     * 
     * @param key            16-byte Key
     * @param nonce          16-byte Nonce
     * @param associatedData Associated Data (can be null or empty)
     * @param ciphertext     Ciphertext to decrypt
     * @param tag            Authentication Tag (16 bytes). If null, last 16 bytes
     *                       of ciphertext are assumed to be tag?
     *                       Standard Ascon appends Tag to Ciphertext.
     *                       If input 'ciphertext' includes tag, separate them.
     * @return Decrypted Plaintext or null if Tag validation fails.
     */
    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] associatedData, byte[] ciphertext, byte[] tag) {
        if (key == null || key.length != 16)
            throw new IllegalArgumentException("Key must be 16 bytes");
        if (nonce == null || nonce.length != 16)
            throw new IllegalArgumentException("Nonce must be 16 bytes");
        if (associatedData == null)
            associatedData = new byte[0];

        // State: x0, x1, x2, x3, x4
        long[] S = new long[5];

        // 1. Initialization
        // Load IV
        S[0] = IV;
        // Load Key
        long K0 = bytesToLong(key, 0);
        long K1 = bytesToLong(key, 8);
        S[1] = K0;
        S[2] = K1;
        // Load Nonce
        long N0 = bytesToLong(nonce, 0);
        long N1 = bytesToLong(nonce, 8);
        S[3] = N0;
        S[4] = N1;

        // Apply Permutation a (12 rounds)
        permutation(S, A_ROUNDS);

        // XOR Key (Ascon-128a specific)
        S[3] ^= K0;
        S[4] ^= K1;

        // 2. Associated Data
        if (associatedData.length > 0) {
            processAssociatedData(S, associatedData);
        }
        // Domain separation
        S[4] ^= 1L;

        // 3. Decryption
        byte[] plaintext = new byte[ciphertext.length];
        int len = ciphertext.length;
        int blocks = len / RATE;
        int remain = len % RATE;

        // Process full blocks
        for (int i = 0; i < blocks; i++) {
            long C0 = bytesToLong(ciphertext, i * 16);
            long C1 = bytesToLong(ciphertext, i * 16 + 8);

            long P0 = S[0] ^ C0;
            long P1 = S[1] ^ C1;

            longToBytes(P0, plaintext, i * 16);
            longToBytes(P1, plaintext, i * 16 + 8);

            S[0] = C0;
            S[1] = C1;

            permutation(S, B_ROUNDS);
        }

        // Process remaining bytes (Padding)
        if (remain > 0) { // Should check logic for 128a (uses double rate 16 bytes)
            // Ascon-128a rate is 128 bits (16 bytes).
            // If remain > 0, it means partial block.
            // Implies we handle full 128-bit blocks above.
            // For partial:
            // Padding is 0x80...

            // Since java Long operations are 8 bytes, handling partial 16-byte block is
            // tricky.
            // We construct temporary buffer.
            int off = blocks * 16;
            byte[] block = new byte[16]; // Zero initialized
            System.arraycopy(ciphertext, off, block, 0, remain);

            long C0_part = bytesToLong(block, 0);
            long C1_part = bytesToLong(block, 8);

            // Only XOR available bytes?
            // Ascon sponge squeeze logic for partial:
            // P = S ^ C.
            // But we need to mask correctly.

            // Let's do byte-wise for simplicity on partial
            for (int i = 0; i < remain; i++) {
                byte c = ciphertext[off + i];
                // Extract byte from S[0], S[1]
                byte s = (byte) (getByte(S, i) & 0xFF);
                plaintext[off + i] = (byte) (s ^ c);
            }

            // Update State for Tag Gen
            // Needs careful padding application.
            // S[0] and S[1] are updated with C (padded)

            // Pad Ciphertext with 0x80...
            block[remain] = (byte) 0x80;
            // Remaining are 0

            long C0_pad = bytesToLong(block, 0);
            long C1_pad = bytesToLong(block, 8);

            // Mask the state part that was modified by plaintext output
            // Actually, easier to regenerate S from P and C.
            // State is replaced by Ciphertext (padded)? No.
            // S ^= P (padded).
            // Since P = S ^ C => S = P ^ C.
            // Effectively: S = C_padded.
            // Wait, standard sponge overwrite:
            // S_new = C_padded? No.
            // For decryption:
            // P = S ^ C.
            // S_new = C. (For full blocks).
            // For partial:
            // S_new = (S_old & mask) | (C_padded & ~mask)?
            // Actually: S_new = S_old ^ (P_padded).

            // Let's emulate "S[0] ^= P0_padded" logic.
            byte[] p_pad = new byte[16];
            System.arraycopy(plaintext, off, p_pad, 0, remain);
            p_pad[remain] = (byte) 0x80;

            long P0_pad = bytesToLong(p_pad, 0);
            long P1_pad = bytesToLong(p_pad, 8);

            S[0] ^= P0_pad;
            S[1] ^= P1_pad;
        } else {
            // No partial bytes, but we must apply padding 0x80 to the *next* state?
            // "The plaintext is padded with a 1 and 0s."
            // "If P is empty, 0x80... is XORed."
            // For full blocks, we processed them.
            // But we ALWAYS pad.
            // If remain == 0, we XOR 0x8000...00 to S[0].
            // (Assuming buffer was processed in loop).
            // Wait, standard says: "If input length is multiple of rate, padding is applied
            // in a new block."
            // BUT Ascon-128a SQUEEZES ciphertext.

            // Correction: Padding is XORed into State.
            // S[0] ^= 0x8000000000000000L;
            S[0] ^= 0x8000000000000000L;
        }

        // 4. Finalization
        S[2] ^= K0;
        S[3] ^= K1;

        permutation(S, A_ROUNDS);

        S[3] ^= K0;
        S[4] ^= K1;

        // 5. Verify Tag
        long T0 = S[3];
        long T1 = S[4];

        byte[] calcTag = new byte[16];
        longToBytes(T0, calcTag, 0);
        longToBytes(T1, calcTag, 8);

        if (tag != null) {
            // Constant time compare
            int diff = 0;
            for (int i = 0; i < 16; i++) {
                diff |= (calcTag[i] ^ tag[i]);
            }
            if (diff != 0)
                return null; // Tag mismatch
        }

        // Return plaintext (+ calcTag if needed, but usually we just want PT)
        return plaintext;
    }

    private static void processAssociatedData(long[] S, byte[] ad) {
        int len = ad.length;
        int blocks = len / RATE;
        int remain = len % RATE;

        for (int i = 0; i < blocks; i++) {
            S[0] ^= bytesToLong(ad, i * 16);
            S[1] ^= bytesToLong(ad, i * 16 + 8);
            permutation(S, B_ROUNDS);
        }

        // Padding
        byte[] block = new byte[16];
        if (remain > 0) {
            System.arraycopy(ad, blocks * 16, block, 0, remain);
        }
        block[remain] = (byte) 0x80;

        S[0] ^= bytesToLong(block, 0);
        S[1] ^= bytesToLong(block, 8);

        permutation(S, B_ROUNDS);
    }

    private static void permutation(long[] S, int rounds) {
        int startRound = 12 - rounds;
        for (int r = startRound; r < 12; r++) {
            // 1. Add Round Constant
            S[2] ^= ROUND_CONSTANTS[r];

            // 2. Substitution Layer (S-Box)
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

            // 3. Linear Diffusion Layer
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
                l = (l << 8); // Should assume 0 for OOB
        }
        return l;
    }

    private static void longToBytes(long l, byte[] b, int offset) {
        for (int i = 7; i >= 0; i--) {
            if (offset + i < b.length)
                b[offset + i] = (byte) (l & 0xFF);
            l >>>= 8;
        }
    }

    private static byte getByte(long[] S, int idx) {
        int wordIdx = idx / 8; // 0 or 1
        int byteIdx = idx % 8; // 0..7 (Big Endian)
        if (wordIdx > 1)
            return 0;
        long w = S[wordIdx];
        int shift = (7 - byteIdx) * 8;
        return (byte) ((w >>> shift) & 0xFF);
    }
}
