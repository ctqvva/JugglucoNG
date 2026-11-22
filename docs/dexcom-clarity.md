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

## The provider library

The request crypto needs Dexcom's application keys and session-key decoder.
Upstream packages them as `liblibre_receiver_skb.so` (it also serves upstream's
Libre receiver; NG only uses the exported `jg_clarity_abi` table) and the native code
`dlopen`s it by name. Without it the screen opens but every upload and sign-in fails
with "Cannot load Clarity provider".

| ABI | size | sha256 |
|---|---:|---|
| arm64-v8a | 4468408 | `7b946a3012ad278a74c9cccfc7da615582a494ae963439e8c9f0b6931b7fa038` |
| armeabi-v7a | 4819728 | `b8b2fad33c68e46762a11b6e08fa0e2e38a3f60d5acdae165af0097109877492` |

Source: `lib/<abi>/liblibre_receiver_skb.so` inside upstream's
`Juggluco-11.5.6.apk` (SourceForge, linked from juggluco.nl/Juggluco/download.html;
the copy used had sha256 `454bf168e74514f388d83234c3b65f9e3d0baf08924fe698d9c629c7489af4a0`).
Both builds depend only on `libc.so`.

To use it locally:

```sh
unzip -j Juggluco-11.5.6.apk 'lib/arm64-v8a/liblibre_receiver_skb.so' -d Common/src/main/jniLibs/arm64-v8a/
unzip -j Juggluco-11.5.6.apk 'lib/armeabi-v7a/liblibre_receiver_skb.so' -d Common/src/main/jniLibs/armeabi-v7a/
```

The inventory in `scripts/dist/build-inputs.json` lists both files, so the pinned
1.2.3-Alpha APK cannot supply them yet and the inventory is in bootstrap state. Ship
it with the documented procedure in [distribution.md](distribution.md#update-vendor-library--test--release--new-baseline):
a local release with the files present, then `scripts/update-build-inputs.sh --baseline <version>`.
Review redistribution of the library first.

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
byte maps, each affecting one output byte. Combining the byte maps matched
all 128 full-block probes. The output helper's raw byte substitutions become
affine after the ABI rows are applied.

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

The unresolved part is how the late helper uses that representation across
the lookup groups at `0x2c489d5` and `0x2de907c`. Each has 4,096 distinct
32-bit outputs with a 32-bit affine span. The last word lookup group in
`0x2656270` and its 16 terminal byte substitutions are affine. The remaining
work is to establish the nonlinear round boundaries through the late helper
and relate the context masks and folded words to a standard key schedule.
The measurements establish inverse AES structure within `D`. They leave the
complete block equivalence, AES variant, and independently buildable
single-block replacement unverified.

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
