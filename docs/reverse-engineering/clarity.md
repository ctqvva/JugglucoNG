# Dexcom Clarity crypto provider: reverse-engineering archive

This document archives the technical analysis of the native crypto provider
(`liblibre_receiver_skb.so`) shipped in upstream Juggluco 11.5.6 for Dexcom Clarity
uploads, its white-box AES-256 session-key decoder, and the Billet-Gilbert-Etienne
(BGE) algebraic key-recovery procedure that reduces the provider to standard
`AES-256-CBC` and `HMAC-SHA256` with 32 compact keys.

For ordinary developer documentation on how JugglucoNG's Clarity uploader works, how
to run the test suites, and what to check if Dexcom changes compatibility, see
[`docs/dexcom-clarity.md`](../dexcom-clarity.md).

## Reference binaries

Reference source: `lib/<abi>/liblibre_receiver_skb.so` inside upstream's
`Juggluco-11.5.6.apk` (SourceForge, linked from `juggluco.nl/Juggluco/download.html`;
APK SHA-256 `454bf168e74514f388d83234c3b65f9e3d0baf08924fe698d9c629c7489af4a0`).

| Upstream reference ABI | Size (bytes) | SHA-256 | `jg_clarity_abi` RVA | `unwrap` RVA |
|---|---:|---|---|---|
| `arm64-v8a` | 4,468,408 | `7b946a3012ad278a74c9cccfc7da615582a494ae963439e8c9f0b6931b7fa038` | `0x2f20000` | `0x26591a4` |
| `armeabi-v7a` | 4,819,728 | `b8b2fad33c68e46762a11b6e08fa0e2e38a3f60d5acdae165af0097109877492` | `0x2e40000` | `0x2602c8d` (Thumb) |

## `jg_clarity_abi` layout

Both reference libraries export the `jg_clarity_abi` symbol matching `NativeABI` in
[`Common/src/main/cpp/net/clarity/native_abi.hpp`](../../Common/src/main/cpp/net/clarity/native_abi.hpp):

- Offset `0x0000` (8 bytes): `magic = 0x3249544e414c434a` (`"JCLANTI2"` little-endian)
- Offset `0x0008` (8 bytes): `version = 2`
- Offset `0x0010` (8 bytes): `unwrap` function pointer (64-bit pointer field on 64-bit, pointer + padding in the v2 layout)
- Offset `0x0018` (288 bytes): `uint8_t context[288]` (white-box round-key/offset context; 17 consecutive 16-byte slices live at offsets `0x10..0x11f` within `context`)
- Offset `0x0138` (4,096 bytes): `uint8_t decode[16][256]` (16 output byte-substitution rows applied at `position % 16`)
- Offset `0x1138` (8 bytes): `keyCount = 31`
- Offset `0x1140` (3,224 bytes = `31 × 104`): `NativeKey keys[31]`, each consisting of `char name[32]`, `uint64_t size` (`32` or `64`), and `uint8_t value[64]`

The ABI `unwrap` entry point takes `(const void *context, const uint8_t *cipher, size_t size, uint8_t *iv, uint8_t *out, int format)` with `size = 32`, a writable copy of the 16-byte `iv`, and `format = 1`. A nonzero return code signals a failed session-key import; on `0`, the caller maps each output byte `out[i]` through `decode[i % 16][out[i]]`.

## Role of the 32 keys in the wire protocol

| Key family | Count & size | Wire protocol usage |
|---|---|---|
| `WBCloakedSigKey` | 1 × 64 B (`HMAC-SHA256`) | Derives `DeviceId = base64url(trunc16(HMAC(WBCloakedSigKey, SoftwareId + HardwareId)))` sent in `Certificate/UserCert` and `updateSessionKeys` |
| `QEK_0..9` | 10 × 32 B (`AES-256-CBC`) | Encrypts outgoing `SignedMessage` bootstrap requests (`listOfUrls`, `Certificate/UserCert`, `updateSessionKeys`); client chooses one ID per request |
| `REK_0..9` | 10 × 32 B (`AES-256-CBC`) | Decrypts incoming `SignedMessage` bootstrap responses; server selects from the 10-key pool for `SoftwareId` |
| `HSK_0..9` | 10 × 64 B (`HMAC-SHA256`) | Signs outgoing bootstrap requests and verifies incoming bootstrap responses (16-byte truncated tag); server selects from the 10-key pool on responses |
| `SessionWrapKey` | 1 × 32 B (`AES-256-CBC`) | Decrypts the per-batch 32-byte `EncKey` and 32-byte `SigKey` returned by `updateSessionKeys` under `EncKey.IV`; `postBulkData` then uses only those ephemeral session keys (`PRIVATE`/`Private`) |

