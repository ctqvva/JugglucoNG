import unittest

import numpy as np

import caresens_oracle as co
import differential as df


class ComparisonTest(unittest.TestCase):
    def inputs(self, n):
        engine = np.zeros(n, df.RESULT)
        vendor = np.zeros(n, co.OUTPUT)
        debug = np.zeros(n, co.DEBUG)
        engine['glucose'] = vendor['result_glucose'] = 100
        engine['exact'] = 1
        return engine, vendor, debug

    def test_error4_exclusion_leaves_other_bits_scored(self):
        engine, vendor, debug = self.inputs(3)
        vendor['errcode'] = [1, 4, 4 | 16 | 32]
        result = df.compare_records(engine, vendor, debug, np.ones(3), np.array([False, True, True]), df.MODELLED)
        self.assertEqual(('modelled errors', 2), result.first)
        self.assertEqual([1, 0, 2, 0, 1, 1, 0, 0], result.mismatches.tolist())
        self.assertEqual([0, 0, 0, 0, 1, 1, 0, 0], result.scored_mismatches.tolist())
        result = df.compare_records(engine, vendor, debug, np.ones(3), np.array([False, True, True]), 1)
        self.assertEqual(('modelled errors', 0), result.first)

    def test_withheld_uses_live_range_and_stops_at_first_fill(self):
        engine, vendor, debug = self.inputs(7)
        vendor['errcode'] = 16
        vendor['errcode'][3] = 0
        engine['exact'] = 0
        engine['glucose'][1:3] = [35, 505]
        engine['error'][4] = 1
        result = df.compare_records(engine, vendor, debug, np.array([1, 1, 1, 0, 1, 2, 1]), np.zeros(7, bool), 0)
        self.assertIsNone(result.first)
        self.assertEqual((2, 1), (result.withheld, result.withheld_error))
        self.assertEqual(1, result.withheld_error16)
        self.assertEqual((5, 2), (result.compared, result.after_fill))
        self.assertEqual(4, result.mismatches[df.BITS.index(16)])

    def test_finite_to_nan_is_a_numeric_mismatch(self):
        engine, vendor, debug = self.inputs(2)
        engine['glucose'] = np.nan
        vendor['result_glucose'][0] = np.nan
        result = df.compare_records(engine, vendor, debug, np.ones(2), np.zeros(2, bool), 0)
        self.assertEqual(('glucose', 1), result.first)


if __name__ == '__main__':
    unittest.main()
