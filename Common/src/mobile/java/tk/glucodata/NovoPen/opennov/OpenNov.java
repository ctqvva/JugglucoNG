/*
All files in this directory tree are a modification for Juggluco of files of xDrip:
https://github.com/NightscoutFoundation/xDrip
*/
package tk.glucodata.NovoPen.opennov;

import static tk.glucodata.Log.doLog;
import static tk.glucodata.NovoPen.opennov.BaseMessage.d;
import static tk.glucodata.NovoPen.opennov.BaseMessage.log;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import tk.glucodata.NovoPen.opennov.HexDump;
import tk.glucodata.NovoPen.opennov.buffer.MyByteBuffer;
import tk.glucodata.NovoPen.opennov.ll.PHDllHelper;
import tk.glucodata.NovoPen.opennov.ll.T4Transceiver;

import java.io.IOException;

import tk.glucodata.Log;


/**
 * JamOrHam
 * OpenNov implementation
 */


//@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class OpenNov extends MyByteBuffer {

    public static final String TAG = "OpenNov";
    private static final int MAX_ERRORS = 3;

    private T4Transceiver ts;
    private PHDllHelper ph;

    /**
     * True once the tag answered the NDEF application select. Every ISO-DEP card in range
     * reaches this class -- bank cards, transit passes, door badges -- and only a tag that
     * got this far is worth reporting a read failure about.
     */
    private boolean spokeProtocol = false;

    public boolean spokeProtocol() {
        return spokeProtocol;
    }

    public OpContext processTag(final Tag tag) {
        final var machine = new Machine();
        try {
            var isoDep = IsoDep.get(tag);
            this.ts = new T4Transceiver(isoDep);
            this.ph = new PHDllHelper(this.ts);

            isoDep.connect();
            isoDep.setTimeout(1000);

            if (ts.doNeededSelection()) {
                {if(doLog) {Log.d(TAG, "Selection okay");};};
                spokeProtocol = true;

                int errors = 0;
                int transactions = 0;
                FSA fsa = FSA.read();
                while (fsa.doRead()
                        && errors < MAX_ERRORS
                        && transactions < 200) {
                    transactions++;
                    var res = ts.readFromLinkLayer();
                    if (d) log("link layer read: " + HexDump.dumpHexString(res));
                    var payload = ph.extractInnerPacket(res, true);
                    if (payload != null) {
                        fsa = machine.processPayload(payload);
                        {if(doLog) {Log.d(TAG, "Got fsa action: " + fsa.action);};};
                        switch (fsa.action) {

                            case WRITE_READ:
                                ph.writeInnerPacket(fsa.payload);
                                break;

                            case DONE:
                                Log.d(TAG, "All done"); //TODO: why no isoDep.close()?
                                isoDep.close();

                                return machine.context;
                        }
                    } else {
                        errors++;
                        {if(doLog) {Log.d(TAG, "Read cycle got null errors @ " + errors);};};
                    } // if payload
                } // end while

                if (fsa.doRead()) {
                    {if(doLog) {Log.d(TAG, "Overall failure to read");};};
                    isoDep.close();
                    return whatWasRead(machine);
                }

                isoDep.close();
                return machine.context;
            }
            isoDep.close();
        } catch (IOException e) {
            {if(doLog) {Log.d(TAG, "Could not connect: " + e);};};
        } catch (Exception e) {
            Log.stack(TAG, "Got crash in handler: " , e);
        }
        return whatWasRead(machine);
    }

    /**
     * A pen holding months of doses takes many seconds of steady contact to read out, and a
     * hand moves. Losing the tag two thirds of the way through used to discard everything
     * that had already arrived and report a failed read; the segments delivered up to that
     * point are perfectly good, and the pen sends its newest doses first, so a partial read
     * is the interesting part of the log. Returns null only when nothing usable arrived.
     */
    private OpContext whatWasRead(final Machine machine) {
        var context = machine.context;
        if (context.specification != null
                && context.specification.getSerial() != null
                && !context.doses.isEmpty()) {
            Log.i(TAG, "Partial read kept: " + context.doses.size() + " segment(s)");
            return context;
        }
        return null;
    }
}
