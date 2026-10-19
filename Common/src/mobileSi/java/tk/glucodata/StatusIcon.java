package tk.glucodata;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import java.util.List;
import tk.glucodata.Log;

public class StatusIcon {
    final private static String LOG_ID = "StatusIcon";
    final static int size = 96; // High res

    private android.content.Context mContext;

    StatusIcon(android.content.Context context) {
        mContext = context;
    }

    Icon getIcon(String value) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);

        // Read Preferences
        android.content.SharedPreferences prefs = mContext.getSharedPreferences("tk.glucodata_preferences",
                android.content.Context.MODE_PRIVATE);
        int fontFamily = prefs.getInt("notification_font_family", 0); // 0=App, 1=System
        int fontWeight = prefs.getInt("notification_font_weight", 400);

        // Boost weight for status bar visibility (Legibility improvement)
        int effectiveWeight = Math.min(1000, fontWeight + 200);

        try {
            if (fontFamily == 0) {
                // App Font (IBM Plex)
                Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(mContext, R.font.ibm_plex_sans_var);
                paint.setTypeface(tf);
                if (Build.VERSION.SDK_INT >= 26) {
                    paint.setFontVariationSettings("'wght' " + effectiveWeight + ", 'wdth' 100");
                }
            } else {
                // System Font - Explicitly try Google Sans as requested
                String familyName = "google-sans";
                if (effectiveWeight >= 500) {
                    familyName = "google-sans-medium";
                }

                Typeface tf = Typeface.create(familyName, Typeface.NORMAL);
                // Fallback to basic google-sans if medium failed (returns default)
                if (tf == Typeface.DEFAULT && !familyName.equals("google-sans")) {
                    tf = Typeface.create("google-sans", Typeface.NORMAL);
                }
                // Fallback to sans-serif if google-sans failed
                if (tf == Typeface.DEFAULT) {
                    tf = Typeface.create("sans-serif", Typeface.NORMAL);
                }

                paint.setTypeface(tf);
                if (Build.VERSION.SDK_INT >= 26) {
                    // Apply weight (works on variable fonts or supported system fonts)
                    paint.setFontVariationSettings("'wght' " + effectiveWeight);
                }
            }
        } catch (Throwable t) {
            // Fallback
            Typeface font = Typeface.create("sans-serif", Typeface.BOLD);
            paint.setTypeface(font);
        }

        // 1. Measure at test size
        float testSize = 100f;
        paint.setTextSize(testSize);
        Rect bounds = new Rect();
        paint.getTextBounds(value, 0, value.length(), bounds);

        // 2. Calculate Scale
        float textW = bounds.width();
        float textH = bounds.height();

        // Target: Fill 100% of width/height (Maximizing size as requested)
        float targetW = size * 1.0f;
        float targetH = size * 1.0f;

        float scaleW = targetW / textW;
        float scaleH = targetH / textH;

        // 2b. Calculate Max Scale (based on "88" to match '2 numbers' consistency)
        paint.getTextBounds("88", 0, 2, bounds);
        float refW = bounds.width();
        // Use the same targetW for consistency
        float maxScale = targetW / refW;

        // Use the smaller of the two scales (Auto-fit vs Max-cap)
        float scale = Math.min(Math.min(scaleW, scaleH), maxScale);

        // Apply User Scale Preference (50% - 100%)
        float userScale = mContext
                .getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
                .getFloat("notification_status_icon_scale", 1.0f);

        float finalSize = testSize * scale * userScale;
        paint.setTextSize(finalSize);

        // 3. Re-measure for exact centering
        paint.getTextBounds(value, 0, value.length(), bounds);

        // Center X
        float x = (size - bounds.width()) / 2f - bounds.left;

        // Center Y with Offset for Status Bar alignment
        // Push down by ~10% of size to align with clock baseline
        // float yOffset = size * 0.10f;
        // float y = (size - bounds.height()) / 2f - bounds.top + yOffset;
        float y = (size - bounds.height()) / 2f - bounds.top;

        canvas.drawText(value, x, y, paint);

        return Icon.createWithBitmap(bitmap);
    }

    Icon getIcon(String primaryValue, List<String> peerValues) {
        if (peerValues == null || peerValues.isEmpty()) {
            return getIcon(primaryValue);
        }

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = createTextPaint();

        int lineCount = 1 + peerValues.size();
        float peerScale = 0.80f;
        float totalWeight = 1.0f + peerScale * peerValues.size();
        float lineGap = Math.max(1f, size * 0.025f);
        float availableHeight = size - lineGap * (lineCount - 1);
        float primaryLineHeight = availableHeight / totalWeight;
        float top = 0f;

        top = drawCenteredLine(canvas, paint, primaryValue, top, primaryLineHeight);
        for (String peerValue : peerValues) {
            top += lineGap;
            top = drawCenteredLine(canvas, paint, peerValue, top, primaryLineHeight * peerScale);
        }

        return Icon.createWithBitmap(bitmap);
    }

    private Paint createTextPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);

        android.content.SharedPreferences prefs = mContext.getSharedPreferences("tk.glucodata_preferences",
                android.content.Context.MODE_PRIVATE);
        int fontFamily = prefs.getInt("notification_font_family", 0);
        int fontWeight = prefs.getInt("notification_font_weight", 400);
        int effectiveWeight = Math.min(1000, fontWeight + 200);
        try {
            if (fontFamily == 0) {
                Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(mContext, R.font.ibm_plex_sans_var);
                paint.setTypeface(tf);
                if (Build.VERSION.SDK_INT >= 26) {
                    paint.setFontVariationSettings("'wght' " + effectiveWeight + ", 'wdth' 100");
                }
            } else {
                String familyName = effectiveWeight >= 500 ? "google-sans-medium" : "google-sans";
                Typeface tf = Typeface.create(familyName, Typeface.NORMAL);
                if (tf == Typeface.DEFAULT && !familyName.equals("google-sans")) {
                    tf = Typeface.create("google-sans", Typeface.NORMAL);
                }
                if (tf == Typeface.DEFAULT) {
                    tf = Typeface.create("sans-serif", Typeface.NORMAL);
                }
                paint.setTypeface(tf);
                if (Build.VERSION.SDK_INT >= 26) {
                    paint.setFontVariationSettings("'wght' " + effectiveWeight);
                }
            }
        } catch (Throwable t) {
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        return paint;
    }

    private float drawCenteredLine(Canvas canvas, Paint paint, String text, float top, float lineHeight) {
        String safeText = text != null ? text : "";
        float testSize = 100f;
        paint.setTextSize(testSize);
        Rect bounds = new Rect();
        paint.getTextBounds(safeText, 0, safeText.length(), bounds);
        float measuredWidth = Math.max(1f, bounds.width());
        float measuredHeight = Math.max(1f, bounds.height());
        float textSize = testSize * Math.min((size * 0.96f) / measuredWidth, (lineHeight * 0.92f) / measuredHeight);
        paint.setTextSize(textSize);
        paint.getTextBounds(safeText, 0, safeText.length(), bounds);

        float x = (size - bounds.width()) / 2f - bounds.left;
        float y = top + (lineHeight - bounds.height()) / 2f - bounds.top;
        canvas.drawText(safeText, x, y, paint);
        return top + lineHeight;
    }
}
