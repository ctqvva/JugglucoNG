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
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ComplicationType.*
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.PlainComplicationText
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
import java.lang.Math.min

class ArrowValueDataSourceService: SuspendingComplicationDataSourceService()  {
private var glview: GlucoseValue? =null
private val iconView = GlucoseValue(150,150)

    override fun onComplicationActivated( complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }
fun getview(type: ComplicationType):GlucoseValue {
     if(glview==null) {
        val width:Int
        val height:Int
         if(type==PHOTO_IMAGE ) {
             val size= kotlin.math.min(MainActivity.screenheight, MainActivity.screenwidth)
               height=size
                width=size
 //            height= MainActivity.screenheight
  //           width=MainActivity.screenwidth
             }
           else {
             width = 150
             height = 150
            }
         glview= GlucoseValue(width,height)
         }
      return glview as GlucoseValue;
      }
    override fun getPreviewData(type: ComplicationType): ComplicationData {

        val reading = GlucoseComplicationData.previewReading()
        val tapAction = GlucoseComplicationData.tapAction()
        return when (type) {
        
/*         MONOCHROMATIC_IMAGE -> {
            Log.i(LOG_ID,"MonochromaticImage")
            MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder (Icon.createWithBitmap(
                        glview.getArrowValueBitmap(
                            value,
                            time,
                            index,
                            rate
                        )
                    )).build(), contentDescription = PlainComplicationText.Builder("Glucose+arrow").build()
                ).setTapAction(null).build()
			    }  */
            PHOTO_IMAGE -> {
                Log.i(LOG_ID,"getPreviewData PHOTO_IMAGE")
                val preview = GlucoseComplicationData.previewReading()
                val icon=Icon.createWithBitmap(
                    ComplicationRenderer.valueWithArrowBitmap(
                        VALUE_ARROW_PX, preview.text, preview.value, preview.rate,
                        ComplicationRenderer.isMmol(),
                    )
                )
                PhotoImageComplicationData.Builder(photoImage = icon, contentDescription = PlainComplicationText.Builder("Glucose+arrow").build()
                ).setTapAction(tapAction).build()
            } 
            SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                reading,
                "Glucose+arrow",
                tapAction,
                arrowIcon(reading),
            )
            RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                reading,
                "Glucose+arrow",
                tapAction,
                arrowIcon(reading),
            )
            SMALL_IMAGE ->
            //ComplicationType.SMALL_IMAGE -> 
            {
               Log.i(LOG_ID,"getPreviewData OTHER")
                 val preview = GlucoseComplicationData.previewReading()
                val icon=Icon.createWithBitmap(
                    ComplicationRenderer.valueWithArrowBitmap(
                        VALUE_ARROW_PX, preview.text, preview.value, preview.rate,
                        ComplicationRenderer.isMmol(),
                    )
                )
                 SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder( icon, SmallImageType.PHOTO).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose+arrow")
                        .build()
                )
                    .setTapAction(tapAction)
                    .build()
            }
            else -> GlucoseComplicationData.shortValueData(null, "Glucose+arrow", tapAction)

        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(LOG_ID, "onComplicationRequest() id: ${request.complicationInstanceId}")

        val complicationPendingIntent = GlucoseComplicationData.tapAction()
    val type=        request.complicationType
      val glucose = GlucoseComplicationData.currentReading()
      if (type == SHORT_TEXT) {
          return GlucoseComplicationData.shortValueData(
              glucose,
              "Glucose Arrow+Value",
              complicationPendingIntent,
              glucose?.let { arrowIcon(it) },
          )
      }
      if (type == RANGED_VALUE) {
          return GlucoseComplicationData.rangedValueData(
              glucose,
              "Glucose Arrow+Value",
              complicationPendingIntent,
              glucose?.let { arrowIcon(it) },
          )
      }
      val bitmap=
      if(glucose==null) {
         Log.i(LOG_ID,"glucose==null") 
	      getview(type).getnovalue()
         }
	else {
         Log.i(LOG_ID,"glucose==${glucose.text}")
      // Through ComplicationRenderer, so this slot uses the app's arrow angle
      // and palette like the others rather than the legacy geometry.
      ComplicationRenderer.valueWithArrowBitmap(
          VALUE_ARROW_PX, glucose.text, glucose.value, glucose.rate, ComplicationRenderer.isMmol(),
      )
	}

	val image=Icon.createWithBitmap(bitmap)
    return when (type) {
         PHOTO_IMAGE -> {
         Log.i(LOG_ID,"PHOTO_IMAGE")
                PhotoImageComplicationData.Builder( image , contentDescription = PlainComplicationText.Builder("Glucose Arrow+Value").build()).setTapAction(complicationPendingIntent).build()
			    }

           else -> {
             Log.i(LOG_ID,"SMALL_IMAGE")
                SmallImageComplicationData.Builder( SmallImage.Builder( image, SmallImageType.PHOTO).build(), contentDescription = PlainComplicationText.Builder("Glucose Arrow+Value").build()).setTapAction(complicationPendingIntent).build()
//		setAmbientImage(Icon ambientImage) ??
			    }
            }

    }

    private fun arrowIcon(reading: GlucoseComplicationData.Reading): Icon =
        Icon.createWithBitmap(iconView.getArrowTimeBitmap(reading.timeMillis, reading.rate))

    companion object {
        private const val LOG_ID = "ArrowValueDataSourceService"
        private const val VALUE_ARROW_PX = 256
   private val complicationDataSourceUpdateRequester = ComplicationDataSourceUpdateRequester.create( context=tk.glucodata.Applic.app, complicationDataSourceComponent = ComponentName(tk.glucodata.Applic.app,
       ArrowValueDataSourceService::class.java
   ))

        public fun update() {
            complicationDataSourceUpdateRequester.requestUpdateAll()
        }
    }
}