Because the server selects from the 10-key `REK_` and `HSK_` rotation pools when
answering bootstrap requests for `SoftwareId = "0300wAECCzACDwAA0000"`, all
`REK_0..9`, `HSK_0..9`, `WBCloakedSigKey`, `SessionWrapKey`, and at least one
`QEK_` key (all 10 are retained for rotation parity) are active in the G7 protocol.

## ARM64 white-box decoder investigation

In the ARM64 reference ELF, `jg_clarity_abi` is at RVA `0x2f20000` and its `unwrap`
relocation at offset `0x10` resolves to `0x26591a4`.

After applying `abi->decode[i % 16]`, format `1` implements 16-byte CBC decryption:
for ciphertext blocks `C[0]`, `C[1]`, …, the decoded output is `D(C[0]) XOR IV`
followed by `D(C[i]) XOR C[i-1]`, where `D` is the decoded single-block operation
with an all-zero IV. The ABI also overwrites its writable IV buffer with an encoded
form of the final ciphertext block, which `Engine` discards.

The CBC equation matched **579 inputs and 1,158 blocks** when the reference provider
was loaded with `dlopen` on an ARM64 Android emulator, covering single-bit changes to
ciphertext and IV as well as one-, two-, and three-block messages.

### Single-block stages and RVAs

For format `1` with a zero IV, `D` executes:

1. **Input transform** at `0x26784a0` (config at `0x28475b0`): 16 position-preserving
   reversible affine byte maps over $\mathbb{F}_2^8$.
2. **Core block routine** at `0x2656270`:
   - Reads 17 consecutive 16-byte slices of `abi->context` at offsets `0x10..0x11f`
     (`BlockMaterial::context[0..16]`).
   - Applies two initial affine lookup groups at `0x2888e54` and `0x2c3928b`
     (`inputRounds[0..1]`).
   - Runs 11 table rounds (`context[2..12]`) over six distinct `16 × 256 × 32-bit`
     lookup groups at `0x28e6110`, `0x2d2ba8c`, `0x285d184`, `0x2b9ec98`,
     `0x2dc984d`, and `0x2b926fc` following `inverseSchedule = {0, 1, 2, 3, 4, 1, 2, 3, 4, 1, 5}`
     and `InvShiftRows` index routing.
   - Calls the **late state helper** at `0x277848c`:
     - Applies `lateRows` at `0x28db930` and folds with `context[13]`.
     - Subroutine `0x270aa88` computes a seeded ELF-style hash over 16 context bytes.
     - Subroutine `0x27b66dc` groups 12 32-bit masks into three sets of four, takes
       byte parities of the XOR of the four masked words in each set, packs the
       resulting 24 bits as two 12-bit values, XORs them with the quotient and
       remainder of its tag divided by radix **93,139**, and recombines them in that
       radix.
     - Evaluates two 4,096-entry packed lookup groups at `0x2c489d5`
       (`firstPackedLookup`, with projection masks at `0x2634770` / `0x26341f0`,
       lookup tags at `0x2634f44` / `0x2634768`, and check tags at `0x2634268` /
       `0x2634a54`) and `0x2de907c` (`secondPackedLookup`, with per-column projection
       masks at `0x26348b8..0x26345d8` and tags at `0x2634264..0x26347d8`).
     - Folds `finalRound` at `0x2c85d9c` with `context[15..16]` and applies the 16
       terminal affine byte tables at `0x2d90602`, `0x2ce8bb8`, `0x2dcd86d`,
       `0x28d151e`, `0x2d05a58`, `0x289cb28`, `0x2d70d9c`, `0x2888d0c`,
       `0x2db3070`, `0x28d5804`, `0x2b60a28`, `0x2e82c5c`, `0x284d160`,
       `0x2d2b98c`, `0x2d2fa8c`, and `0x2d6cc34`.
3. **Output transform** at `0x271e5f4` (config at `0x2cfb0d8`) composed with
   `abi->decode[0..15]`: 16 position-preserving reversible affine byte maps.

