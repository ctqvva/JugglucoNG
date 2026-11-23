# Dexcom Clarity upload

Settings → Sharing → **Dexcom Clarity** uploads sensor glucose to the user's own Clarity
account, so a clinic that works from Clarity sees the data. It is a port of upstream
Juggluco 11.5.6's native uploader (`Common/src/main/cpp/net/clarity`, GPL-3.0-or-later),
requested in [issue 790](https://github.com/ctqvva/JugglucoNG/issues/790).

## How it works

- **Sign-in** is Dexcom's own OAuth page in a WebView (`ClarityLoginScreen`, window
  `FLAG_SECURE`, only `https://*.dexcom.com`/`*.dexcom.eu` may load). The `dexcomg7:`
  callback goes straight to native code, which exchanges the code and discovers the
  account's regional endpoints. Tokens, the account's TLS client certificate and the
  outbox stay in native code and never cross JNI. If discovery fails after Dexcom
  accepted the sign-in, its tokens are kept (also across restarts) and the settings
  screen offers **Finish pending sign-in**; starting a new sign-in discards them.
- **Upload** runs on a native worker thread. It snapshots the native sensor streams
  (Dexcom, Libre 3 and the other natively stored sensors), keeps a memory-mapped
  outbox under `<app data>/clarity-indexed/<account>/`, and posts batches over mutual TLS.
  It wakes on every new reading (`wakewithcurrent`), on network changes and every
  five minutes, and backs off exponentially on errors with the batch kept.
- **Values** are the ones the app shows: each uploaded reading goes through
  `resolveExportedMgdl()` (calibration and chosen lane), like Nightscout,
  the webserver and LibreView. Libre 3 history records have no raw value, so
  they use the auto lane there too. The snapshot keeps stored values and where
  each reading lives; a reading is resolved only once it would otherwise be
  selected for a batch (at most 256), so an up-to-date scan makes no Java calls.
  A calibrated value outside 20–600 is not sent at all (never the stored value
  in its place), and the next reading takes its slot.
- **Which sensor** a reading may come from: the one activated last, from its
  first usable reading on. Upstream ordered sensors by planned wear end, which
  blocked a 10-day G7 that replaced a 15-day Libre early. A sensor's first
  reading moves earlier when backfill arrives after it was first seen.
- **Storage** maps through its own file descriptor and logs only the operation
  and errno, never a path: outbox paths contain the Dexcom account ID. A
  checkpoint restarts only when it provably holds an unfinished first creation
  (4096 zero bytes, or two slots with slot 1 untouched and slot 0 empty or at
  generation 1). Any other damaged or truncated checkpoint stops uploads rather
  than forget acknowledgments.
- **Options** live at the end of `Tings` (`settings->data()->clarity`): enabled,
  start date (default: 14 days before first start), Libre 3 history instead of
  stream, and amounts.
- **Amounts** (insulin/carbs/finger sticks) exist natively but need label
  categories, which NG has no screen for yet. The NG screen never turns them on.

The uploader identifies itself as Dexcom's G7 app. It is unofficial and Dexcom can
break it at any time.

## Crypto provider and key material

The request crypto needs 31 application keys (`QEK_0..9`, `REK_0..9`, `HSK_0..9`,
`WBCloakedSigKey`) and the 32-byte AES-256 session-wrapping key (`SessionWrapKey`).
Upstream packages them inside `liblibre_receiver_skb.so` alongside its white-box
session-key decoder. JugglucoNG replaces the native provider executable entirely
with portable C++20 and standard `AES-256-CBC`/`HMAC-SHA256` (`engine.cpp`,
`cipher.cpp`, `block_decoder.cpp`, `material.cpp`), and does not package or
`dlopen` `liblibre_receiver_skb.so`.

In application builds (`CLARITY_JUGGLUCO_LOG`), `Engine` loads only from the
app-private file `<app data>/clarity-indexed/clarity.mat` (`O_NOFOLLOW`, verified
regular file owned by the app UID with mode `0600`, inside an `allowBackup="false"`
app data directory; paths and key contents are never logged). Environment variable
and system property overrides are compiled out of app builds, and there is no
`dlopen` fallback. Without `clarity.mat`, sign-in discovery and uploads report
`Clarity crypto material is not installed`.

| Upstream reference ABI | size | sha256 |
|---|---:|---|
| arm64-v8a | 4468408 | `7b946a3012ad278a74c9cccfc7da615582a494ae963439e8c9f0b6931b7fa038` |
| armeabi-v7a | 4819728 | `b8b2fad33c68e46762a11b6e08fa0e2e38a3f60d5acdae165af0097109877492` |

