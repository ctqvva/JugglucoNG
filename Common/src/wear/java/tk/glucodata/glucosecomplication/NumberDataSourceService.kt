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
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Log

class NumberDataSourceService: SuspendingComplicationDataSourceService()  {
private val glview= GlucoseValue(100,100)

    override fun onComplicationActivated( complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData {
        val reading = GlucoseComplicationData.previewReading()
        val tapAction = GlucoseComplicationData.tapAction()
        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(
                        Icon.createWithBitmap(
                            glview.getNumberBitmap(
                                reading.text,
                                reading.timeMillis,
                                reading.index,
                                System.currentTimeMillis(),
                            ),
                        ),
                        SmallImageType.PHOTO,
                    ).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose Value").build(),
                )
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.LONG_TEXT -> GlucoseComplicationData.longTextData(
                reading,
                "Glucose Value",
                tapAction,
            )
            ComplicationType.SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                reading,
                "Glucose Value",
                tapAction,
            )
            ComplicationType.RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                reading,
                "Glucose Value",
                tapAction,
            )
            else -> GlucoseComplicationData.shortValueData(null, "Glucose Value", tapAction)
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(LOG_ID, "onComplicationRequest() id: ${request.complicationInstanceId}")

        val complicationPendingIntent = GlucoseComplicationData.tapAction()
        val glucose = GlucoseComplicationData.currentReading()

        return when (request.complicationType) {
            ComplicationType.SMALL_IMAGE-> {
	      var image=
	      if(glucose==null) {
		 Log.i(LOG_ID,"glucose==null")
		  ComplicationRenderer.noValueBitmap(VALUE_PX)
		 }
	      else {
		    Log.i(LOG_ID,"glucose==${glucose.text}")
		   // Value over its time, through the shared renderer: this is the
		   // form that shows when the reading was taken.
		   ComplicationRenderer.valueWithTimeBitmap(
		       VALUE_PX, glucose.text, glucose.value, glucose.timeMillis,
		       ComplicationRenderer.isMmol(),
		   )
		  }
                SmallImageComplicationData.Builder( SmallImage.Builder( Icon.createWithBitmap(image), SmallImageType.PHOTO).build(), contentDescription = PlainComplicationText.Builder("Glucose Number").build()).setTapAction(complicationPendingIntent).build()
		}
            ComplicationType.LONG_TEXT -> GlucoseComplicationData.longTextData(
                glucose,
                "Glucose Number",
                complicationPendingIntent,
            )
            ComplicationType.SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                glucose,
                "Glucose Number",
                complicationPendingIntent,
            )
            ComplicationType.RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                glucose,
                "Glucose Number",
                complicationPendingIntent,
            )

            else -> {
                Log.w(LOG_ID, "Unexpected complication type ${request.complicationType}")
                null
            }
        }
    }


    companion object {
        private const val LOG_ID = "NumberDataSourceService"
        private const val VALUE_PX = 320
   private val complicationDataSourceUpdateRequester = ComplicationDataSourceUpdateRequester.create( context=tk.glucodata.Applic.app, complicationDataSourceComponent = ComponentName(tk.glucodata.Applic.app,
       NumberDataSourceService::class.java
   ))

        public fun update() {
            complicationDataSourceUpdateRequester.requestUpdateAll()
        }
    }
}
