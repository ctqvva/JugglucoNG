"""Keep louder Ember processing linear and distinct from unchanged Timber."""
import unittest
import numpy as np
from render_acoustic import RATE, CUES, ember_note, ember_phone_body


class EmberProcessingTests(unittest.TestCase):
    def test_envelopes_and_eq_are_linear_not_signal_dependent(self):
        t = np.arange(RATE) / RATE
        a = .2 * np.sin(2*np.pi*130*t)
        b = .1 * np.sin(2*np.pi*1320*t)
        for cue in CUES:
            for operation in (ember_note, ember_phone_body):
                np.testing.assert_allclose(operation(a+b, cue),
                                           operation(a, cue)+operation(b, cue), atol=1e-12)
                np.testing.assert_allclose(operation(a*.1, cue),
                                           operation(a, cue)*.1, atol=1e-12)

    def test_low_eq_frees_bass_headroom_and_retains_upper_partials(self):
        t = np.arange(RATE) / RATE
        gains = []
        for frequency in (130, 1320):
            source = np.sin(2*np.pi*frequency*t)
            filtered = ember_phone_body(source, 'low')
            gains.append(np.sqrt(np.mean(filtered[192:-192]**2) /
                                 np.mean(source[192:-192]**2)))
        self.assertLess(gains[0], .05)
        self.assertGreater(gains[1], .8)
        self.assertLess(gains[1], 1)


if __name__ == '__main__':
    unittest.main()