Reference source: `lib/<abi>/liblibre_receiver_skb.so` inside upstream's
`Juggluco-11.5.6.apk` (SourceForge, linked from juggluco.nl/Juggluco/download.html;
the copy used had sha256 `454bf168e74514f388d83234c3b65f9e3d0baf08924fe698d9c629c7489af4a0`).

## Build

The phone flavour passes `-DCLARITY=ON`; the uploader is built for `arm64-v8a` and
`armeabi-v7a` only. Every flavour has the JNI entry points; without the uploader they
report "not built" rather than failing to link.

`ContextHTTPS` gained two per-request options for it: `client` (a TLS client
certificate for that one connection) and `location` (the `Location` header of the
final response; redirects are still the caller's decision).
`tools/test-context-https.py` covers both. `tools/test-clarity-native.py` runs the
outbox and storage on the host: sensor replacement, late backfill, which readings
are resolved, interrupted checkpoint creation and truncated saved checkpoints.

## Offline provider tests

Run `python3 tools/test-clarity-provider.py` with a C++20 compiler, zlib headers and
OpenSSL available through `pkg-config`. It builds the real `Engine` and `Provider`
against synthetic fixture libraries. The tests check standard AES and HMAC vectors,
application key selection, session-key import, preservation of the previous session
when rekeying fails, and detection of a deliberately changed decoder.

The provider ABI takes a 32-byte ciphertext, a writable copy of the 16-byte IV,
the provider's context and format `1`. The engine treats a nonzero status as a
failed import. After success it maps each output byte through the provider's
decode row at `position % 16`. The fixture exercises that contract with a
synthetic transform. A compatible implementation still needs the real decoder
algorithm and appropriately licensed material. Uploading with a JugglucoNG
identity also needs a Dexcom registration authorized for Clarity uploads.

Compare two trusted ABI-compatible host libraries with
`python3 tools/test-clarity-provider.py --compare /path/to/reference /path/to/candidate`.
For Android libraries, use the NDK target compiler for the library's ABI. For
example, with the NDK's `bin` directory on `PATH`:

```sh
python3 tools/test-clarity-provider.py \
  --android-compiler aarch64-linux-android26-clang++ --output ./clarity-provider-test
```

Use `armv7a-linux-androideabi26-clang++` for `armeabi-v7a`. Run the executable
on the matching Android ABI with the absolute paths to both libraries as its two
arguments. Comparison uses 387 synthetic ciphertext and IV pairs and checks all
three import operations. It reports counts or the first mismatch, and requires at
least one successful import. The runner builds without OAuth or upload transport
and emits no key values. A successful comparison covers those inputs only.

## ARM64 decoder investigation

The ARM64 library extracted from the public 11.5.6 APK matched the SHA-256 in
the provider table above. In that ELF, `jg_clarity_abi` is at virtual address
`0x2f20000`. The relocation for its `unwrap` pointer at offset `0x10` resolves
to `0x26591a4`. These addresses are relative to the library load base.

After the engine applies its ABI output decode rows, format `1` implements
16-byte CBC decryption. For ciphertext blocks `C[0]`, `C[1]`, and so on, the
decoded output is `D(C[0]) XOR IV` followed by `D(C[i]) XOR C[i-1]`. Here `D`
is the decoded one-block operation with an all-zero IV. The ABI also overwrites
its IV buffer with an encoded form of the final ciphertext block. The engine
discards this update when it returns from a session-key import.

The CBC equation matched **579 inputs and 1,158 blocks** when the actual
provider was loaded with `dlopen` on an ARM64 Android emulator. The inputs
included single-bit changes to the ciphertext and IV, as well as varied
one-, two-, and three-block messages. Each multiblock result was checked
against independent single-block invocations and ordinary bytewise XOR. The
same equation held when tracing the real ARM64 instructions offline. These
comparisons establish the chaining and leave `D` as a reference operation.

### Single-block operation

For format `1` with a zero IV, `D` runs the input transform at `0x26784a0`,
the block routine at `0x2656270`, the output helper at `0x271e5f4`, and the
ABI decode rows. Calling these stages separately reproduced `D` on 128
single blocks. For both the input transform and the output helper composed
with the ABI rows, exhaustive single-byte sweeps found 16 reversible affine
byte maps that preserve byte positions. Each composed map matched 1,024
random blocks and 7,680 pairwise bit perturbations on random backgrounds.
The output helper's raw byte substitutions become affine after the ABI rows
are applied.

The block routine reads 17 consecutive 16-byte regions of the context at
offsets `0x10` through `0x11f`. Changing a context bit changed the decoded
result. The first two lookup groups are affine. The stages at offsets `0x30`
through `0xd0` use six distinct 16-row groups. All 96 rows match the AES
S-box differential distribution for every nonzero input difference. The
table-index routing follows inverse ShiftRows. Comparing the relative linear
maps between the four output bytes of each table, which cancels the input
substitution, matched the AES inverse MixColumns matrix up to reversible
changes of byte basis in all four columns of all six groups.

The late helper at `0x277848c` changes the state representation. Portable
C++ versions of its two arithmetic subroutines matched the actual ARM64
instructions on 512 inputs apiece. `0x270aa88` performs a seeded ELF-style
hash over 16 context bytes. `0x27b66dc` groups 12 masks into three sets of
four, then takes byte parities of the XOR of the four masked words in each
set. It packs the resulting 24 bits as two 12-bit values, XORs them with the
quotient and remainder of its tag divided by 93,139, and recombines them in
that radix.

Both 4,096-entry lookup groups at `0x2c489d5` and `0x2de907c` take four
indices per column. They use the first projection's quotient from the current
column and its remainder from the previous column, plus the second projection's
quotient and remainder from the two other columns. The first lookup applies
two sets of index tags and checks that the results agree. The second uses
tags in the projections and XORs its result with the context and the last fold.

[`block_decoder.cpp`](../Common/src/main/cpp/net/clarity/block_decoder.cpp)
implements the complete single-block operation in standalone C++20. It takes
`BlockMaterial` from the caller and matched the native ARM64 reference on
19,970 blocks. The comparison used the verified library's material in memory.
It exercised exhaustive single-byte values, pairwise perturbations and random
full blocks. Both out-of-range indices and inconsistent redundant data were
rejected.

[`unwrapSessionKey`](../Common/src/main/cpp/net/clarity/block_decoder.hpp) adds
the two-block CBC step and the same failure behavior as the ABI. A static
ARM64 build of the complete path matched **20,482** two-block results from the
real provider, including invalid lengths and first- or second-block failures.
Only the reference process loaded the provider library.

### Portable provider material

By default, `tools/clarity-material.cpp` extracts the white-box tables from the
pinned Juggluco 11.5.6 ARM64 provider in memory, recovers `SessionWrapKey` via
`recoverSessionWrapKey` (verifying the 10-round AES-256 key schedule), drops all
lookup tables, and writes a **3,368-byte key-only file** (`JGCLMAT` v1 with
`blockSize = 0`, `decodeSize = 0`, and 32 keys: the 31 application keys plus
`SessionWrapKey`). Pass `--full` to retain the 193 KiB white-box tables for
table-level testing, or `--strip <in.mat> <out.mat>` to convert an existing full
file to the 3,368-byte key-only format. The file is **not** part of the
repository or the APK: it contains vendor key material and must not be
redistributed.

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

In the Android app (`CLARITY_JUGGLUCO_LOG`), `Engine` loads only from the
app-private `<app data>/clarity-indexed/clarity.mat` (`0600`, owned by the app
UID), which can also be installed via `Natives.clarityImport` (passing either a
key-only or full `.mat` payload writes the 3,368-byte key-only file with mode
`0600`). In standalone host/NDK test runners (`!CLARITY_JUGGLUCO_LOG`), `Engine`
also accepts `CLARITY_MATERIAL_FILE`, `debug.juggluco.clarity_material`, or a
`.mat`/`.clarity-material` constructor path:

```sh
# Compare the 3,368-byte key-only material file with the real provider's two-block results.
./material-check clarity.mat < reference-stream.bin

# Exercise Engine and Provider protocol round-trips on host or Android.
./material-engine-check clarity.mat reference-stream.bin
```

The committed checks are `python3 tools/test-clarity-material.py` (file format,
key-only stripping, rejection cases and BGE white-box AES-256 key recovery) and
`python3 tools/test-clarity-native.py` (outbox and storage). The
reference-stream checks are local because they need the vendor library.

### AES-256 reduction and BGE key recovery

The six inverse table groups and the late rows all match the AES S-box
differential distribution for every nonzero input difference. The relative
linear maps between the four output bytes of each table match the AES inverse
MixColumns matrix up to reversible byte bases. Counting the non-affine layers
(11 inverse rounds, the late row layer and the two packed lookups) gives 14,
the AES-256 round count. The two leading rounds and the final round are affine;
they carry the initial and final key additions and the linear mixing.

In [`block_decoder.cpp`](../Common/src/main/cpp/net/clarity/block_decoder.cpp),
`recoverSessionWrapKey` applies a Billet-Gilbert-Etienne (BGE) algebraic
analysis to `BlockMaterial::inverseRounds` and `context[3..12]`:

1. For each of the six inverse table groups and four columns, it strips the
   per-byte output linear encodings $P^{(g)}_{4c+i}$ up to a single column
   scalar $\alpha^{(g)}_c \in GF(2^8)^*$ using the `InvMixColumns` row ratios.
2. For each input byte position and candidate $\alpha^{(g)}_c$, it recovers the
   unique input affine map $A^{(g)}_{\text{pos}}(x) \oplus q^{(g)}_{\text{pos}}$
   that linearizes $S(\alpha^{-1} \odot f_{\text{pos}}(x) \oplus d_0)$.
3. Matching $A^{(g_{t+1})}_{4c+i} \circ P^{(g_t)}_{4c+i} = \text{Identity}$
   across consecutive rounds of `inverseSchedule` uniquely determines every
   $\alpha^{(g)}_c$ and verifies the exact decomposition of all 384 byte
   lookup tables.
4. From the inter-round constant sums and `context[3..12]`, it recovers the 10
   unencoded Equivalent Inverse Cipher round keys $dw_{13} \dots dw_4$, applies
   forward `MixColumns` to obtain standard AES round keys $RK_{13} \dots RK_4$,
   verifies the AES-256 key schedule recurrence across all 40 32-bit words, and
   rewinds the key schedule to the 32-byte AES-256 key $K = RK_0 \parallel RK_1$.

Evaluating the leading layers (`input`, `inputRounds[0..1]` and
$A^{(0)} \oplus q^{(0)}$) on 1,000 random blocks (sufficient to span all
128-bit affine functions) confirmed the exact identity $C \oplus RK_{14}$. In
`lateRounds`, each of the four 12-bit projections in `firstProjections` and
`secondProjections` has $\mathbb{F}_2$-rank 8 (combined rank 32 per column) and
routes bytes in `InvShiftRows` order through `firstPackedLookup` and
`secondPackedLookup`, implementing rounds 12 through 14 with $RK_3 \dots RK_0$.
End-to-end, all internal and external white-box encodings cancel: the entire
two-block `unwrap` operation with ABI output decoding reduces to standard,
unpadded `AES-256-CBC` decryption (`aes256cbc(K, iv, cipher, false)`).

When `Engine` loads a `ProviderMaterial` whose keys include `SessionWrapKey`
(the 3,368-byte key-only format without white-box tables), it unwraps session
keys directly via `aes256cbc`. When loaded from a full `BlockMaterial`, `Engine`
recovers the AES-256 key via `recoverSessionWrapKey` and verifies that
`aes256cbc` agrees with the 14-round decoder on every import. Both the
3,368-byte key-only `Engine` and the table-backed `Engine` matched
**20,482 of 20,482** two-block reference outputs from the upstream ARM64
provider.

Independent inspection of the upstream `armeabi-v7a` library
(`lib/armeabi-v7a/liblibre_receiver_skb.so`, `jg_clarity_abi` at `0x2e40000`,
Thumb `unwrap` at `0x2602c8d`) confirmed that its 31 application keys
(3,224 bytes at ABI offset `4416`) are byte-for-byte identical to `arm64-v8a`.
Its white-box `context[288]` and `decode[16][256]` tables are a distinct
white-box compilation, yet executing the ARM32 native `unwrap` and applying its
`decode` rows matched **20,482 of 20,482** reference two-block outputs and
reduced to the exact same 32-byte `SessionWrapKey`.

### Role of the 32 keys in the wire protocol

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
`QEK_` key (all 10 are retained for rotation parity) are active in the G7
protocol. Because these 32 keys are vendor key material, they are never
committed to source control.

### Authorized Clarity upload registration

Dexcom's developer portal offers sandbox credentials and upgrades for access
to Dexcom-generated data. Its public endpoints and scopes include no Clarity
write grant. A separate inquiry can ask whether Dexcom offers an inbound
Clarity integration for patient-authorized uploads and, if so, request its
supported client registration, sandbox, and upload specification. Dexcom
lists Developer Support Requests and Strategic Partnerships as intake routes.
[Scopes and Access](https://developer.dexcom.com/docs/dexcom/scopes-access) ·
[Endpoint Overview](https://developer.dexcom.com/docs/dexcomv3/endpoint-overview) ·
[Partner with Dexcom](https://www.dexcom.com/en-us/global-partner-intake-requests)
