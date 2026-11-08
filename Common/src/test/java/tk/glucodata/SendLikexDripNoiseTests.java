package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SendLikexDripNoiseTests {
    private static final String EXTRA_NOISE = "com.eveningoutpost.dexdrip.Extras.Noise";
    private static final String EXTRA_NS_NOISE_LEVEL = "com.eveningoutpost.dexdrip.Extras.NsNoiseLevel";
    private static final String EXTRA_NOISE_SIGMA = "tk.glucodata.Extras.NoiseSigmaMgdl";

    @Test
    public void highVarianceCarriesBothScales() {
        Bundle extras = new Bundle();
        SendLikexDrip.putNoiseExtras(extras, new NoiseEstimate(73f));

        assertEquals(73.0, extras.getDouble(EXTRA_NOISE), 0.0);
        assertEquals("3", extras.getString(EXTRA_NS_NOISE_LEVEL));
        assertEquals(Math.sqrt(73.0), extras.getDouble(EXTRA_NOISE_SIGMA), 0.0001);
    }

    @Test
    public void cleanTraceSendsLevelZero() {
        Bundle extras = new Bundle();
        SendLikexDrip.putNoiseExtras(extras, new NoiseEstimate(0f));

        assertEquals(0.0, extras.getDouble(EXTRA_NOISE), 0.0);
        assertEquals("0", extras.getString(EXTRA_NS_NOISE_LEVEL));
        assertEquals(0.0, extras.getDouble(EXTRA_NOISE_SIGMA), 0.0);
    }

    @Test
    public void absentEstimateAddsNothing() {
        Bundle extras = new Bundle();
        SendLikexDrip.putNoiseExtras(extras, null);

        assertTrue(extras.isEmpty());
    }
}
