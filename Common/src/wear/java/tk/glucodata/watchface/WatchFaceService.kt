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
/*      Fri Jan 27 15:33:27 CET 2023                                                 */


package tk.glucodata.watchface

import tk.glucodata.Log
import android.view.SurfaceHolder
import androidx.annotation.UiThread
//import androidx.annotation.Keep
import androidx.wear.watchface.*
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import tk.glucodata.Applic
import tk.glucodata.MainActivity
import tk.glucodata.watchface.utils.createComplicationSlotManager
import tk.glucodata.watchface.utils.createUserStyleSchema

/**
 * Handles much of the boilerplate needed to implement a watch face (minus rendering code; see
 * [WatchRenderer]) including the complications and settings (styles user can change on
 * the watch face).
 */
//class JugglucoWatchFaceService : WatchFaceService() {
//@Keep
class WatchFaceService : WatchFaceService() {

    // Used by Watch Face APIs to construct user setting options and repository.
    override fun createUserStyleSchema(): UserStyleSchema = createUserStyleSchema(context = applicationContext)

    // Creates all complication user settings and adds them to the existing user settings
    // repository.
    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager = createComplicationSlotManager(
        context = applicationContext,
        currentUserStyleRepository = currentUserStyleRepository
    )

class Mytaplistener: WatchFace.TapListener {
        val xtapmax:Int;val ytapmin:Int;val ytapmax:Int;
    init {
//        xtapmin=(MainActivity.screenwidth*.35).toInt()
//        xtapmax=(MainActivity.screenwidth*.65).toInt()
        xtapmax=(MainActivity.screenwidth*.30).toInt()
        ytapmin=(MainActivity.screenheight*.48).toInt()
        ytapmax=(MainActivity.screenheight*.70).toInt()
        Log.i(LOG_ID,"init xtapmax=$xtapmax ytapmin=$ytapmin ytapmax=$ytapmax ")
        }
	@UiThread
	override fun onTapEvent(
        tapType: Int,
        tapEvent: TapEvent,
        complicationSlot: ComplicationSlot?
	) {
	if(tk.glucodata.Natives.turnoffalarm()) tk.glucodata.Notify.stopalarm();
	Log.d(LOG_ID,"Tap ${tapEvent.xPos} ${tapEvent.yPos}")
    if(tapEvent.xPos <xtapmax &&tapEvent.yPos>=ytapmin&&tapEvent.yPos<ytapmax) {
        Applic.RunOnUiThread { Applic.startMain() }
    }

	}

}

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        Log.d(LOG_ID, "createWatchFace()")

        // Creates class that renders the watch face.
        val renderer = WatchRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            complicationSlotsManager = complicationSlotsManager,
            currentUserStyleRepository = currentUserStyleRepository,
            canvasType = CanvasType.HARDWARE
        )
        // Creates the watch face.
        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        ).setTapListener(Mytaplistener())
    }

    companion object {
        const val LOG_ID = "WatchFaceService"
    }
}
