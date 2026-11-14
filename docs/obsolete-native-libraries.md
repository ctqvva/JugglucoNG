# Obsolete distribution native libraries

Audit baseline: source commit `91fed63ff`; vendor bytes restored from the pinned
1.2.3-Alpha APK using `scripts/restore-build-inputs.sh --apk`. Its SHA-256 is
`6374954ca6c9c7681434343220437437a8378748c303975af92c46c7d74b74b1`.
Both `arm64-v8a` and `armeabi-v7a` were inspected with NDK 29
`llvm-readelf -d`, `llvm-nm -D --defined-only`, and `llvm-strings`.
The load-path audit was rechecked after rebasing onto `609a6b680`.

## Per-library decisions

| Library | Decision | Evidence |
| --- | --- | --- |
| `libalgorithm-jni.so` | Remove, both ABIs | JNI exports target `ist.com.sdk.AlgorithmTools`. That bridge has no callers or reflective lookup in production sources. Current `AnytimeAlgorithm` and `AnytimeQr` use the existing Kotlin implementation. The unused bridge and its keep rules are left alone; no algorithm change. |
| `libcgat-lib.so` | Remove, both ABIs | JNI exports target `com.microtechmd.cgat.CGA`/`CGATools`; neither package nor a loader exists in the app. This is distinct from the externally supplied `com.microtechmd.blecomm` loader. |
| `libobjectbox-jni.so` | Remove, both ABIs | Exports target `io.objectbox`; no ObjectBox code or dependency. Current persistence uses Room. |
| `libscannative.so` | Remove, both ABIs | Exports target `com.huawei.hms.scankit.util.OpencvJNI`; no ScanKit code or dependency. Existing ML Kit, CameraX and ZXing dependencies stay unchanged. |
| `libjniPdfium.so` | Remove, both ABIs | Exports target `com.shockwave.pdfium.PdfiumCore`; no Pdfium consumer or dependency. `StatsReportExporter` creates reports with `android.graphics.pdf.PdfDocument`. |
| `libmodpdfium.so` | Remove, both ABIs | Only candidate `libjniPdfium.so` needs it; it needs `libmodft2.so`. |
| `libmodft2.so` | Remove, both ABIs | Only candidate `libmodpdfium.so` needs it; it needs `libmodpng.so`. |
| `libmodpng.so` | Remove, both ABIs | Only candidate `libmodft2.so` needs it. Android report rendering does not use this library. |
| `libmarsxlog.so` | Remove, both ABIs | Exports target `com.tencent.mars.xlog.Xlog`; no Mars/Xlog code or dependency. Its dependency on `libc++_shared.so` is outgoing, not a reason to remove the shared C++ runtime. |
| `libmmkv.so` | Remove, both ABIs | Dynamic JNI registration; MMKV class/method strings identify the owner. No MMKV consumer, loader or dependency. |
| `libgetuiext3.so` | Remove, ARM32 only | Separately inspected: exports `Java_com_igexin_dms_core_NativeCaller_doDaemon` and `JNI_OnLoad`, with registration owner `com/igexin/push/extension/mod/SecurityUtils`. No Igexin/Getui Java package, manifest service, loader or dependency. No retained vendor library needs or names it. No ARM64 counterpart exists in the inventory. |
| Generated `libnative.so` | Retain, actively used, both ABIs | CMake builds `abbotttest.cpp` as an executable linked against `g`. `rightlib.rightlib()` executes it, and `testrightlib()` deletes a candidate Libre library if it fails. Although startup bypasses import with `Applic.includeLib = true`, `ScanNfcV` can still call `getlibrary.openlibrary()` after a failed `switchgen2()`, leading through `Openfile.getlib()` to this executable. The user explicitly confirmed retention after reviewing this path. Its CMake target and the production `g` target stay unchanged. |

## Load-path and dependency audit

The source audit covers Java/Kotlin `System.loadLibrary`, `System.load`, native
declarations and reflection, plus C/C++ `dlopen`, `dlsym`, `dlvsym`, bypass/fake
loaders and constructed filenames. Production explicit Java loads are `g` and
the optional external `blecomm-lib`; `algorithm-jni` belongs to the unused bridge
described above. No new library is supplied for that external loader.

Constructed native paths remain unchanged: Libre uses `packagepath` and
`nativelibpath` (`libcalibrate.so` mutates to `libcalibrat2.so`), Libre 3 uses
`liblibre3extension.so`, `libcrl_dp.so` and its `libinit.so` package resource;
CareSens uses `libCALCULATION.so`. Network and Dexcom code opens platform
`libcrypto.so`/`libssl.so`, including system paths and namespace bypasses.
The legacy Sibionics loader names `libnative-algorithm-*`, `libdata-handle-lib*`
and related libraries already excluded by Gradle; it does not name these
candidates. Existing managed Sibionics functionality stays unchanged.

The resolved `mobileReleaseRuntimeClasspath` contains no CGAT, ObjectBox,
ScanKit, Pdfium, Mars, MMKV or Igexin dependency. Native libraries merged from
the existing ML Kit/CameraX dependencies (`libbarhopper_v3.so`,
`libimage_processing_util_jni.so`, `libsurface_util_jni.so`), AndroidX graphics,
and zstd were also inspected for `DT_NEEDED` references to the retired set;
none were found in either ARM ABI. The same check covers freshly built `g`
and the retained `libnative.so` executable.

