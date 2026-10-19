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
/*      Thu Oct 05 15:29:10 CEST 2023                                                 */

package tk.glucodata;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;
import static java.lang.Float.isNaN;
import static java.lang.String.format;

import static tk.glucodata.CommonCanvas.drawarrow;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getisalarm;
import static tk.glucodata.Notify.penmutable;
import static tk.glucodata.Notify.stopalarmrequest;
import static tk.glucodata.Notify.unitlabel;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.RemoteViews;

import static tk.glucodata.NumberView.minhourstr;
import static tk.glucodata.R.id.arrowandvalue;

import java.text.DateFormat;
import java.util.Date;

class RemoteGlucose {
   final private static String LOG_ID = "RemoteGlucose";
   final private Bitmap glucoseBitmap;
   final private Canvas canvas;
   final private Paint glucosePaint;
   final private int baseForegroundColor;
   final private float density;
   final private float glucosesize;
   final private int notglucosex;
   final private int timeHeight;
   final private int timesize;

   RemoteGlucose(float gl, float notwidth, float xper, int whiteonblack, boolean givetime) {

      glucosesize = gl;
      glucosePaint = new Paint();
      glucosePaint.setAntiAlias(true);
      glucosePaint.setTextAlign(Paint.Align.LEFT);
      float notheight = glucosesize * 0.8f;
      notglucosex = (int) (notwidth * xper);
      density = notheight / 54.0f;

      if (givetime) {
         Rect bounds = new Rect();
         timesize = (int) (glucosesize * .2f);
         glucosePaint.setTextSize(timesize);
         glucosePaint.getTextBounds("8.9", 0, 3, bounds);
         timeHeight = (int) (bounds.height() * 1.2f);
         notheight += timeHeight;
      } else {
         timeHeight = timesize = 0;
      }
      glucosePaint.setTextSize(glucosesize);
      if (notwidth <= 0.0f)
         notwidth = 1.0f;
      if (notheight <= 0.0f)
         notheight = 1.0f;
      glucoseBitmap = Bitmap.createBitmap((int) notwidth, (int) notheight, Bitmap.Config.ARGB_8888);
      canvas = new Canvas(glucoseBitmap);

      {
         if (doLog) {
            Log.i(LOG_ID,
                  "timesize=" + timesize + " timeHeight=" + timeHeight + " glucosesize=" + glucosesize + " notwidth="
                        + notwidth + " notheight=" + notheight + "color=" + format("%x", glucosePaint.getColor()));
         }
         ;
      }
      ;
      switch (whiteonblack) {
         case 1:
         case 2:
            glucosePaint.setColor(WHITE);
            break;
         default: {
            var style = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                  ? android.R.style.TextAppearance_Material_Notification_Title
                  : android.R.style.TextAppearance_StatusBar_EventContent;
            int[] attrs = { android.R.attr.textColor };
            try {
               @SuppressLint("ResourceType")
               TypedArray ta = Applic.app.obtainStyledAttributes(style, attrs);
               if (ta != null) {
                  int col = ta.getColor(0, Color.TRANSPARENT);
                  glucosePaint.setColor(col);
                  Notify.foregroundcolor = col;
                  ta.recycle();
               }
            } catch (Throwable e) {
               Log.stack(LOG_ID, "obtainStyledAttributes", e);
            }
         }
      }
      ;
      baseForegroundColor = glucosePaint.getColor();
   }

   static final String stopalarmAction = "StopAlarm";

   int getBaseForegroundColor() {
      return baseForegroundColor;
   }

