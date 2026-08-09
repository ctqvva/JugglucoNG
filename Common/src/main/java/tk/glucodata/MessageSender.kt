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

//import androidx.activity.Context
//import androidx.lifecycle.Lifecycle
//import androidx.lifecycle.lifecycleScope
import android.content.Context
import androidx.annotation.Keep
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import tk.glucodata.Applic.JUGGLUCOIDENT;
import tk.glucodata.Applic.isWearable
import tk.glucodata.Log.doLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

//import tk.glucodata.Applic.messagesender


class MessageSender(val activity: Context):CapabilityClient.OnCapabilityChangedListener {
    private val messageClient by lazy { Wearable.getMessageClient(activity) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(activity) }
    private val nodeClient by lazy { Wearable.getNodeClient(activity) }
    public val localnodeall by lazy { Tasks.await(nodeClient.localNode) }
    public val localnode by lazy { localnodeall.id }
    public val galaxywatch by lazy {
        isGalaxy(localnodeall) }

    var nodes: Set<Node>? = null
    private var nexttimes:LongArray?=null

    private fun setnodes(ns:Set<Node>) {
        nodes = ns
        val len: Int = nodes?.size ?: 0
        nexttimes = LongArray(len)
        sendnetinfo();
    }
    public fun nulltimes() {
        nexttimes?.fill(0L)
    }
var nodesbusy=false
suspend fun findWearDevicesWithApp() {
    if (wearableApiUnavailable) {
        Log.d(LOG_ID, "findWearDevicesWithApp skipped: Wearable.API unavailable")
        return
    }
    Log.i(LOG_ID,"start findWearDevicesWithApp nodesbusy=$nodesbusy")
    if(nodesbusy)        
        return;
    nodesbusy=true;
    try {
        val capabilityInfo = capabilityClient.getCapability( JUGGLUCOIDENT, CapabilityClient.FILTER_REACHABLE).await()
        setnodes(capabilityInfo.nodes)
        Log.d(LOG_ID, "Capable Nodes: $nodes")
        Natives.isGalaxyWatch(galaxywatch)
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (th: Throwable) {
        if (th is ApiException && th.statusCode == API_UNAVAILABLE_STATUS) {
            markWearableApiUnavailable("findWearDevicesWithApp", th)
        } else {
            Thread.currentThread().setName("Devices$findIter")
            ++findIter
            Log.stack(LOG_ID, "findDev",th)
        }
    }
    finally {
        Log.i(LOG_ID,"end findWearDevicesWithApp nodesbusy=false")
        nodesbusy=false
    }
    }

public fun finddevices() {
     if (wearableApiUnavailable) {
         Log.w(LOG_ID, "finddevices skipped: Wearable.API unavailable")
         return
     }
     if (!GoogleServices.isPlayServicesAvailable(activity)) {
         Log.w(LOG_ID, "finddevices skipped: Google Play Services unavailable")
         return
     }
     val sender=this
     scope.launch {
      findWearDevicesWithApp()
      }
     try {
         Wearable.getCapabilityClient(activity).addListener(sender, JUGGLUCOIDENT)
     } catch (th: Throwable) {
         if (th is ApiException && th.statusCode == API_UNAVAILABLE_STATUS) {
             markWearableApiUnavailable("addCapabilityListener", th)
         } else {
             Log.stack(LOG_ID, "addCapabilityListener", th)
         }
     }
     }

  init {
      finddevices()
      }

/*
    public fun startActivity() {
    val data=Natives.bytesettings()
      sendmessage(START_PATH, data) 
    } */

public fun startWearOSActivity(nodeName:String) {
    val data=Natives.bytesettings()
    nameSendMessage(nodeName,START_PATH,data)
    }
public fun toDefaults(node:Node) {
    val nodata:ByteArray=byteArrayOf(0)
    nodeSendmessage(node,DEFAULTS_PATH,nodata)
    }
/*
private fun startnodedetection(context: Context):String? {
    Wearable.getCapabilityClient(context).addListener( this,JUGGLUCOIDENT )
    val capabilityInfo: CapabilityInfo = Tasks.await(capabilityClient.getCapability( JUGGLUCOIDENT, CapabilityClient.FILTER_REACHABLE))
    return pickBestNodeId(capabilityInfo.nodes)
}
*/
//private var transcriptionNodeId: String? = null



override fun onCapabilityChanged(cap: CapabilityInfo) {
        scope.launch {
            setnodes(cap.nodes)
        }
    }
    private fun sendmessage(path:String,data:ByteArray) {
            if (!outgoingAllowed()) {
                if (doLog) { Log.i(LOG_ID, "sendmessage($path) skipped: companion disabled") }
                return
            }
            try {
        when {
            nodes == null -> {
                Log.d(LOG_ID, "sendmessage nodes=null")
                scope.launch {
                findWearDevicesWithApp()
                }
            }
            nodes?.isEmpty() == true -> {
                Log.d(LOG_ID, "sendmessage nodes.isEmpty")
            }
            else -> {
                    nodes?.map { node ->
                        scope.launch {
                            Log.i(LOG_ID, "sendMessage(${node.id} ${node.displayName}, $path,)")
                            try {
                                messageClient.sendMessage(node.id, path, data)
                            } catch (th: Throwable) {
                                Log.stack(LOG_ID, th);
                            }
                           }
                        }
                    Log.d(LOG_ID, "Starting requests sent successfully")
                }
            }
            } catch (exception: Exception) {
                Log.d(LOG_ID, "Starting activity failed: $exception")
        }
    }
private fun nameSendMessage(name:String, path:String, data:ByteArray) {
    if (!outgoingAllowed()) {
        if (doLog) { Log.i(LOG_ID, "nameSendMessage($path) skipped: companion disabled") }
        return
    }
    scope.launch {
        Log.i(LOG_ID, "start sendNameMessage($name $path,... )")
        try {
            messageClient.sendMessage(name, path, data)
             }
        catch (th: Throwable) { Log.stack(LOG_ID, th); }
        finally{
            Log.i(LOG_ID,"after sendNameMessage($name $path,... )")
            }
        }
    }
private fun nameSendMessageResult(name:String, path:String, data:ByteArray):Boolean {
        if (!outgoingAllowed()) {
            if (doLog) { Log.i(LOG_ID, "nameSendMessageResult($path) skipped: companion disabled") }
            return false
        }
        try {
//            val len=data.size
//            val timeout:Long= (len / 20L).coerceAtMost(1L)
            val timeout:Long= 60L
        val res=Tasks.await(messageClient.sendMessage(name, path, data),timeout,TimeUnit.SECONDS)
        Log.i(LOG_ID,"nameSendMessageResult "+res)
        return true
        }
        catch (th: Throwable) {
                Log.stack(LOG_ID, th)
            return false
        }

    }

private fun nodeSendmessage(node:Node,path:String,data:ByteArray) {
    nameSendMessage(node.id,path,data);
    }
        
    public fun sendnetinfo(data:ByteArray) {
    sendmessage(NET_PATH,data);
        }
    public fun sendnetinfo( node:Node,data:ByteArray) {
    nodeSendmessage(node,NET_PATH,data);
        }
    public fun sendnetinfo( node:String,data:ByteArray) {
        nameSendMessage(node,NET_PATH,data);
        }
    /*
    public fun sendsettings() {
    val data=Natives.bytesettings()
    sendmessage(SETTINGS_PATH,data)
    } */
    public fun sendbluetooth( node:Node,on:Boolean) {
         sendbluetooth( node.id,on);
     }
    public fun sendbluetooth( name:String,on:Boolean) {
        sendbool(BLUETOOTH_PATH,name,on)
     }
    public fun sendSensorHandoff(name:String, data:ByteArray): Boolean {
        return nameSendMessageResult(name, SENSOR_HANDOFF_PATH, data)
     }
    private fun sendOnmessages( node:String,on:Boolean) {
        if(doLog) {Log.i(LOG_ID,"sendNameMessageOn($node,$on)");}
        sendbool(MESSAGES_PATH,node,on)
        }
     /*
    public fun sendbluetooth(on:Boolean) {
    sendbool(BLUETOOTH_PATH,on)
     }
    public fun sendbool(String path,on:Boolean) {
        val onbyte:Byte=if(on) 1;else 0;
        val onar:ByteArray= byteArrayOf(onbyte)
    sendmessage(path,onar)
     } */
    public fun sendbool( path:String,nodeName:String,on:Boolean) {
        val onbyte:Byte=if(on) 1;else 0;
        val onar:ByteArray= byteArrayOf(onbyte)
       nameSendMessage(nodeName,path,onar)
     }

   public fun     findnodeid(id:String):Int {
       val nods=nodes
       if(nods==null)
           return -1
        val num = nods.size
        var it = 0
        while(true) {
            if (it == num) {
                Log.e(LOG_ID, "Can't find $id")
                return -1;
            }
            var othernode = nods.elementAt(it)
            if (id == othernode.getId()) {
               return it;
            }
            it++
        }
    }


companion object {
    private var findIter=0;
    private const val LOG_ID = "MessageSender"
    private const val API_UNAVAILABLE_STATUS = 17
    private const val WEAR_API_UNAVAILABLE_LOG_INTERVAL_MS = 60_000L
    const val WAKE_PATH = "/wake"
    const val WAKESTREAM_PATH = "/wakestream"
    const val NET_PATH = "/netinfo"
    const val START_PATH = "/start"
    const val ASKFORSTART_PATH = "/askforstart"
    const val DEFAULTS_PATH = "/defaults"
    const val SETTINGS_PATH = "/settings"
    const val BLUETOOTH_PATH = "/bluetooth"
    const val DATA_PATH = "/data"
    const val MESSAGES_PATH = "/messages"
    const val CALIBRATE_PATH = "/calibrate"
    const val SENSOR_HANDOFF_PATH = "/sensorhandoff"
    const val SENSOR_CLAIM_STATUS_PATH = "/sensorclaimstatus"
    const val SYNC2_REQ_PATH = "/sync2/req"
    const val SYNC2_CHUNK_PATH = "/sync2/chunk"
    const val SYNC2_CAL_PATH = "/sync2/cal"
    const val CALIBRATION_CMD_PATH = "/sync2/calcmd"
    const val SYNC2_REMOVE_PATH = "/sync2/remove"
    const val JOURNAL_REQ_PATH = "/sync2/journal/req"
    const val JOURNAL_DATA_PATH = "/sync2/journal"
    const val JOURNAL_CMD_PATH = "/sync2/journal/cmd"
    const val SENSOR_OWNERSHIP_PATH = "/sync2/own"
    val scope = CoroutineScope(Dispatchers.IO+SupervisorJob()  )
    private var messagesender: MessageSender? = null
    @Volatile private var wearableApiUnavailable = false
    @Volatile private var wearableApiUnavailableLoggedAt = 0L
    private val lastNetInfoExchangeMs = AtomicLong(0L)

    @JvmStatic
    fun markNetInfoExchanged() {
        lastNetInfoExchangeMs.set(System.currentTimeMillis())
    }

    @JvmStatic
    fun sendSensorClaimStatus() {
        if (!isWearable) return
        val sender = messagesender ?: return
        sender.sendmessage(
            SENSOR_CLAIM_STATUS_PATH,
            byteArrayOf(WearSensorClaim.currentStateValue().toByte()),
        )
    }

    @JvmStatic
    fun lastNetInfoExchangeMs(): Long = lastNetInfoExchangeMs.get()

    private fun markWearableApiUnavailable(where: String, th: Throwable?) {
        wearableApiUnavailable = true
        messagesender?.nodes = emptySet()
        messagesender?.nexttimes = LongArray(0)
        val now = System.currentTimeMillis()
        if (now - wearableApiUnavailableLoggedAt >= WEAR_API_UNAVAILABLE_LOG_INTERVAL_MS) {
            val detail = th?.message ?: "API_UNAVAILABLE"
            Log.w(LOG_ID, "$where: Wearable.API unavailable ($detail). Wear transport disabled for this app run.")
            wearableApiUnavailableLoggedAt = now
        }
    }

    @JvmStatic
    fun isWearTransportAvailable(): Boolean = !wearableApiUnavailable

    @Volatile private var companionEnabled: Boolean? = null
    @Volatile private var companionEnabledAt = 0L
    private const val COMPANION_STATE_TTL_MS = 10_000L

    /**
     * Outgoing wear traffic is only allowed while the user has the companion
     * switched on. Disabling it used to turn off the receiver component alone,
     * so the phone kept streaming into a channel the user had closed.
     *
     * The answer is cached: this sits on every send, and the underlying
     * component lookup is a binder round trip.
     */
    @JvmStatic
    fun outgoingAllowed(): Boolean {
        if (isWearable) return true
        val now = System.currentTimeMillis()
        val cached = companionEnabled
        if (cached != null && now - companionEnabledAt < COMPANION_STATE_TTL_MS) return cached
        val fresh = try { Applic.useWearos() } catch (_: Throwable) { false }
        companionEnabled = fresh
        companionEnabledAt = now
        return fresh
    }

    /** Called when the user flips the companion switch, so the gate is instant. */
    @JvmStatic
    fun onCompanionEnabledChanged(enabled: Boolean) {
        companionEnabled = enabled
        companionEnabledAt = System.currentTimeMillis()
        if (!enabled) shutdownwearos()
    }

    /** Drops the transport so nothing is left holding nodes or streaming. */
    @JvmStatic
    fun shutdownwearos() {
        val sender = messagesender ?: return
        messagesender = null
        runCatching {
            sender.nodes = emptySet()
            sender.nexttimes = LongArray(0)
        }
        Log.i(LOG_ID, "wear transport shut down: companion switched off")
    }
    @JvmStatic
    public fun getMessageSender(): MessageSender? {
        return messagesender
    }

    private var nodenames: Array<String>? = null
    fun getNodeName(ident: Int): String {
        if (nodenames == null)
            throw NullPointerException("getNodeName nodenames==null")
        else
            return nodenames!!.get(ident)
    }
    @JvmStatic
    public fun sendaskforstart() {
        val sender = messagesender ?: return
        val ar = byteArrayOf(0);
        sender.sendmessage(ASKFORSTART_PATH, ar)
      }

    // Watch → phone: relay a fingerstick calibration (mg/dL) to the side that
    // owns the BLE connection in companion mode.
    @JvmStatic
    public fun sendcalibrate(glucoseMgDl: Int) {
        val sender = messagesender ?: return
        val data = java.nio.ByteBuffer.allocate(4).putInt(glucoseMgDl).array()
        sender.sendmessage(CALIBRATE_PATH, data)
    }

    @JvmStatic
    public fun sendsensorhandoff(nodeId: String, data: ByteArray): Boolean {
        val sender = messagesender ?: return false
        return sender.sendSensorHandoff(nodeId, data)
    }

    @JvmStatic
    public fun sendwake() {
        val sender = messagesender ?: return
        val ar = byteArrayOf(0);
        sender.sendmessage(WAKE_PATH, ar)
    }

    @JvmStatic
    public fun sendwakestream() {
        val sender = messagesender ?: return
        val ar = byteArrayOf(0);
        sender.sendmessage(WAKESTREAM_PATH, ar)
    }

    @JvmStatic
    /** @return false when there is no wear transport to send through. */
    public fun sendSyncMessage(path: String, data: ByteArray): Boolean {
        val sender = messagesender ?: return false
        sender.sendmessage(path, data)
        return true
    }

    @Keep
    @JvmStatic
    public fun sendDatawithName(ident: String, data: ByteArray): Boolean {
        val sender = messagesender ?: return false
    if(doLog) {Log.i(LOG_ID,"start sendDatawithName $ident");}
        val res=sender.nameSendMessageResult(ident, DATA_PATH, data)
    if(doLog) {Log.i(LOG_ID,"end sendDatawithName $ident");}
    return res;
    }

    public fun watchBluetooth(act:MainActivity,sensor:Boolean,nums:Boolean) {
        val sender = messagesender
        if (sender == null) {
            Log.e(LOG_ID, "sendData messagesender==null")
            return
            }
        if(sender.localnode==null) {
             Log.d(LOG_ID,"localnode==null")
             return
             }
        val name=sender.localnode;
        Natives.watchBluetooth(name,sensor,nums);
        }

    @Keep
    @JvmStatic
    public fun sendData(data: ByteArray): Boolean {
        val sender = messagesender
        if (sender == null) {
            Log.e(LOG_ID, "sendData messagesender==null")
            return false
        }
        val nodes = sender.nodes
        if (nodes == null) {
            Log.e(LOG_ID, "sendData nodes==null")
                scope.launch {
                sender.findWearDevicesWithApp()
                }
            return false;
        }
        if (nodes.isEmpty()) {
            Log.e(LOG_ID, "sendData nodes.isEmpty()")
            return false
        }
    Log.i(LOG_ID,"start sendData")
        val res=sender.nameSendMessageResult(nodes.elementAt(0).id, DATA_PATH, data)
    Log.i(LOG_ID,"end sendData "+res)
    return res;
    }

    @Keep
    @JvmStatic
    public fun sendNameMessageOn(ident: String, on: Boolean) {
        val sender = messagesender
        if (sender == null) {
            Log.e(LOG_ID, "messagesender==null")
            return
        }
        return sender.sendOnmessages(ident, on);
    }

    @Keep
    @JvmStatic
    public fun sendMessageOn(on: Boolean) {
        val sender = messagesender
        if (sender == null) {
            Log.e(LOG_ID, "sendMessageOn messagesender==null")
            return
        }
        val nodes = sender.nodes
        if (nodes == null) {
            Log.e(LOG_ID, "sendMessageOn nodes==null")
                scope.launch {
                sender.findWearDevicesWithApp()
                }
            return
        }
        if (nodes.isEmpty()) {
            Log.e(LOG_ID, "sendMessageOn nodes.isEmpty()")
            return
        }

        return sendNameMessageOn(nodes.elementAt(0).id, on)
    }
/*
@Keep
@JvmStatic
public fun sendDatawithInt(ident: Int, data: ByteArray) {
        try {
        messagesender?.nameSendMessage(getNodeName(ident), DATA_PATH, data)
        } catch (th: Throwable) {
        Log.stack(LOG_ID, "sendData $ident", th);
        }
    } */

    @JvmStatic
    public fun initwearos(app: Context) {
        wearableApiUnavailable = false
        wearableApiUnavailableLoggedAt = 0L
        if (!GoogleServices.isPlayServicesAvailable(app)) {
            Log.w(LOG_ID, "initwearos skipped: Google Play Services unavailable")
            messagesender = null
            return
        }
        if(doLog) {Log.i(LOG_ID, "before new MessageSender");}
        try {
            messagesender = MessageSender(app)
        } catch (th: Throwable) {
            messagesender = null
            Log.stack(LOG_ID, "initwearos", th)
        }
//    {if(doLog) {Log.i(LOG_ID,"before sendnetinfo");};};
//    sendnetinfo();
    }

    @JvmStatic
    public fun cansend(): Boolean {
        if (wearableApiUnavailable) {
            return false
        }
        val sender: MessageSender? = messagesender
        if (sender == null) {
            Log.e(LOG_ID, "messagesender==null");
            return false
        }
        val tmp = sender.nodes
        if (tmp == null || tmp.isEmpty()) {
            Log.e(LOG_ID, "no sender.nodes");
            return false
        }
        return true
    }

    private const val netwait = (1000).toLong()

    private fun inargsendnetinfo(id: String) {
        if(doLog) {Log.i(LOG_ID,"sendnetinfo($id)");}
        if (wearableApiUnavailable) {
            Log.d(LOG_ID, "sendnetinfo($id) skipped: Wearable.API unavailable")
            return
        }
        if(!cansend()) {
            Log.i(LOG_ID, "!cansend()")
                return
            }
            if(messagesender==null) {
                Log.e(LOG_ID, "no messagesender")
                return
            }
            val sender:MessageSender = messagesender as MessageSender

            val nodes = sender.nodes
        if(nodes == null || nodes.isEmpty()) {
               Log.e(LOG_ID,"no nodes")
                scope.launch {
                sender.findWearDevicesWithApp()
                }
            return
        }
            val times = sender.nexttimes
            if(times==null) {
                Log.e(LOG_ID,"times=null")
                scope.launch {
                    sender.findWearDevicesWithApp()
                }
                return;
            }
            val it= sender.findnodeid(id)
            if(it<0||it>=times.size) {
                  Log.e(LOG_ID,"nodenum ($it) >= times.size || >0 (${times.size})");
                  scope.launch {
                      sender.findWearDevicesWithApp()
                   }
                return;
                }
            var  othernode=nodes.elementAt(it)
            val nu = System.currentTimeMillis()
            if(times!![it] > nu) {
                Log.i(LOG_ID,"times!![it] > nu) it=$id times!![it]=${times!![it]} nu=$nu ")
                return
                }
            if(sender.localnode==null) {
                Log.d(LOG_ID,"localnode==null")
                    return
                }
            val netinfo: ByteArray?
            // watchHasSensor: 1 = watch owns the sensor, -1 = it does not,
            // 0 = keep whatever was persisted. The watch must never claim the
            // sensor unless it actually holds a live BLE connection: the phone
            // reacts to watchsensor=1 by setting nobluetooth=true and
            // sendstream=false (netinfo.cpp), i.e. it drops its own sensor AND
            // stops feeding the watch. A stale persisted flag (0) therefore
            // strands both devices with no data.
            netinfo = if(isWearable) { Natives.getmynetinfo(sender.localnode, true, WearSensorClaim.netInfoValue(),true,0) } else { Natives.getmynetinfo(id, false, 0, isGalaxy(othernode),0) }
            if(netinfo == null) {
                Log.e(LOG_ID,"netinfo=null")
                return
                }
            if(doLog) {Log.i(LOG_ID, "sender.sendnetinfo($id, netinfo)");};
            sender.sendnetinfo(id, netinfo)
            markNetInfoExchanged()
            times[it] = nu + netwait
        }

        @JvmStatic     public fun sendnetinfo(id: String) {
        scope.launch {    
                inargsendnetinfo(id) 
            }
        }
    private fun insendnetinfo() {
        Log.i(LOG_ID,"sendnetinfo()")
        if (wearableApiUnavailable) {
            Log.d(LOG_ID, "sendnetinfo skipped: Wearable.API unavailable")
            return
        }

            val nu = System.currentTimeMillis()
            if (!cansend()) {
                Log.i(LOG_ID, "!cansend()")
                return
            }
            val sender = messagesender ?: return
            val nodes = sender.nodes
            if (nodes == null || nodes.isEmpty())  {
                scope.launch {
                sender.findWearDevicesWithApp()
                }
                return
            }
            val times = sender.nexttimes
            val nextnetinfo = nu + netwait
            val num = nodes.size
            for(i in 0 until num) {
                val node: Node = nodes.elementAt(i)
                if(times!![i] < nu) {
                    val name = if (isWearable) sender.localnode else node.id
                    if(name==null) {
                        Log.d(LOG_ID,"name=null")
                        continue
                        }
                    val watchSensor = if (isWearable) WearSensorClaim.netInfoValue() else 0
                    val netinfo = Natives.getmynetinfo(name, isWearable, watchSensor, isGalaxy(node),0) ?: continue
                    sender.sendnetinfo(node, netinfo)
                    markNetInfoExchanged()
                    times[i] = nextnetinfo
                } else {
                    Log.i(LOG_ID, "sendnetinfo already done " + node.id)
                  }
              }
            if (isWearable) {
                sender.sendmessage(
                    SENSOR_CLAIM_STATUS_PATH,
                    byteArrayOf(WearSensorClaim.currentStateValue().toByte()),
                )
            }
        }
      @JvmStatic    public fun sendnetinfo() {
        scope.launch {    
                insendnetinfo()
            }
        }
     @JvmStatic     
     public fun isGalaxy(node:Node): Boolean {
         val name=node.getDisplayName()
         val res= name.startsWith("Galaxy Watch") 
         Log.i(LOG_ID,"isGalaxy($name)=$res")
         if(Applic.ALLGALAXY)
                return true;
         return res;
       }

     @JvmStatic     
     public fun reinit() {
        if (wearableApiUnavailable) {
            Log.i(LOG_ID, "reinit skipped: Wearable.API unavailable")
            return
        }
        Log.i(LOG_ID,"reinit")
        Natives.resetnetwork()
        getMessageSender()?.nulltimes()
        sendnetinfo()
        }
    }

}
