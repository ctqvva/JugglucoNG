package com.microtechmd.blecomm;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import tk.glucodata.Applic;

public final class BlecommLoader {
    private static final String TAG = "BlecommLoader";
    private static final String LIBRARY_NAME = "blecomm-lib";
    private static final String LIBRARY_FILE = "libblecomm-lib.so";
    private static final String EXTERNAL_DIR = "aidex/proprietary";
    private static volatile boolean loaded = false;
    private static volatile boolean failedLogged = false;

    private BlecommLoader() {
    }

    public static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        Applic app = Applic.app;
        if (app != null && tryLoadExternal(app)) {
            loaded = true;
            failedLogged = false;
            return true;
        }
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
            failedLogged = false;
        } catch (Throwable t) {
            if (!failedLogged) {
                failedLogged = true;
                Log.e(TAG, "Failed to load libblecomm-lib", t);
            }
        }
        return loaded;
    }

    public static synchronized boolean ensureLoaded(Context context) {
        if (loaded) {
            return true;
        }
        if (context != null && tryLoadExternal(context)) {
            loaded = true;
            failedLogged = false;
            return true;
        }
        return ensureLoaded();
    }

    public static boolean isLibraryPresent(Context context) {
        if (loaded) {
            return true;
        }
        if (context == null) {
            return false;
        }
        try {
            if (externalLibraryFile(context).isFile()) {
                return true;
            }
            String nativeLibraryDir = context.getApplicationInfo() != null
                ? context.getApplicationInfo().nativeLibraryDir
                : null;
            return nativeLibraryDir != null && new File(nativeLibraryDir, LIBRARY_FILE).isFile();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to check blecomm library presence", t);
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String requiredLibraryFileName() {
        return LIBRARY_FILE;
    }

    public static synchronized boolean installFromDocument(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        try (InputStream rawInput = context.getContentResolver().openInputStream(uri)) {
            if (rawInput == null) {
                return false;
            }
            BufferedInputStream input = new BufferedInputStream(rawInput);
            input.mark(8);
            byte[] header = new byte[4];
            int headerRead = input.read(header);
            input.reset();
            byte[] libraryBytes = (headerRead == 4 && isZipBytes(header))
                ? extractLibraryFromArchive(input)
                : readFully(input);
            if (libraryBytes == null || libraryBytes.length == 0 || !isElfSharedObject(libraryBytes)) {
                return false;
            }
            File target = externalLibraryFile(context);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(libraryBytes);
            }
            loaded = false;
            failedLogged = false;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install proprietary blecomm library", t);
            return false;
        }
    }

    private static boolean tryLoadExternal(Context context) {
        try {
            File external = externalLibraryFile(context);
            if (!external.exists() || !external.isFile()) {
                return false;
            }
            System.load(external.getAbsolutePath());
            Log.i(TAG, "Loaded external blecomm library from " + external.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load external blecomm library", t);
            return false;
        }
    }

    private static File externalLibraryFile(Context context) {
        return new File(new File(context.getFilesDir(), EXTERNAL_DIR), LIBRARY_FILE);
    }

    private static byte[] extractLibraryFromArchive(InputStream archiveInput) {
        Map<String, Integer> abiPriority = new HashMap<>();
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        for (int i = 0; i < supportedAbis.length; i++) {
            abiPriority.put(supportedAbis[i], i);
        }

        byte[] bestMatch = null;
        byte[] fallback = null;
        int bestPriority = Integer.MAX_VALUE;
        try (ZipInputStream zip = new ZipInputStream(archiveInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name == null) {
                    continue;
                }
                if (!name.startsWith("lib/") || !name.endsWith("/" + LIBRARY_FILE)) {
                    continue;
                }
                byte[] payload = readFully(zip);
                if (payload.length == 0) {
                    continue;
                }
                if (fallback == null) {
                    fallback = payload;
                }
                String abi = extractAbi(name);
                Integer priority = abi != null ? abiPriority.get(abi) : null;
                if (priority != null && priority < bestPriority) {
                    bestPriority = priority;
                    bestMatch = payload;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to extract blecomm library from archive", t);
            return null;
        }
        return bestMatch != null ? bestMatch : fallback;
    }

    private static String extractAbi(String entryName) {
        int prefix = "lib/".length();
        int sep = entryName.indexOf('/', prefix);
        if (sep <= prefix) {
            return null;
        }
        return entryName.substring(prefix, sep);
    }

    private static boolean isZipBytes(byte[] bytes) {
        return bytes != null
            && bytes.length >= 4
            && bytes[0] == 0x50
            && bytes[1] == 0x4b
            && bytes[2] == 0x03
            && bytes[3] == 0x04;
    }

    private static boolean isElfSharedObject(byte[] bytes) {
        return bytes != null
            && bytes.length >= 4
            && bytes[0] == 0x7f
            && bytes[1] == 0x45
            && bytes[2] == 0x4c
            && bytes[3] == 0x46;
    }

    private static byte[] readFully(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