Across all inventoried ELF files in both ABIs, no retained library has a
`DT_NEEDED` edge or library-name string naming a retired candidate. Candidate
edges are only the PDF chain above. Their other dependencies are platform
libraries, except `libmarsxlog.so` needing the retained `libc++_shared.so`.
None of the retained vendor ELFs imports `dlopen`; `libcrl_dp.so` and
`liblibre3extension.so` import `dlsym` but contain no retired library names.
`libinit.so` is a ZIP resource, not ELF; its six entries contain no native code.
Retained vendor inputs are `libCALCULATION.so`, `libc++_shared.so`,
`libcalibrat2.so`, `libcrl_dp.so`, `libinit.so`, `liblibre3extension.so` in both
ABIs and `libcalibrate.so` in ARM32. Production `libg.so` is unchanged.

## Verification

- `python3 -m unittest discover -s scripts/dist/tests -v`: 103 tests passed.
  Named retired-library negative controls cover all four phone/Wear and dub
  distribution variants, in both ABIs; SDK signature/badging output is mocked
  in these unit tests. Existing signing, source-integrity and lifecycle tests
  remain enabled. The new ARM64 Getui negative control failed before the
  cross-ABI verifier fix (`ValueError not raised`) and passes afterwards.
- Review follow-up: re-adding a retired library for only one ABI through the
  updater must exempt that exact active entry from both Gradle exclusions and
  APK retirement checks. The added lifecycle regression reproduced rejection
  of approved ARM64-only and ARM32-only re-adds before the fix. It now accepts
  each approved copy, rejects a changed hash, and rejects the still-retired ABI.
  Real Gradle JNI merge checks also retained each approved
  `libalgorithm-jni.so` byte-for-byte while excluding a deliberately stale copy
  in the other ABI. These temporary re-adds were removed and the original
  13-input/21-retirement inventory restored unchanged afterward.
  Phone/Wear release assembly then passed again for both ARM ABIs (3m 43s),
  with real APK verification against the explicit local fallback certificate;
  the production certificate gate continued to reject those local APKs.
- A real empty JNI input directory was restored using the unchanged pinned APK:
  exactly 13 active inputs returned, and none of the 21 retired paths returned.
- `scripts/update-build-inputs.sh --apk ...` regenerated the inventory; the
  source URL/hash and retained-library hashes are unchanged. No bootstrap or
  rebaseline is needed. Removed vendor payload totals 24,900,904 uncompressed
  bytes (14,197,384 ARM64 and 10,703,520 ARM32).
- `./gradlew :Common:assembleMobileRelease :Common:assembleWearRelease
  --no-daemon --offline` passed on the rebased branch with Java 21, an isolated
  Gradle cache and both ARM ABIs (5m 29s). The initial pre-rebase online build
  also passed after resolving dependencies missing from the offline cache.
- The final build deliberately had all 21 retired baseline files reintroduced
  into `src/main/jniLibs`: distribution input preflight rejected them, and direct
  Gradle assembly excluded them from both APKs. Afterward the injected files
  were removed and input checks plus `scripts/update-build-inputs.sh --ci --apk
  ...` passed again.
- Real phone/Wear release APKs passed `dist.verify_apk` with an explicit local
  fallback certificate: signatures, package/version, both ARM ABIs, retained
  vendor hashes and all retirement exclusions. The unchanged default production
  certificate gate rejected these local APKs as expected. Both retain `g` and
  `libnative`; the phone retains ML Kit and CameraX native libraries in both ABIs.
  None of their 25 phone / 17 Wear ELF dependency tables references a retired
  library. These local APKs are compile/packaging checks, not production releases.
- Production-signed canonical builds and physical sensor/device testing were
  not run. Existing resource/deprecation diagnostics were emitted during the
  successful builds; no separate lint pass is claimed.

### ELF `DT_NEEDED` inventory

Names below are identical on both ABIs unless a row says otherwise. The
non-ELF `libinit.so` is excluded from this table. Duplicate `libg.so` entries
in the original `libcalibrat2.so` dynamic sections are collapsed for readability.

| Library | Needed libraries |
| --- | --- |
| `libCALCULATION.so` | liblog.so, libc.so, libm.so, libstdc++.so, libdl.so |
| `libalgorithm-jni.so` | arm64-v8a: liblog.so, libc.so, libm.so, libstdc++.so, libdl.so; armeabi-v7a: libdl.so, liblog.so, libc.so, libm.so, libstdc++.so |
| `libc++_shared.so` | libc.so, libdl.so |
| `libcalibrat2.so` | arm64-v8a: libg.so, libm.so, libdl.so; armeabi-v7a: libdl.so, libg.so, libm.so |
| `libcalibrate.so` (ARM32 only) | libdl.so, libg.so, libc.so, libm.so, libstdc++.so |
| `libcgat-lib.so` | liblog.so, libm.so, libdl.so, libc.so |
| `libcrl_dp.so` | liblog.so, libm.so, libdl.so, libc.so |
| `libgetuiext3.so` (ARM32 only) | liblog.so, libstdc++.so, libm.so, libc.so, libdl.so |
| `libjniPdfium.so` | libmodpdfium.so, liblog.so, libandroid.so, libjnigraphics.so, libstdc++.so, libm.so, libc.so, libdl.so |
| `liblibre3extension.so` | liblog.so, libm.so, libdl.so, libc.so |
| `libmarsxlog.so` | liblog.so, libm.so, libc++_shared.so, libdl.so, libc.so |
| `libmmkv.so` | liblog.so, libz.so, libm.so, libdl.so, libc.so |
| `libmodft2.so` | libmodpng.so, libz.so, libc.so, libm.so, libstdc++.so |
| `libmodpdfium.so` | libdl.so, libmodft2.so, libc.so, libm.so |
| `libmodpng.so` | libz.so, libc.so, libm.so, libstdc++.so |
| `libobjectbox-jni.so` | libc.so, liblog.so, libm.so, libdl.so |
| `libscannative.so` | libz.so, liblog.so, libm.so, libdl.so, libc.so |