   private void applyWidgetTypeface(Paint paint) {
      try {
         var prefs = Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE);
         boolean useSystemFont = prefs.getInt("notification_font_family", 0) == 1;
         int fontWeight = prefs.getInt("notification_font_weight", 400);

         if (useSystemFont) {
            String familyName = fontWeight >= 500 ? "google-sans-medium" : "google-sans";
            android.graphics.Typeface tf = android.graphics.Typeface.create(familyName, android.graphics.Typeface.NORMAL);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
               tf = android.graphics.Typeface.create(tf, fontWeight, false);
            }
            paint.setTypeface(tf);
         } else {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(Applic.app,
                  R.font.ibm_plex_sans_var);
            paint.setTypeface(tf);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
               paint.setFontVariationSettings("'wght' " + fontWeight + ", 'wdth' 100");
            }
         }
      } catch (Throwable t) {
      }
   }

   final RemoteViews widgetRemote(CurrentDisplaySource.Snapshot snapshot) {
      RemoteViews remoteViews = new RemoteViews(Applic.app.getPackageName(), R.layout.arrowandvalue);
      if (snapshot == null) {
         return remoteViews;
      }

      final boolean isMmol = Applic.unit == 1;
      // Keep the legacy widget on its fixed legacy foreground instead of
      // following notification/system light-dark theme flips.
      final int glucoseColor = baseForegroundColor;
      final float useglsize = glucosesize;
      final float usedensity = density;
      final int canvasWidth = canvas.getWidth();
      final int canvasHeight = canvas.getHeight();
      float rate = snapshot.getRate();

      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      glucosePaint.setColor(glucoseColor);
      applyWidgetTypeface(glucosePaint);

      // Render the right-side cluster (arrow above small time) so we know its
      // width before placing the value. The cluster is intentionally compact —
      // small arrow paired with a small time label, both right-aligned with a
      // generous offset from the canvas edge.
      final float rightInset = usedensity * 18f;
      final float reducedTimeSize = timesize * 0.95f;
      Bitmap arrowBitmap = null;
      if (!isNaN(rate)) {
         try {
            float displayDensity = Applic.app.getResources().getDisplayMetrics().density;
            float arrowScale = Math.max(0.85f, Math.min(2.05f, useglsize / (30f * displayDensity)));
            arrowBitmap = NotificationChartDrawer.drawArrow(Applic.app, rate, isMmol, glucoseColor, arrowScale);
         } catch (Throwable t) {
            arrowBitmap = null;
         }
      }

      String timestr = minhourstr(snapshot.getTimeMillis());
      glucosePaint.setTextSize(reducedTimeSize);
      Rect timeBounds = new Rect();
      glucosePaint.getTextBounds(timestr, 0, timestr.length(), timeBounds);
      float timeWidth = timeBounds.width();

      float arrowWidth = arrowBitmap != null ? arrowBitmap.getWidth() : 0f;
      float arrowHeight = arrowBitmap != null ? arrowBitmap.getHeight() : 0f;
      float clusterWidth = Math.max(arrowWidth, timeWidth);
      float clusterCenterX = canvasWidth - rightInset - clusterWidth / 2f;

      // Value text — left-anchored at a small inset (the original notglucosex
      // offset reserved space for a left-side arrow that no longer exists).
      // If the value is wide enough to collide with the cluster, shrink it
      // down so the right cluster keeps its breathing room.
      String valueStr = snapshot.getPrimaryStr();
      final float leftInset = usedensity * 14f;
      final float valueClusterGap = useglsize * 0.18f;
      final float maxValueWidth = (canvasWidth - rightInset - clusterWidth - valueClusterGap) - leftInset;
      glucosePaint.setTextSize(useglsize);
      float effectiveSize = useglsize;
      float valueWidth = glucosePaint.measureText(valueStr);
      if (maxValueWidth > 0 && valueWidth > maxValueWidth) {
         effectiveSize = useglsize * (maxValueWidth / valueWidth);
         glucosePaint.setTextSize(effectiveSize);
      }
      Paint.FontMetrics valueMetrics = glucosePaint.getFontMetrics();
      float valueTextHeight = valueMetrics.descent - valueMetrics.ascent;
      // Vertically center the value in the canvas (less timeHeight reserve
      // since we no longer use that bottom strip — time moved into the cluster).
      float gety = (canvasHeight + valueTextHeight) / 2f - valueMetrics.descent;
      canvas.drawText(valueStr, leftInset, gety, glucosePaint);

      // Cluster vertical layout: arrow above, time directly below, with the
      // arrow's vertical center aligned to the value's vertical center so the
      // pair reads as a single unit beside the value.
      float valueCenterY = gety + (valueMetrics.ascent + valueMetrics.descent) / 2f;
      float clusterGap = reducedTimeSize * 0.65f;
      float clusterHeight = arrowHeight + clusterGap + reducedTimeSize;
      float clusterTop = valueCenterY - clusterHeight / 2f;

      if (arrowBitmap != null) {
         float arrowLeft = clusterCenterX - arrowWidth / 2f;
         canvas.drawBitmap(arrowBitmap, arrowLeft, clusterTop, null);
      } else if (!isNaN(rate)) {
         drawarrow(canvas, glucosePaint, usedensity, rate, clusterCenterX, clusterTop + arrowHeight / 2f);
      }

      glucosePaint.setTextSize(reducedTimeSize);
      glucosePaint.setTextAlign(Paint.Align.CENTER);
      glucosePaint.setAlpha(200);
      float timeBaseline = clusterTop + arrowHeight + clusterGap + reducedTimeSize * 0.65f;
      canvas.drawText(timestr, clusterCenterX, timeBaseline, glucosePaint);
      glucosePaint.setAlpha(255);
      glucosePaint.setTextAlign(Paint.Align.LEFT);

      canvas.setBitmap(glucoseBitmap);
      remoteViews.setImageViewBitmap(arrowandvalue, glucoseBitmap);
      return remoteViews;
   }

   final RemoteViews arrowremote(int kind, notGlucose glucose, final boolean alarm) {
      RemoteViews remoteViews = new RemoteViews(Applic.app.getPackageName(),
            alarm ? R.layout.alarm : R.layout.arrowandvalue);
      if (alarm) {
         Intent closeintent = new Intent(Applic.app, NumAlarm.class);
         closeintent.setAction(stopalarmAction);
         PendingIntent closepending = PendingIntent.getBroadcast(Applic.app, stopalarmrequest, closeintent,
               PendingIntent.FLAG_UPDATE_CURRENT | penmutable);
         remoteViews.setOnClickPendingIntent(R.id.stopalarm, closepending);
      }
      if (glucose == null || glucose.value == null) {
         return remoteViews;
      }
      var gety = (canvas.getHeight() - timeHeight) * 0.98f;
      var getx = notglucosex;
      var rate = glucose.rate;
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      final var useglsize = glucosesize;
      final var usedensity = density;
      glucosePaint.setTextSize(useglsize);

      // Set Font
      try {
         android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(Applic.app,
               R.font.ibm_plex_sans_var);
         glucosePaint.setTypeface(tf);
      } catch (Throwable t) {
      }

      if (isNaN(rate)) {
         getx *= 0.82f;
      } else {
         float weightrate = 0.0f, arrowy;
         weightrate = (rate > 1.6 ? -1.0f : (rate < -1.6 ? 1.0f : (rate / -1.6f)));
         arrowy = gety - useglsize * .4f + weightrate * useglsize * .4f;
         {
            if (doLog) {
               Log.i(LOG_ID, "weightrate=" + weightrate + " arrowy=" + arrowy);
            }
            ;
         }
         ;
         drawarrow(canvas, glucosePaint, usedensity, rate, getx * .85f, arrowy);
      }

      canvas.drawText(glucose.value, getx, gety, glucosePaint);
      final boolean glucosealarm = kind < 2 || kind > 4;
      if (kind < 50) {
         float valwidth = glucosePaint.measureText(glucose.value, 0, glucose.value.length());
         if (!glucosealarm) {
            glucosePaint.setTextSize(useglsize * .4f);
            canvas.drawText(unitlabel, getx + valwidth + useglsize * .2f, gety - useglsize * .25f, glucosePaint);
         } else {
            glucosePaint.setTextSize(useglsize * .65f);
            canvas.drawText(" " + Notify.alarmtext(kind), getx + valwidth + useglsize * .2f, gety - useglsize * .15f,
                  glucosePaint);
         }
      } else {
         // var timestr= DateFormat.getTimeInstance(DateFormat.SHORT).format(new
         // Date(glucose.time));
         var timestr = minhourstr(glucose.time);
         glucosePaint.setTextSize(timesize);
         canvas.drawText(timestr, usedensity * 16, gety + timeHeight, glucosePaint);
         {
            if (doLog) {
               Log.i(LOG_ID, "time: " + timestr);
            }
            ;
         }
         ;
      }
      canvas.setBitmap(glucoseBitmap);
      remoteViews.setImageViewBitmap(arrowandvalue, glucoseBitmap);
      return remoteViews;
   }
}
