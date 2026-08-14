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
/*      Fri Oct 11 12:22:15 CEST 2024                                                 */


package tk.glucodata.glucosecomplication


import android.content.ComponentName
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ComplicationType.*
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.Notify
import tk.glucodata.R
import java.lang.Math.min

class ShortArrowValueDataSourceService: SuspendingComplicationDataSourceService()  {
    /**
     * Arrow plus the reading's time, drawn by ComplicationRenderer so the angle
     * and colour match the app rather than the legacy geometry.
     */
    private fun arrowTimeBitmap(rate: Float, timeMillis: Long): android.graphics.Bitmap =
        // The value is in this complication's text field, so the icon is the
        // arrow alone: a time caption under it only made both smaller. Drawn as
        // a mask, since the face tints it.
        ComplicationRenderer.arrowBitmap(ICON_PX, rate, ComplicationRenderer.ICON_TINT)

    private val ICON_PX = 256

private var glview: GlucoseValue? =null

    override fun onComplicationActivated( complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }
fun getview(type: ComplicationType):GlucoseValue {
     if(glview==null) {
        val width:Int = 150
        val height:Int = 150
         glview= GlucoseValue(width,height)
         }
      return glview as GlucoseValue;
      }
    override fun getPreviewData(type: ComplicationType): ComplicationData {
      val reading = GlucoseComplicationData.previewReading()
      val tapAction = GlucoseComplicationData.tapAction()
      val icon=Icon.createWithBitmap( arrowTimeBitmap(reading.rate, reading.timeMillis));
       Log.i(LOG_ID,"getPreviewData $type")
         return when (type) {
             SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                 reading,
                 "Small Glucose",
                 tapAction,
                 icon,
              )
             LONG_TEXT -> GlucoseComplicationData.longTextData(
                 reading,
                 "Small Glucose",
                 tapAction,
                 icon,
              )
             RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                 reading,
                 "Small Glucose",
                 tapAction,
                 icon,
             )
             else -> GlucoseComplicationData.shortValueData(null, "Small Glucose", tapAction)
         }
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(LOG_ID, "onComplicationRequest() id: ${request.complicationInstanceId}")
        val complicationPendingIntent = GlucoseComplicationData.tapAction()
        val type=        request.complicationType
      val glucose = GlucoseComplicationData.currentReading()
      if(glucose==null) {
         Log.i(LOG_ID,"no glucose") 
           return when (type) {
               RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                   null,
                   "Small Glucose",
                   complicationPendingIntent,
               )
               else -> GlucoseComplicationData.shortValueData(
                   null,
                   "Small Glucose",
                   complicationPendingIntent,
               )
           }
         }
      else {

            val bitmap=arrowTimeBitmap(glucose.rate, glucose.timeMillis);
            Log.i(LOG_ID," glucose==${glucose.text}")
                val image=Icon.createWithBitmap(bitmap)
             return when (type) {
                 RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                     glucose,
                     "Small Glucose",
                     complicationPendingIntent,
                     image,
                 )
                 LONG_TEXT -> GlucoseComplicationData.longTextData(
                     glucose,
                     "Small Glucose",
                     complicationPendingIntent,
                     image,
                      )
                 else -> GlucoseComplicationData.shortValueData(
                     glucose,
                     "Small Glucose",
                     complicationPendingIntent,
                     image,
                      )
             }
            }
    }

    companion object {
        private const val LOG_ID = "ShortArrowValueDataSourceService"
   private val complicationDataSourceUpdateRequester = ComplicationDataSourceUpdateRequester.create( context=tk.glucodata.Applic.app, complicationDataSourceComponent = ComponentName(tk.glucodata.Applic.app,
    ShortArrowValueDataSourceService::class.java
   ))

        public fun update() {
            complicationDataSourceUpdateRequester.requestUpdateAll()
        }
    }
}
