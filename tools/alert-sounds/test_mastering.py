"""Regression: loudness adjustment must preserve the waveform, including transients."""
import unittest
import numpy as np
from mastering import master, REFERENCES, RATE

class MasteringTests(unittest.TestCase):
    def test_high_crest_recording_gets_linear_attenuation_not_saturation(self):
        n = REFERENCES['low']['frames']
        x = .03*np.sin(2*np.pi*400*np.arange(n)/RATE)
        x[4800], x[4801] = 1, -1
        centered = x-x.mean()
        self.assert_linear_master(centered, x.copy(), 0.0, -3.0)
        self.assert_linear_master(centered, x.copy(), 4.5, -1.5)
        self.assert_linear_master(centered, x.copy(), 0.0, -1.0, peak_normalize=True)

    def assert_linear_master(self, centered, x, boost_db, peak_db, peak_normalize=False):
        pcm = master(x, 'low', boost_db=boost_db, peak_db=peak_db,
                     peak_normalize=peak_normalize).astype(float)/32767
        # The loud transient determines one gain for the entire interior. Quiet
        # resonances must keep that same gain rather than be pushed into a limiter.
        gain = pcm[4800]/centered[4800]
        np.testing.assert_allclose(pcm[240:-240], centered[240:-240]*gain, atol=3.1e-5, rtol=0)
        self.assertLessEqual(np.max(np.abs(pcm)), 10**(peak_db/20)+1/32767)
        self.assertEqual(pcm[0], 0)
        self.assertEqual(pcm[-1], 0)

if __name__ == '__main__':
    unittest.main()
