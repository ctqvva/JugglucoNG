package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class XdripFollowerSendTests {
    @Test
    public void createIntent_sendsMgdlAndTimestampInSgvExtra() throws Exception {
        long timeMillis = 1_760_000_000_123L;
        Intent intent = XdripFollowerSend.createIntent(101, timeMillis, "Flat");

        assertEquals("info.nightscout.client.NEW_SGV", intent.getAction());
        assertEquals("com.eveningoutpost.dexdrip", intent.getPackage());
        assertTrue((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0);

        String extra = intent.getStringExtra("sgv");
        assertNotNull(extra);
        JSONObject sgv = new JSONObject(extra);
        assertEquals(101, sgv.getInt("mgdl"));
        assertEquals(timeMillis, sgv.getLong("mills"));
        assertEquals("Flat", sgv.getString("direction"));
        assertFalse(sgv.has("filtered"));
        assertFalse(sgv.has("unfiltered"));
    }

    @Test
    public void createIntent_usesUnknownDirectionWhenTrendIsMissing() throws Exception {
        Intent intent = XdripFollowerSend.createIntent(126, 1_760_000_300_123L, "");

        JSONObject sgv = new JSONObject(intent.getStringExtra("sgv"));
        assertEquals("NOT_COMPUTABLE", sgv.getString("direction"));
    }
}
