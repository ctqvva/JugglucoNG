import json
import re
import subprocess
import unittest

from scripts.aidex.report import TEMPLATE


class IntervalGapTests(unittest.TestCase):
    def bands(self, minutes):
        function = next(line for line in TEMPLATE.splitlines() if line.startswith('function intervalBands('))
        rows = [['', time * 60000, 0, 0, 0, 4, 8] for time in minutes]
        code = ('const x=t=>t/60000,y=v=>v;' + function
                + 'console.log(JSON.stringify(intervalBands(' + json.dumps(rows) + ')));')
        return json.loads(subprocess.check_output(['node', '-e', code], text=True))

    def test_each_continuous_run_has_its_own_closed_lower_and_upper_edges(self):
        bands = self.bands([0, 1, 5, 6, 10])
        self.assertEqual(3, len(bands))
        for band, times in zip(bands, [{0, 1}, {5, 6}, {10}]):
            points = [(float(x), float(y)) for x, y in re.findall(r'[ML]([\d.]+),([\d.]+)', band)]
            self.assertEqual(times, {x for x, _ in points})
            self.assertEqual({4, 8}, {y for _, y in points})
            self.assertEqual(1, band.count('M'))
            self.assertEqual(1, band.count('Z'))

    def test_gap_boundary_matches_the_glucose_lines(self):
        self.assertEqual(1, len(self.bands([0, 1.5])))
        self.assertEqual(2, len(self.bands([0, 1.5001])))