[`block_decoder.cpp`](../../Common/src/main/cpp/net/clarity/block_decoder.cpp)
implements the complete single-block operation (`decodeBlock`) and two-block CBC
unwrapper (`unwrapSessionKey`) in standalone C++20. Using the extracted
`BlockMaterial`, `decodeBlock` matched the native ARM64 reference on **19,970** single
blocks and `unwrapSessionKey` matched **20,482 of 20,482** two-block results (including
invalid lengths and first- or second-block rejection cases).

## AES-256 reduction and BGE algebraic key recovery

All 96 rows of the six inverse table groups and the late rows match the AES S-box
differential distribution for every nonzero input difference. The relative linear
maps between the four output bytes of each table match the AES `InvMixColumns` matrix
up to reversible byte bases. Counting the non-affine layers (11 inverse rounds, the
late row layer, and the two packed radix-93,139 lookups) gives **14**, the AES-256
round count. The two leading rounds (`inputRounds[0..1]`) and `finalRound` are purely
affine over $\mathbb{F}_2$ and carry the initial and final key additions and linear
mixing.

In [`block_decoder.cpp`](../../Common/src/main/cpp/net/clarity/block_decoder.cpp),
`recoverSessionWrapKey(const BlockMaterial &)` applies a Billet-Gilbert-Etienne (BGE)
algebraic attack to `BlockMaterial::inverseRounds` and `context[3..12]`:

1. **Strip output linear encodings up to a column scalar**: For each group
   $g \in \{0..5\}$, column $c \in \{0..3\}$, and output row $i \in \{0..3\}$, using
   the `InvMixColumns` ratios (`0x0e`, `0x0b`, `0x0d`, `0x09`), it computes the unique
   $8 \times 8$ linear isomorphism $L^{(g,c)}_i = P^{(g)}_{4c+i} \circ (P^{(g)}_{4c})^{-1}$
   such that $(P^{(g)}_{4c})^{-1}(y_i(x) \oplus y_i(0)) = (\text{InvMix}_{i,0} \cdot 0x0e^{-1}) \odot (P^{(g)}_{4c})^{-1}(y_0(x) \oplus y_0(0))$,
   leaving only an unknown column scalar $\alpha^{(g)}_c \in GF(2^8)^*$.
2. **Recover input affine encodings**: For each byte position $\text{pos} = 4c + r$
   and candidate $\alpha \in GF(2^8)^*$, it tests whether
   $g(x) = S(\alpha^{-1} \odot \text{coeff}^{-1} \odot (P^{(g)}_{4c})^{-1}(y_0(x)))$
   is affine over $\mathbb{F}_2^8$, recovering the unique invertible linear map
   $A^{(g)}_{\text{pos}}$ and offset $q^{(g)}_{\text{pos}}$ satisfying
   $S^{-1}(A^{(g)}_{\text{pos}}(x) \oplus q^{(g)}_{\text{pos}}) = \alpha^{-1} \odot s(x)$.
3. **Inter-round basis matching**: Across consecutive transitions $(g_t, g_{t+1})$ of
   `inverseSchedule`, the output encoding of group $g_t$ feeds directly into the input
   encoding of group $g_{t+1}$ after `InvShiftRows`, requiring
   $A^{(g_{t+1})}_{4c+i} \circ P^{(g_t)}_{4c+i} = \text{Identity}$. This uniquely
   pins every column scalar $\alpha^{(g)}_c$ across all six groups and verifies the
   exact table decomposition across all 384 byte lookup tables.
4. **Round-key extraction and AES-256 key-schedule verification**: From the table
   output constant sums $\bigoplus_{r=0}^3 c^{(g_t)}_{4c+i,r}$ and `context[t + 3]`,
   it recovers the 10 unencoded Equivalent Inverse Cipher round keys
   $dw_{13} \dots dw_4$:
   $$dw^{(13-t)}_{4c+i} = q^{(g_{t+1})}_{4c+i} \oplus \left(P^{(g_t)}_{4c+i}\right)^{-1}\!\left(\text{context}[t+3][4c+i] \oplus \bigoplus_{r=0}^3 c^{(g_t)}_{4c+i,r}\right)$$
   Applying forward `MixColumns` yields the 10 standard AES-256 round keys
   $RK_{13} \dots RK_4$ (40 32-bit words $W_{55} \dots W_{16}$). The function verifies
   the exact AES-256 key-schedule recurrence $W_k = W_{k-8} \oplus f(W_{k-1})$ across
   all 32 overlapping words ($k = 24 \dots 55$) and rewinds 16 words ($k = 15 \dots 0$)
   to recover the 32-byte AES-256 key:
   `SessionWrapKey = f66d7b0887c23c8bae7e627659e87d073bcab450dac1aebb88389c3be3b72751`.

