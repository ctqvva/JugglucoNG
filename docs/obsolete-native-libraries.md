# Obsolete distribution native libraries

Audit baseline: source commit `91fed63ff`; vendor bytes restored from the pinned
1.2.3-Alpha APK using `scripts/restore-build-inputs.sh --apk`. Its SHA-256 is
`6374954ca6c9c7681434343220437437a8378748c303975af92c46c7d74b74b1`.
Both `arm64-v8a` and `armeabi-v7a` were inspected with NDK 29
`llvm-readelf -d`, `llvm-nm -D --defined-only`, and `llvm-strings`.

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
| `libgetuiext3.so` | Remove, ARM32 only | Separately inspected: exports `Java_com_igexin_dms_core_NativeCaller_doDaemon` and `JNI_OnLoad`. No Igexin/Getui Java package, manifest service, loader or dependency. No retained vendor library needs or names it. No ARM64 counterpart exists in the inventory. |
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

Across all inventoried ELF files in both ABIs, no retained library has a
`DT_NEEDED` edge or library-name string naming a retired candidate. Candidate
edges are only the PDF chain above. Their other dependencies are platform
libraries, except `libmarsxlog.so` needing the retained `libc++_shared.so`.
`libinit.so` is a ZIP resource, not ELF; its six entries contain no native code.
Retained vendor inputs are `libCALCULATION.so`, `libc++_shared.so`,
`libcalibrat2.so`, `libcrl_dp.so`, `libinit.so`, `liblibre3extension.so` in both
ABIs and `libcalibrate.so` in ARM32. Production `libg.so` is unchanged.

## Verification

Validation results will be recorded after running the updated distribution
tests, clean vendor restoration, Gradle assembly and APK verification. A build
or source audit does not establish physical sensor behavior.
