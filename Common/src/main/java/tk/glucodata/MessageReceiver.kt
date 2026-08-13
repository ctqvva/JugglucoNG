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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata

import android.content.Intent
import com.google.android.gms.wearable.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import tk.glucodata.Applic.isWearable
import tk.glucodata.Log.doLog
import tk.glucodata.MainActivity.setbluetoothon
import tk.glucodata.MessageSender.Companion.isGalaxy
//import tk.glucodata.MessageSender.Companion.messagesender
import tk.glucodata.MessageSender.Companion.sendnetinfo
import tk.glucodata.Natives
import tk.glucodata.Natives.setWearosdefaults

class MessageReceiver: WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        val data= messageEvent.getData();
        val path= messageEvent.path
        if (!isWearable && !MessageSender.outgoingAllowed()) {
            val now = System.currentTimeMillis()
            val previous = lastDisabledMessageLogMs.get()
            if (now - previous >= DISABLED_MESSAGE_LOG_INTERVAL_MS &&
                lastDisabledMessageLogMs.compareAndSet(previous, now)
            ) {
                Log.i(LOG_ID, "ignoring Wear messages: companion disabled")
            }
            return
        }
        Log.i(LOG_ID,"onMessageReceived start $path"  )
        when(path) {
            MessageSender.DEFAULTS_PATH ->  {
                val sender = tk.glucodata.MessageSender.getMessageSender()
                if (sender == null) {
                    Log.d(LOG_ID, "messagesender==null")
                    return
                    }
                val source=  sender.localnode
                 if(doLog) {Log.i(LOG_ID,"path==MessageSender.DEFAULTS_PATH "+source );}
                  setWearosdefaults(source,true);
                   val context=if(MainActivity.thisone==null)Applic.app;else MainActivity.thisone;
                   Applic.setbluetooth(context,false)
                 }
            MessageSender.WAKE_PATH -> {
                Natives.wakehereonly()
                }
            MessageSender.WAKESTREAM_PATH -> {
                Natives.wakestreamhereonly()
                }
            MessageSender.DATA_PATH   -> {
                // Phone-to-phone mirroring only. On the watch this legacy
                // stream wrote the sensor's uncalibrated values into the same
                // minute slots WearSync2 fills with calibrated ones, so the
                // displayed value flipped between the two depending on which
                // arrived last.
                if (isWearable) {
                    if (doLog) Log.i(LOG_ID, "ignoring legacy /data on wear; WearSync2 owns the store")
                } else {
                    Natives.message(data);
                }
            }
            MessageSender.SYNC2_REQ_PATH -> {
                if (!isWearable) {
                    WearSync2.onRequest(data)
                    // Rides along with the sync the watch already asks for, and
                    // sends nothing while the scheme is unchanged. Without it a
                    // watch that missed the change-time push would keep the
                    // compiled-in defaults for good.
                    GlucoseColorSync.pushIfChanged(messageEvent.sourceNodeId)
                    WearPrefsSync.pushIfChanged(messageEvent.sourceNodeId)
                    WearToggleSync.pushIfChanged(messageEvent.sourceNodeId)
                }
            }
            MessageSender.SYNC2_CHUNK_PATH -> {
                // Either device may be the one holding the sensor, so both accept
                // readings; WearSync2 drops any for a sensor it reads itself.
                WearSync2.onChunk(data)
            }
            MessageSender.SYNC2_CAL_PATH -> {
                if (isWearable) WearSync2.onCalibration(data)
            }
            MessageSender.CALIBRATION_CMD_PATH -> {
                if (!isWearable) WearCalibrationCommand.onCommand(data)
            }
            MessageSender.SYNC2_REMOVE_PATH -> {
                if (isWearable) WearSync2.onRemove(data)
            }
            MessageSender.JOURNAL_REQ_PATH -> {
                if (!isWearable) WearJournalSync.onRequest(
                    if (data != null && data.size >= 9) {
                        java.nio.ByteBuffer.wrap(data, 1, 8).long
                    } else {
                        0L
                    }
                )
            }
            MessageSender.JOURNAL_DATA_PATH -> {
                if (isWearable) WearJournalSync.onServed(data)
            }
            MessageSender.SENSOR_OWNERSHIP_PATH -> {
                // Both devices arbitrate, so neither side is gated here.
                SensorOwnershipRuntime.onPeerReport(data)
            }
            MessageSender.JOURNAL_CMD_PATH -> {
                if (!isWearable) WearJournalSync.onCommand(data)
            }
            MessageSender.SENSOR_HANDOFF_PATH -> {
                if (isWearable) {
                    // Persist the identity only. Do NOT flip the watch into
                    // "I own the sensor" here: the watch advertises that in
                    // netinfo, and the phone answers by dropping its own BLE
                    // and stopping the stream (netinfo.cpp) — so claiming it
                    // before a real local connection exists kills data on both
                    // devices. The watch starts owning the sensor only once it
                    // actually connects.
                    val context = if (MainActivity.thisone == null) Applic.app else MainActivity.thisone
                    val ok = ManagedSensorHandoff.applyIncoming(context, data)
                    Log.i(LOG_ID, "sensor handoff stored=$ok")
                }
            }
            MessageSender.SENSOR_CLAIM_STATUS_PATH -> {
                if (!isWearable) {
                    WearSensorClaimStatus.onRemoteStatus(messageEvent.sourceNodeId, data)
                }
            }
            MessageSender.CALIBRATE_PATH -> {
                // Watch-relayed fingerstick calibration; applied to the local
                // driver that owns the BLE connection.
                if (data != null && data.size >= 4) {
                    val mgdl = java.nio.ByteBuffer.wrap(data).int
                    MessageSender.scope.launch {
                        tk.glucodata.drivers.ManagedCalibration.applyFingerstickCalibration(mgdl)
                    }
                }
            }
            MessageSender.NET_PATH   -> {
                // The switch may have changed after this callback entered. Do
                // not let an in-flight /netinfo recreate the native Wear host
                // after shutdown has just deactivated it.
                if (!isWearable && !MessageSender.outgoingAllowed()) return
                MessageSender.markNetInfoExchanged()
                val sender = tk.glucodata.MessageSender.getMessageSender()
                if (sender == null) {
                    Log.d(LOG_ID, "messagesender==null")
                    return
                }
                val nodes = sender.nodes
                if(nodes == null || nodes.isEmpty()) {
                    Log.e(LOG_ID, "no nodes")
                    MessageSender.scope.launch {
                        sender.findWearDevicesWithApp()
                    }
                    return
                }
                val sourceId = messageEvent.getSourceNodeId()
                val name: String
                val galaxy: Boolean
                if (isWearable) {
                    name = sender.localnode
                    galaxy = true;
                } else {
                    name = sourceId
                    val it = sender.findnodeid(sourceId)
                    if (it < 0)
                        return
                    val node: Node = nodes.elementAt(it)
                    galaxy = isGalaxy(node)
                }



                if(Natives.setmynetinfo(name, data, galaxy)) {
                    sendnetinfo(sourceId)
                    if (isWearable) {
                        MessageSender.sendSensorClaimStatus()
                    }
                }
            }
            MessageSender.START_PATH ->  {
               if(isWearable)
                  UseWifi.usewifi()
               val context=Applic.getContext()
               Applic.setinittext(context.getString(R.string.connected));
               Applic.initStarted=Natives.ontbytesettings(data)
               Notify.mkunitstr(context,Natives.getunit())
               sendnetinfo(messageEvent.getSourceNodeId())
            }
             MessageSender.SETTINGS_PATH   -> { //Never used
                 Natives.ontbytesettings(data)
                    Notify.mkunitstr(Applic.app,Natives.getunit())
                }
             MessageSender.TOGGLE_REQ_PATH -> {
                 if (!isWearable) WearToggleSync.pushTo(messageEvent.sourceNodeId)
                }
             MessageSender.TOGGLE_CMD_PATH -> {
                 // The phone owns these; it applies and then reports back what
                 // it actually holds, so a refused switch snaps back on the
                 // watch rather than showing a state that is not real.
                 if (!isWearable) WearToggleSync.onCommand(data, messageEvent.sourceNodeId)
                }
             MessageSender.TOGGLE_STATE_PATH -> {
                 if (isWearable) WearToggleSync.onState(data)
                }
             MessageSender.WEAR_PREFS_REQ_PATH -> {
                 // Pull, not push. Relying on the phone to push at the right
                 // moment meant a watch whose app opened outside that window
                 // kept the compiled-in defaults with no way to ask; the journal
                 // has always worked request/serve for the same reason.
                 if (!isWearable) {
                     WearPrefsSync.pushTo(messageEvent.sourceNodeId)
                     GlucoseColorSync.pushTo(messageEvent.sourceNodeId)
                     WearToggleSync.pushTo(messageEvent.sourceNodeId)
                 }
                }
             MessageSender.WEAR_PREFS_PATH -> {
                 // The phone owns these settings; the watch only mirrors them,
                 // so smoothing and prediction behave the same on both.
                 // apply() raises UiRefreshBus, which is what the watch's history
                 // store listens on; this file compiles into both variants, so it
                 // must not name a wear-only class directly.
                 if (isWearable && WearPrefsSync.apply(Applic.app, data) == 0) {
                     Log.w(LOG_ID, "unusable display-prefs payload; keeping current settings")
                 }
                }
             MessageSender.GLUCOSE_COLORS_PATH -> {
                 // The phone owns the palette; the watch only mirrors it, so a
                 // preset or custom band edit shows on both without a rebuild.
                 if (isWearable) {
                     if (!GlucoseColorSync.apply(Applic.app, data)) {
                         Log.w(LOG_ID, "unusable glucose colour payload; keeping current scheme")
                     }
                 }
                }
             MessageSender.MESSAGES_PATH -> {
                 val sender=tk.glucodata.MessageSender.getMessageSender()
                 if(sender==null) {
                     Log.d(LOG_ID,"2: messagesender==null")
                     return
                 }
                 val sourceId= messageEvent.getSourceNodeId()
                 val name:String=(if(isWearable) sender.localnode; else sourceId)?:return
                val on=booldata(data)
                Natives.setBlueMessage(name,on)
                }
             MessageSender.BLUETOOTH_PATH -> {
                val context=if(MainActivity.thisone==null)Applic.app;else MainActivity.thisone;
                val on=booldata(data)
                if(tk.glucodata.Log.doLog) {Log.i(LOG_ID,"set bluetooth $on  ${data[0]}");}
                if (isWearable) WearSensorClaim.setDirectRequested(on)
                Applic.setbluetooth(context,on )
                }
             MessageSender.ASKFORSTART_PATH -> {
                 if(!isWearable) {
                     // Fresh watch: serve the full sync2 backfill alongside the
                     // legacy start flow.
                     WearSync2.serveAll()
                     // Colours are pushed on every change, but a watch that was
                     // off or unpaired then missed them; the handshake is the
                     // one point where it is certain to be listening.
                     GlucoseColorSync.pushTo(messageEvent.sourceNodeId)
                     WearPrefsSync.pushTo(messageEvent.sourceNodeId)
                     val sender = tk.glucodata.MessageSender.getMessageSender()
                     if (sender == null) {
                         Log.d(LOG_ID, "3: messagesender==null")
                         return
                     }
                     val sourceId = messageEvent.sourceNodeId
                     val it = sender.findnodeid(sourceId)
                     if (it < 0) {
                         Log.e(LOG_ID, "sender.findnodeid(sourceId)<0")
                         return
                     }
                     val nodes = sender.nodes
                     if (nodes.isNullOrEmpty()) {
                         Log.e(LOG_ID, "3: no nodes")
                         MessageSender.scope.launch {
                             sender.findWearDevicesWithApp()
                         }
                         return;
                     }
                     val node: Node = nodes.elementAt(it)
                     Wearos.sendinitwatchapp(node);
                 }
               }
        }
        Log.i(LOG_ID,"onMessageReceived end $path"  )
      }

 companion object {
   private const val LOG_ID = "MessageReceiver"
   private const val DISABLED_MESSAGE_LOG_INTERVAL_MS = 60_000L
   private val lastDisabledMessageLogMs = AtomicLong(0L)
    private const val offbyte:Byte=0
    fun booldata(data:ByteArray):Boolean {
        return data[0]!=offbyte
        }
       }
   }