Evaluating the leading layers (`input`, `inputRounds[0..1]`, and $A^{(0)} \oplus q^{(0)}$)
on 1,000 random blocks (spanning all 128-bit affine maps) confirmed the exact identity
$C \oplus RK_{14}$. In `lateRounds`, each of the four 12-bit projections in
`firstProjections` and `secondProjections` has $\mathbb{F}_2$-rank 8 (combined rank 32
per column) and routes bytes in `InvShiftRows` order through `firstPackedLookup` and
`secondPackedLookup`, implementing rounds 12 through 14 with $RK_3 \dots RK_0$.
End-to-end, all internal and external white-box encodings cancel: the entire two-block
`unwrap` operation with ABI output decoding reduces to standard, unpadded
`AES-256-CBC` decryption (`aes256cbc(SessionWrapKey, iv, cipher, false)`).

## ARM32 (`armeabi-v7a`) cross-verification

Independent inspection and execution of the upstream `armeabi-v7a` provider
(`lib/armeabi-v7a/liblibre_receiver_skb.so`, `jg_clarity_abi` at `0x2e40000`, Thumb
`unwrap` at `0x2602c8d`) confirmed:

- All 31 application keys (3,224 bytes at ABI offset `0x1140`) are byte-for-byte
  identical to `arm64-v8a`.
- Although `context[288]` and `decode[16][256]` are a distinct white-box compilation
  for 32-bit ARM, executing the ARM32 native `unwrap` and applying its `decode` rows
  matched **20,482 of 20,482** two-block reference outputs and reduced to the exact
  same 32-byte `SessionWrapKey`.

## Reproducible extraction and verification tools

- [`tools/clarity-material.cpp`](../../tools/clarity-material.cpp) — Loads the pinned
  ARM64 provider on an ARM64 Android device/emulator, extracts the white-box tables
  and 31 application keys, runs `recoverSessionWrapKey`, and by default writes a
  **3,368-byte key-only** `JGCLMAT` v1 file (`blockSize = 0`, `decodeSize = 0`, 32
  keys). Pass `--full` to retain the 193 KiB white-box tables (197,552 bytes total),
  or `--strip <in.mat> <out.mat>` to strip an existing full file to key-only.

  ```sh
  aarch64-linux-android26-clang++ -std=c++20 -O2 -static-libstdc++ \
    -I Common/src/main/cpp -I Common/src/main/cpp/share \
    tools/clarity-material.cpp \
    Common/src/main/cpp/net/clarity/{material,block_decoder,bytes,logging}.cpp \
    -lz -ldl -o clarity-material
  adb push clarity-material liblibre_receiver_skb.so /data/local/tmp/
  adb shell chmod 700 /data/local/tmp/clarity-material
  adb shell /data/local/tmp/clarity-material \
    /data/local/tmp/liblibre_receiver_skb.so /data/local/tmp/clarity.mat
  adb pull /data/local/tmp/clarity.mat
  ```

- [`tools/clarity-material-check.cpp`](../../tools/clarity-material-check.cpp) —
  Compares `--builtin`, a 3,368-byte key-only `.mat` file, or a 197,552-byte full
  `.mat` file against a reference stream of two-block `(ciphertext, iv, expected)`
  cases from the native provider:

  ```sh
  ./material-check --builtin < reference-stream.bin
  ./material-check clarity.mat < reference-stream.bin
  ```

- [`tools/clarity-material-engine-check.cpp`](../../tools/clarity-material-engine-check.cpp) —
  Verifies all 32 keys, key selection coverage, all 20,482 reference unwrap cases, and
  a full `Provider` session-import and bulk payload/acknowledgment round-trip using
  either `--builtin` or a `.mat` file:

  ```sh
  ./material-engine-check --builtin reference-stream.bin
  ./material-engine-check clarity.mat reference-stream.bin
  ```

- [`tools/test-clarity-provider.py`](../../tools/test-clarity-provider.py) — Supports
  comparing two ABI-compatible `.so` providers (or a `.so` provider against a `.mat`
  file or `builtin`) across 387 synthetic ciphertext/IV pairs (1,161 operations):

  ```sh
  python3 tools/test-clarity-provider.py --compare /path/to/reference.so builtin
  ```
