/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Thu Apr 04 20:10:13 CEST 2024                                                 */

package tk.glucodata;

import static tk.glucodata.Applic.Toaster;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.InsulinTypeHolder.getradiobutton;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.MainActivity.REQUEST_BARCODE_SIB2;
import static tk.glucodata.ZXing.scanZXingAlg;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhotoScan {
    private static final String LOG_ID = "PhotoScan";
    private static final String GROUP_SEPARATOR = "\u001D";
    private static final char GROUP_SEPARATOR_CHAR = '\u001D';
    private static final String SIBIONICS_GTIN = "0697283164";
    private static final String SIBIONICS_GTIN_NO_LEADING_ZERO = "697283164";
    private static final int SIBIONICS_PREFIX_PADDING = 43;
    private static final String ACCUCHEK_MANUFACTURER = "4015630";
    private static final Pattern ACCUCHEK_GS1_COMPACT = Pattern.compile(
            "^01([0-9]{14})11[0-9]{6}17[0-9]{6}21[A-Z0-9]{11}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AI10_PAREN = Pattern.compile("\\(10\\)\\s*([A-Z0-9]{4,40})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AI21_PAREN = Pattern.compile("\\(21\\)\\s*([A-Z0-9]{4,40})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AI10_GS = Pattern.compile("(?:\\u001D|\\^])10([A-Z0-9]{4,40})(?=(?:\\u001D|\\^]|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AI21_GS = Pattern.compile("(?:\\u001D|\\^])21([A-Z0-9]{4,40})(?=(?:\\u001D|\\^]|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIBIONICS2_SERIAL_TOKEN = Pattern.compile("P[0-9A-Z]{12,24}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIBIONICS_PACKAGE_AI21 = Pattern.compile(
            "^(.*10LT[A-Z0-9]{9})(?:\\u001D)?21(P[A-Z0-9\\u001D]{8,40})$",
            Pattern.CASE_INSENSITIVE);

    private static void wrongtag() {
        Toaster(R.string.wrongcode);
    }

    /*
     * E007-0M0063KNUJ0....
     * LT2309GEPD
     * 802JPPLT2309GEPD
     * 
     * GEPD802J
     * 31108 GEPD802J PP7
     * Sensorname
     * sensorgegs
     * int len=sensorgegs.size();
     * startpos=len-19
     * 
     * longserialnumber
     * 
     * 
     * (01) 06972831640165
     * (11) 231209
     * (17) 241208
     * (10) LT41 231108 C
     * 
     * (21) 231108 GEPD802J PP76
     * 
     * 0106972831640165112312091724120810LT41231108C 21231108GEPD802JPP76
     * ^]0106972831641483112411201726051910LT46241155C^]21P22411J6EP
     * 0106972831640165112312091724120810LT41231108C 21231108 GEPD 802J PP76
     */
    // LT2309GEPD
    /*
     * Sibionics2:
     */
    private static void asktransmitter(MainActivity act, String name, long sensorptr) {
        var title = getlabel(act, R.string.scantranstitle);
        var message = getlabel(act, R.string.scantransmessage);
        var cancel = getbutton(act, R.string.cancel);
        var ok = getbutton(act, R.string.ok);
        var reset = getcheckbox(act, R.string.resetname, true);

        cancel.setOnClickListener(v -> {
            MainActivity.doonback();
        });
        int height = GlucoseCurve.getheight();
        int width = GlucoseCurve.getwidth();
        var layout = new Layout(act, (l, w, h) -> {
            return new int[] { w, h };
        }, new View[] { title }, new View[] { message }, new View[] { cancel, reset, ok });
        ok.setOnClickListener(v -> {
            removeContentView(layout);
            Natives.setSensorptrResetSibionics2(sensorptr, reset.isChecked());
            scanner(act, REQUEST_BARCODE_SIB2, sensorptr);
        });
        MainActivity.setonback(() -> {
            removeContentView(layout);
            Natives.finishfromSensorptr(sensorptr);
            SensorBluetooth.sensorEnded(name);
        });
        layout.setBackgroundResource(R.drawable.dialogbackground);
        final int rand = (int) tk.glucodata.GlucoseCurve.metrics.density * 10;
        final int siderand = (int) tk.glucodata.GlucoseCurve.metrics.density * 20;
        layout.setPadding(siderand, rand, siderand, rand);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            layout.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(insets.getSystemWindowInsetLeft() + siderand, top + rand,
                        insets.getSystemWindowInsetRight() + siderand, bottom + rand);
                return insets.consumeSystemWindowInsets();
            });
        }
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(WRAP_CONTENT,
                WRAP_CONTENT);
        params.gravity = android.view.Gravity.CENTER;
        act.addContentView(layout, params);
    }

    private static void selectType(String name, long sensorptr, MainActivity act) {
        int subtype = Natives.getSensorptrSiSubtype(sensorptr);

        var group = new RadioGroup(act);
        int id = 0;
        group.addView(getradiobutton(act, R.string.eusibionics, id++));
        group.addView(getradiobutton(act, R.string.hematonix, id++));
        group.addView(getradiobutton(act, R.string.chsibionics, id++));
        group.addView(getradiobutton(act, R.string.sibionics2, id));
        group.check(subtype);
        var ok = getbutton(act, R.string.ok);
        int height = GlucoseCurve.getheight();
        int width = GlucoseCurve.getwidth();
        final int rand = (int) tk.glucodata.GlucoseCurve.metrics.density * 15;
        group.setPadding(rand, rand, (int) tk.glucodata.GlucoseCurve.metrics.density * 25,
                (int) tk.glucodata.GlucoseCurve.metrics.density * 20);
        var layout = new Layout(act, (l, w, h) -> {
            return new int[] { w, h };
        }, new View[] { group }, new View[] { ok });
        layout.setBackgroundColor(Applic.backgroundcolor);
        layout.setPadding(0, 0, 0, rand);
        MainActivity.setonback(() -> {
            removeContentView(layout);
            int type = group.getCheckedRadioButtonId();
            Log.i(LOG_ID, "getCheckedRadioButtonId()=" + type);
            if (type >= 0) {
                Natives.setSensorptrSiSubtype(sensorptr, type);
                if (type == 3) {
                    asktransmitter(act, name, sensorptr);
                }
            }

        });
        ok.setOnClickListener(v -> {
            MainActivity.doonback();
        });
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            layout.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(insets.getSystemWindowInsetLeft(), top, insets.getSystemWindowInsetRight(), bottom + rand);
                return insets.consumeSystemWindowInsets();
            });
        }
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(WRAP_CONTENT,
                WRAP_CONTENT);
        params.gravity = android.view.Gravity.CENTER;
        act.addContentView(layout, params);
    }

    static long wasdataptr = 0L;

    public static String trimOuterScannerWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmableScannerEdge(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmableScannerEdge(value.charAt(end - 1))) {
            end--;
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static boolean isTrimmableScannerEdge(char ch) {
        return ch != GROUP_SEPARATOR_CHAR && (Character.isWhitespace(ch) || Character.isSpaceChar(ch));
    }

    private static boolean isLikelyMirrorPayload(String text) {
        return text.endsWith("MirrorJuggluco") || text.contains("\"port\"");
    }

    // CareSens Air: optional i-SENS GTIN plus four AIs whose lengths
    // upstream Juggluco requires exactly. AI 21, 240 and 250 are variable length in GS1,
    // so decoders that drop the GS separators are handled by these lengths.
    private static final String CARESENS_AIR_COMPANY = "8806712";
    private static final String[] CARESENS_AIR_AIS = {"01", "17", "21", "240", "250"};
    private static final int[] CARESENS_AIR_LENGTHS = {14, 6, 12, 6, 16};

    /**
     * The canonical form native expects for a CareSens Air code — GS 01 gtin 17 expiry
     * 21 serial GS 240 pin GS 250 code, the layout of careSenseAirScan_t — or null when
     * the payload is not one.
     */
    static String buildCareSensAirPayload(String input) {
        if (input == null) {
            return null;
        }
        String text = stripSymbologyPrefix(input.replace(" ", "")).replace("^]", GROUP_SEPARATOR);
        final String[] values = new String[CARESENS_AIR_AIS.length];
        int pos = 0;
        while (pos < text.length()) {
            if (text.charAt(pos) == GROUP_SEPARATOR_CHAR) {
                pos++;
                continue;
            }
            int field = -1;
            for (int i = 0; i < CARESENS_AIR_AIS.length; i++) {
                if (text.startsWith(CARESENS_AIR_AIS[i], pos)) {
                    field = i;
                    break;
                }
            }
            if (field < 0 || values[field] != null) {
                return null;
            }
            pos += CARESENS_AIR_AIS[field].length();
            final int length = CARESENS_AIR_LENGTHS[field];
            int end = text.indexOf(GROUP_SEPARATOR_CHAR, pos);
            if (end < 0) {
                end = text.length();
            }
            if (end - pos < length) {
                return null;
            }
            // Longer than the field means the separator after it was dropped.
            values[field] = text.substring(pos, pos + length);
            pos += length;
        }
        // Upstream also accepts the 53-byte form containing only AIs 17/21/240/250.
        for (int i = 1; i < values.length; i++) {
            if (values[i] == null) {
                return null;
            }
        }
        final String gtin = values[0];
        if ((gtin != null && (!gtin.substring(1, 8).equals(CARESENS_AIR_COMPANY) || !isDigits(gtin)))
                || !isDigits(values[1])
                || !isPrintable(values[2]) || !isPrintable(values[3]) || !isPrintable(values[4])) {
            return null;
        }
        // Keep the persisted record and PIN offset unchanged. Zeros only pad the
        // absent GTIN; identity still comes from expiry + serial, as upstream.
        return GROUP_SEPARATOR + "01" + (gtin != null ? gtin : "00000000000000")
                + "17" + values[1] + "21" + values[2]
                + GROUP_SEPARATOR + "240" + values[3] + GROUP_SEPARATOR + "250" + values[4];
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrintable(String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c <= ' ' || c > '~') {
                return false;
            }
        }
        return true;
    }

    private static String sanitizeAlnumUpper(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        return out.toString();
    }

    private static String takeLeadingAlnum(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < value.length() && Character.isLetterOrDigit(value.charAt(end))) {
            end++;
        }
        return value.substring(0, end);
    }

    private static String lastGroupMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String found = null;
        while (matcher.find()) {
            found = sanitizeAlnumUpper(matcher.group(1));
        }
        return found;
    }

    private static String extractByAiTailFallback(String text, String ai) {
        int idx = text.lastIndexOf(ai);
        if (idx < 0 || idx + 2 >= text.length()) {
            return null;
        }
        String tail = text.substring(idx + 2);
        String token = sanitizeAlnumUpper(takeLeadingAlnum(tail));
        return token.length() >= 8 ? token : null;
    }

    private static boolean looksLikeSibionicsPayload(String text) {
        final String upper = text.toUpperCase(Locale.ROOT);
        final boolean hasAi21Token = upper.contains("(21)") || upper.contains("\u001D21")
                || upper.contains("^]21") || upper.contains("21P2");
        final boolean hasAi10Token = upper.contains("(10)") || upper.contains("\u001D10")
                || upper.contains("^]10");
        return hasSibionicsGtin(upper)
                || upper.contains("(SI)")
                || (hasAi10Token && hasAi21Token);
    }

    private static boolean hasSibionicsGtin(String text) {
        final String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains(SIBIONICS_GTIN) || upper.contains(SIBIONICS_GTIN_NO_LEADING_ZERO);
    }

    private static String buildCanonicalSibionicsPayload(String input) {
        final String trimmed = input.trim();
        if (trimmed.isEmpty() || !looksLikeSibionicsPayload(trimmed)) {
            return null;
        }

        final String normalized = cleanSibionicsInput(trimmed);

        String serial = lastGroupMatch(AI21_PAREN, normalized);
        if (serial == null) {
            serial = lastGroupMatch(AI21_GS, normalized);
        }
        if (serial == null) {
            serial = extractByAiTailFallback(normalized, "21");
        }
        if (serial == null || serial.length() < 8 || serial.length() > 32) {
            return null;
        }

        String batch = lastGroupMatch(AI10_PAREN, normalized);
        if (batch == null) {
            batch = lastGroupMatch(AI10_GS, normalized);
        }
        if (batch == null) {
            int serialAi = normalized.lastIndexOf("21");
            if (serialAi > 2) {
                int batchAi = normalized.lastIndexOf("10", serialAi - 1);
                if (batchAi >= 0 && batchAi + 2 < serialAi) {
                    batch = sanitizeAlnumUpper(normalized.substring(batchAi + 2, serialAi));
                }
            }
        }

        if (batch == null) {
            batch = "";
        } else if (batch.length() > 24) {
            batch = batch.substring(0, 24);
        }

        final String codePart = batch + serial;
        if (codePart.length() < 12 || codePart.length() > 56) {
            return null;
        }

        final String canonical = SIBIONICS_GTIN + "0".repeat(SIBIONICS_PREFIX_PADDING) + codePart;
        scanLog("Normalized Sibionics QR payload for stable native parse");
        return canonical;
    }

    private static String stripSymbologyPrefix(String text) {
        if (text == null || text.length() < 3 || text.charAt(0) != ']') {
            return text;
        }
        final char c1 = text.charAt(1);
        final char c2 = text.charAt(2);
        if (Character.isLetter(c1) && Character.isDigit(c2)) {
            return text.substring(3);
        }
        return text;
    }

    private static String printableScanPreview(String text) {
        if (text == null) {
            return "";
        }
        final String preview = text.replace("\u001D", "<GS>");
        return preview.length() > 96 ? preview.substring(0, 96) + "..." : preview;
    }

    private static void scanLog(String message) {
        if (!doLog) {
            return;
        }
        try {
            Log.i(LOG_ID, message);
        } catch (RuntimeException ignored) {
            // JVM unit tests do not mock android.util.Log.
        }
    }

    private static String cleanSibionicsInput(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input.toUpperCase(Locale.ROOT).replace(" ", "");
        out = stripSymbologyPrefix(out);
        return out.replace("^]", GROUP_SEPARATOR);
    }

    private static String normalizeAccuChekFraming(String input) {
        if (input == null || input.isEmpty() || isLikelyMirrorPayload(input)) {
            return input;
        }
        String out = stripSymbologyPrefix(input.toUpperCase(Locale.ROOT).replace(" ", ""));
        if (out == null || out.isEmpty()) {
            return input;
        }
        if (out.startsWith(GROUP_SEPARATOR)) {
            return out;
        }
        Matcher matcher = ACCUCHEK_GS1_COMPACT.matcher(out);
        if (!matcher.matches()) {
            return input;
        }
        final String gtin = matcher.group(1);
        if (gtin != null && gtin.contains(ACCUCHEK_MANUFACTURER)) {
            scanLog("Restored leading GS separator for Accu-Chek SmartGuide QR payload");
            return GROUP_SEPARATOR + out;
        }
        return input;
    }

    private static String repairSibionicsPackageAi21Framing(String input) {
        String out = cleanSibionicsInput(input);
        if (out == null || out.isEmpty()) {
            return null;
        }
        if (!hasSibionicsGtin(out)) {
            return null;
        }
        final String body = out.startsWith(GROUP_SEPARATOR) ? out.substring(GROUP_SEPARATOR.length()) : out;
        final Matcher matcher = SIBIONICS_PACKAGE_AI21.matcher(body);
        if (!matcher.matches()) {
            return null;
        }
        final String serial = sanitizeAlnumUpper(matcher.group(2));
        if (serial.length() < 8 || serial.length() > 40) {
            return null;
        }
        return GROUP_SEPARATOR + matcher.group(1) + GROUP_SEPARATOR + "21" + serial;
    }

    private static String normalizeSibionicsFraming(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = cleanSibionicsInput(input);

        final String repairedPackagePayload = repairSibionicsPackageAi21Framing(out);
        if (repairedPackagePayload != null) {
            if (!repairedPackagePayload.equals(out)) {
                scanLog("Repaired Sibionics package-label AI21 framing");
            }
            return repairedPackagePayload;
        }

        if (out.indexOf(GROUP_SEPARATOR.charAt(0)) >= 0) {
            if (!out.startsWith(GROUP_SEPARATOR)) {
                out = GROUP_SEPARATOR + out;
            }
            if (out.indexOf(GROUP_SEPARATOR, GROUP_SEPARATOR.length()) < 0) {
                final int ai21 = out.lastIndexOf("21");
                if (ai21 > 0 && out.charAt(ai21 - 1) != GROUP_SEPARATOR.charAt(0)
                        && !out.contains("21P")) {
                    return out.substring(0, ai21) + GROUP_SEPARATOR + out.substring(ai21);
                }
            }
            return out;
        }
        return out;
    }

    private static String expandCompactSibionicsFakePayload(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        final String compact = input.toUpperCase(Locale.ROOT).replace(" ", "");
        if (!compact.startsWith(SIBIONICS_GTIN)) {
            return null;
        }
        if (compact.contains("^]") || compact.indexOf(GROUP_SEPARATOR.charAt(0)) >= 0
                || compact.contains("(10)") || compact.contains("(21)")) {
            return null;
        }
        if (compact.length() < SIBIONICS_GTIN.length() + 8) {
            return null;
        }

        final String suffix = compact.substring(SIBIONICS_GTIN.length());
        int nonZero = 0;
        while (nonZero < suffix.length() && suffix.charAt(nonZero) == '0') {
            nonZero++;
        }
        final String tail = suffix.substring(nonZero);
        if (tail.length() < 4 || tail.length() > 11) {
            return null;
        }

        String shortName;
        if (tail.startsWith("LT") && tail.length() >= 8) {
            shortName = (tail.substring(tail.length() - 4) + tail);
        } else {
            shortName = tail;
        }
        if (shortName.length() > 11) {
            shortName = shortName.substring(0, 11);
        } else if (shortName.length() < 11) {
            shortName = "0".repeat(11 - shortName.length()) + shortName;
        }

        final String syntheticName16 = "00000" + shortName;
        final String rebuilt = SIBIONICS_GTIN + "0".repeat(SIBIONICS_PREFIX_PADDING) + syntheticName16 + "X";
        scanLog("Expanded compact Sibionics fake payload to long sensor format");
        return rebuilt;
    }

    private static String delimitCompactSibionicsPayload(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        final String repairedPackagePayload = repairSibionicsPackageAi21Framing(input);
        if (repairedPackagePayload != null) {
            return repairedPackagePayload;
        }

        final String compact = input.toUpperCase(Locale.ROOT).replace(" ", "");
        if (!looksLikeSibionicsPayload(compact)) {
            return null;
        }
        if (compact.contains("^]") || compact.indexOf(GROUP_SEPARATOR.charAt(0)) >= 0
                || compact.contains("(10)") || compact.contains("(21)")) {
            return null;
        }

        final int ai10 = compact.lastIndexOf("10", compact.lastIndexOf("21") - 1);
        final int ai21 = compact.lastIndexOf("21");
        if (ai10 < 0 || ai21 <= (ai10 + 2) || ai21 >= (compact.length() - 2)) {
            return null;
        }

        final int batchLen = ai21 - (ai10 + 2);
        final int serialLen = compact.length() - (ai21 + 2);
        if (batchLen < 4 || serialLen < 8) {
            return null;
        }

        final String delimited = GROUP_SEPARATOR + compact.substring(0, ai21) + GROUP_SEPARATOR + compact.substring(ai21);
        scanLog("Injected GS separators for compact Sibionics payload");
        return delimited;
    }

    private static String normalizeSibionics2NamePart(String rawToken) {
        final String token = sanitizeAlnumUpper(rawToken);
        if (token.isEmpty()) {
            return null;
        }
        if (token.startsWith("P") && token.length() >= 16) {
            return token.substring(token.length() - 12, token.length() - 2);
        }
        if (token.length() == 12) {
            return token.substring(1, 11);
        }
        if (token.length() == 11) {
            return token.substring(1);
        }
        if (token.length() > 10) {
            return token.substring(token.length() - 10);
        }
        return token;
    }

    private static String extractSibionics2TransmitterId(String input) {
        if (input == null) {
            return null;
        }
        String normalized = stripSymbologyPrefix(input.trim());
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }

        final int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < normalized.length()) {
            normalized = normalized.substring(lastSlash + 1);
        }

        normalized = normalized.toUpperCase(Locale.ROOT).replace(" ", "");
        normalized = normalized.replace("^]", "\u001D");

        String ai21Token = lastGroupMatch(AI21_PAREN, normalized);
        if (ai21Token == null) {
            ai21Token = lastGroupMatch(AI21_GS, normalized);
        }
        if (ai21Token == null) {
            ai21Token = extractByAiTailFallback(normalized, "21");
        }
        if (ai21Token != null) {
            final String normalizedAi21 = normalizeSibionics2NamePart(ai21Token);
            if (normalizedAi21 != null && !normalizedAi21.isEmpty()) {
                return normalizedAi21;
            }
        }

        final String compact = sanitizeAlnumUpper(normalized);
        if (compact.isEmpty()) {
            return null;
        }

        String serialToken = null;
        Matcher serialMatcher = SIBIONICS2_SERIAL_TOKEN.matcher(compact);
        while (serialMatcher.find()) {
            serialToken = sanitizeAlnumUpper(serialMatcher.group());
        }

        if (serialToken != null) {
            final String normalizedSerial = normalizeSibionics2NamePart(serialToken);
            if (normalizedSerial != null && !normalizedSerial.isEmpty()) {
                return normalizedSerial;
            }
        }

        if ((compact.contains(SIBIONICS_GTIN) || compact.contains(SIBIONICS_GTIN_NO_LEADING_ZERO)
                || looksLikeSibionicsPayload(compact)) && compact.length() >= 11) {
            final String compactTail = extractByAiTailFallback(compact, "21");
            if (compactTail != null) {
                final String normalizedCompactTail = normalizeSibionics2NamePart(compactTail);
                if (normalizedCompactTail != null && !normalizedCompactTail.isEmpty()) {
                    return normalizedCompactTail;
                }
            }
        }

        return normalizeSibionics2NamePart(compact);
    }

    public static String buildSibionics2TransmitterPayload(String input) {
        final String transmitterId = extractSibionics2TransmitterId(input);
        if (transmitterId == null || transmitterId.length() < 3) {
            return null;
        }

        final String namePart;
        if (transmitterId.length() >= 10) {
            namePart = transmitterId.substring(0, 10);
        } else {
            namePart = transmitterId + "0".repeat(10 - transmitterId.length());
        }

        final int targetLength = 59;
        final int paddingLen = targetLength - SIBIONICS_GTIN.length() - namePart.length();
        if (paddingLen < 0) {
            return SIBIONICS_GTIN + namePart;
        }
        return SIBIONICS_GTIN + "0".repeat(paddingLen) + namePart;
    }

    private static String buildSibionicsSensorPayload(String input) {
        if (input == null) {
            return null;
        }
        final String trimmedInput = input.trim();
        if (trimmedInput.length() < 3 || countChar(trimmedInput, '/') > 2) {
            return null;
        }
        final String compactRaw = trimmedInput.replaceAll("\\s+", "");
        final int gtinPos = compactRaw.indexOf(SIBIONICS_GTIN);
        if (gtinPos >= 0 && gtinPos <= 4 && compactRaw.length() - gtinPos >= 55) {
            return compactRaw;
        }
        final String codeToUse;
        if (trimmedInput.contains("/")) {
            final int slash = trimmedInput.lastIndexOf('/');
            final String extracted = sanitizeAlnumUpper(trimmedInput.substring(slash + 1));
            codeToUse = extracted.length() >= 3 ? extracted : sanitizeAlnumUpper(trimmedInput);
        } else {
            codeToUse = sanitizeAlnumUpper(trimmedInput);
        }
        if (codeToUse.isEmpty()) {
            return null;
        }
        if (codeToUse.length() <= 11) {
            final String shortName = (codeToUse.substring(Math.max(0, codeToUse.length() - 4)) + codeToUse);
            final String paddedShort = (shortName + "00000000000").substring(0, 11);
            final String syntheticName16 = "00000" + paddedShort;
            final String suffix = "X";
            final int desiredLen = 70;
            final int prefixLen = desiredLen - syntheticName16.length() - suffix.length();
            final int prefixPaddingLen = Math.max(0, prefixLen - SIBIONICS_GTIN.length());
            return SIBIONICS_GTIN + "0".repeat(prefixPaddingLen) + syntheticName16 + suffix;
        }
        final int minPrefixLen = 53 - SIBIONICS_GTIN.length();
        return SIBIONICS_GTIN + "0".repeat(Math.max(0, minPrefixLen)) + codeToUse;
    }

    private static int countChar(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    static String normalizeScanPayload(String scanText, int request) {
        if (scanText == null) {
            return "";
        }
        final String trimmed = trimOuterScannerWhitespace(scanText);

        if (request == REQUEST_BARCODE_SIB2) {
            final String transmitterPayload = buildSibionics2TransmitterPayload(trimmed);
            return transmitterPayload != null ? transmitterPayload : trimmed;
        }

        if (request != REQUEST_BARCODE || trimmed.isEmpty() || isLikelyMirrorPayload(trimmed)) {
            return trimmed;
        }

        final String careSensAir = buildCareSensAirPayload(trimmed);
        if (careSensAir != null) {
            return careSensAir;
        }

        String normalized = trimmed;

        normalized = normalizeAccuChekFraming(normalized);

        if (looksLikeSibionicsPayload(normalized)) {
            normalized = normalizeSibionicsFraming(normalized);
            scanLog("Sibionics scan after framing normalize len=" + normalized.length()
                    + " payload=" + printableScanPreview(normalized));
        }

        // Conservative fallback: some decoders drop the leading zero in Sibionics GTIN.
        if (normalized.contains(SIBIONICS_GTIN_NO_LEADING_ZERO) && !normalized.contains(SIBIONICS_GTIN)
                && looksLikeSibionicsPayload(normalized)) {
            final int idx = normalized.indexOf(SIBIONICS_GTIN_NO_LEADING_ZERO);
            if (idx >= 0) {
                scanLog("Inserted missing GTIN leading zero for Sibionics QR payload");
                normalized = normalized.substring(0, idx) + "0" + normalized.substring(idx);
            }
        }

        final String expandedFake = expandCompactSibionicsFakePayload(normalized);
        if (expandedFake != null) {
            normalized = expandedFake;
            scanLog("Sibionics fake payload normalize len=" + normalized.length()
                    + " payload=" + printableScanPreview(normalized));
        }

        final String delimited = delimitCompactSibionicsPayload(normalized);
        if (delimited != null) {
            return delimited;
        }
        return normalized;
    }

    public static boolean applyManualPairingCode(Context context, String rawCode) {
        final String normalizedTag = normalizeScanPayload(rawCode, REQUEST_BARCODE);
        String name = parseSensorName(normalizedTag);
        if (name == null || name.isEmpty()) {
            final String fakeSibionics = buildSibionicsSensorPayload(rawCode);
            if (fakeSibionics != null && !fakeSibionics.equals(normalizedTag)) {
                name = parseSensorName(normalizeScanPayload(fakeSibionics, REQUEST_BARCODE));
            }
        }
        if (name == null || name.isEmpty()) {
            return false;
        }
        return finishManualPairing(context, name);
    }

    private static String parseSensorName(String normalizedTag) {
        if (normalizedTag == null || normalizedTag.isEmpty() || isLikelyMirrorPayload(normalizedTag)) {
            return null;
        }
        int[] indexptr = { -1 };
        String name = Natives.addSIscangetName(normalizedTag, indexptr);
        if ((name == null || name.isEmpty()) && indexptr[0] < 0 && looksLikeSibionicsPayload(normalizedTag)) {
            String canonical = buildCanonicalSibionicsPayload(normalizedTag);
            if (canonical != null && !canonical.equals(normalizedTag)) {
                int[] canonicalIndexptr = { -1 };
                name = Natives.addSIscangetName(canonical, canonicalIndexptr);
            }
        }
        return name;
    }

    private static boolean finishManualPairing(Context context, String name) {
        if (Natives.getusebluetooth()) {
            Applic.updateDevices();
            SuperGattCallback.glucosealarms.setLossAlarm();
        } else {
            Natives.updateUsedSensors();
        }
        Applic.wakemirrors();
        return true;
    }

    public static void connectSensor(final String scantag, MainActivity act, int request, long sensorptr2) {
        final String normalizedTag = normalizeScanPayload(scantag, request);
        if (!isWearable) {
            switch (request) {
                case REQUEST_BARCODE: {
                    if (normalizedTag.endsWith("MirrorJuggluco")) {
                        MirrorString.makeMirror(normalizedTag, act);
                        return;
                    } else {
                        if (doLog && looksLikeSibionicsPayload(normalizedTag)) {
                            Log.i(LOG_ID, "Sibionics addSIscangetName input len=" + normalizedTag.length()
                                    + " payload=" + printableScanPreview(normalizedTag));
                        }
                        int[] indexptr = { -1 };
                        String name = Natives.addSIscangetName(normalizedTag, indexptr);
                        if (doLog && looksLikeSibionicsPayload(normalizedTag)) {
                            Log.i(LOG_ID, "Sibionics addSIscangetName output name=" + name + " index=" + indexptr[0]);
                        }
                        if ((name == null || name.isEmpty()) && indexptr[0] < 0 && looksLikeSibionicsPayload(normalizedTag)) {
                            // Keep the native raw-payload path first. Only if native parse fails, try a
                            // canonicalized Sibionics payload to recover scans from strict/odd decoders.
                            String canonical = buildCanonicalSibionicsPayload(normalizedTag);
                            if (canonical != null && !canonical.equals(normalizedTag)) {
                                int[] canonicalIndexptr = { -1 };
                                String canonicalName = Natives.addSIscangetName(canonical, canonicalIndexptr);
                                if (canonicalName != null && !canonicalName.isEmpty()) {
                                    name = canonicalName;
                                    indexptr[0] = canonicalIndexptr[0];
                                } else if (indexptr[0] < 0) {
                                    indexptr[0] = canonicalIndexptr[0];
                                }
                            }
                        }
                        if (name != null && name.length() > 0) {
                            // Skip calendar popup - legacy behavior disabled by default
                            // MainActivity.tocalendarapp = true;
                            var sensorptr = Natives.str2sensorptr(name);
                            int type = Natives.getSensorptrLibreVersion(sensorptr);
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "type=" + type);
                                }
                                ;
                            }
                            ;
                            if (type == 0x10) {
                                // Route to Compose wizard if enabled
                                if (MainActivity.useComposeWizard && MainActivity.onSensorScanResult != null) {
                                    final String sensorNameFinal = name;
                                    final long ptrFinal = sensorptr;
                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                        if (MainActivity.onSensorScanResult != null) {
                                            MainActivity.onSensorScanResult.onResult(sensorNameFinal, ptrFinal, type);
                                        }
                                    });
                                    // Set up BLE immediately so sensor connects while wizard runs.
                                    // updateDevices is idempotent — wizard's finishSetup can safely call it again.
                                    if (Natives.getusebluetooth()) {
                                        var res = SensorBluetooth.updateDevices();
                                        SuperGattCallback.glucosealarms.setLossAlarm();
                                        if (res) {
                                            act.finepermission();
                                        } else
                                            act.systemlocation();
                                    } else {
                                        Natives.updateUsedSensors();
                                    }
                                    Applic.wakemirrors();
                                    return;
                                } else {
                                    selectType(name, sensorptr, act);
                                }
                            }
                            if (Natives.getusebluetooth()) {
                                var res = SensorBluetooth.updateDevices();
                                SuperGattCallback.glucosealarms.setLossAlarm();
                                if (res) {
                                    act.finepermission();
                                } else
                                    act.systemlocation();
                            } else {
                                Natives.updateUsedSensors();
                            }
                            Applic.wakemirrors();
                            return;
                        } else {
                            final int index = indexptr[0];
                            if (index >= 0) {
                                if (Natives.staticnum()) {
                                    help.help(R.string.staticnum, act);
                                } else
                                    MeterConfig.config(act, index, null, null);
                                return;
                            }
                        }
                    }
                }
                    break;
                case REQUEST_BARCODE_SIB2: {
                    if (Natives.siSensorptrTransmitterScan(sensorptr2, normalizedTag)) {
                        // Route to Compose wizard if enabled
                        if (MainActivity.useComposeWizard && MainActivity.onTransmitterScanResult != null) {
                            MainActivity.onTransmitterScanResult.onResult(true);
                        }
                        return;
                    } else {
                        if (MainActivity.useComposeWizard && MainActivity.onTransmitterScanResult != null) {
                            MainActivity.onTransmitterScanResult.onResult(false);
                        }
                        transmitterScanCancelled(sensorptr2);
                    }

                }
            }
        }
        wrongtag();
    }

    static void transmitterScanCancelled(long sensorptr2) {
        if (sensorptr2 != 0L) {
            Natives.finishfromSensorptr(sensorptr2);
            var serial = Natives.sensorptr2str(sensorptr2);
            if (serial != null) {
                Log.i(LOG_ID, "transmitterScanCancelled " + serial);
                SensorBluetooth.sensorEnded(serial);
            }
        } else {
            Log.i(LOG_ID, "transmitterScanCancelled sensorptr==0");
        }
    }

    static boolean handleUnifiedScanResult(int resultCode, Intent data, MainActivity act, int type) {
        if (data == null) {
            return false;
        }
        if (!data.hasExtra(UnifiedScanActivity.EXTRA_SCAN_REQUEST)) {
            return false;
        }
        final int request = data.getIntExtra(UnifiedScanActivity.EXTRA_SCAN_REQUEST, type);
        final long sensorptr = data.getLongExtra(UnifiedScanActivity.EXTRA_SENSOR_PTR, 0L);
        if (resultCode == Activity.RESULT_OK) {
            final String scan = data.getStringExtra(UnifiedScanActivity.EXTRA_SCAN_TEXT);
            if (scan != null && !scan.isEmpty()) {
                connectSensor(scan, act, request, sensorptr);
            } else if (request == REQUEST_BARCODE_SIB2) {
                transmitterScanCancelled(sensorptr);
            }
            return true;
        }
        if (request == REQUEST_BARCODE_SIB2) {
            transmitterScanCancelled(sensorptr);
        }
        return true;
    }

    public static Intent createUnifiedScanIntent(Context context, int type, long sensorptr, String title) {
        try {
            final Intent intent = new Intent(context, UnifiedScanActivity.class);
            intent.putExtra(UnifiedScanActivity.EXTRA_SCAN_REQUEST, type);
            intent.putExtra(UnifiedScanActivity.EXTRA_SENSOR_PTR, sensorptr);
            if (title != null && !title.isEmpty()) {
                intent.putExtra(UnifiedScanActivity.EXTRA_SCAN_TITLE, title);
            }
            return intent;
        } catch (Throwable th) {
            Log.stack(LOG_ID, "createUnifiedScanIntent", th);
            return null;
        }
    }

    public static void scanner(MainActivity act, int type, long sensorptr) {
        scanner(act, type, sensorptr, null);
    }

    public static void scanner(MainActivity act, int type, long sensorptr, String title) {
        if (!isWearable) {
            final Intent unifiedIntent = createUnifiedScanIntent(act, type, sensorptr, title);
            if (unifiedIntent != null) {
                act.startActivityForResult(unifiedIntent, type);
                return;
            }
            scanZXingAlg(act, type, sensorptr);
        }
    }

    public static void scan(MainActivity act, int type) {
        scanner(act, type, 0L);
    }

    public static void scan(MainActivity act, int type, String title) {
        scanner(act, type, 0L, title);
    }

    /*
     * static void testsibionics() {
     * if(doLog) {
     * String
     * tag="^]0106972831640820112312221724122110LT48231127G^]212311271NTK237GAA21";
     * var name=Natives.addSIscangetName(tag);
     * long dataptr=Natives.getdataptr(name);
     * var si=new SiGattCallback(name, dataptr);
     * si.testchanged();
     * }
     * };
     */
}
