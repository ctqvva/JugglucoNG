import csv
from pathlib import Path
import tempfile
import unittest

from scripts.aidex.replay import MGDL_PER_MMOL, prepare, summarize


class ReplayContractTests(unittest.TestCase):
    def fixture(self):
        return {
            "history": {"readings": [
                {"sensorSerial": "X-test", "timestamp": 1_000_000, "valueMgDl": 100, "rawValueMgDl": 50},
                {"sensorSerial": "X-test", "timestamp": 1_060_000, "valueMgDl": 110, "rawValueMgDl": 55},
            ]},
            "calibrations": {"calibrations": [
                {"sensorId": "X-test", "timestamp": 1_030_000, "userValue": 6, "isEnabled": True},
                {"sensorId": "X-other", "timestamp": 1_030_000, "userValue": 20, "isEnabled": True},
                {"sensorId": "X-test", "timestamp": 1_060_000, "userValue": 20, "isEnabled": False},
            ]},
            "settings": {"sharedPreferences": {"SecretPrefs": {"values": {"token": "never-copy-this"}}}},
        }

    def test_future_reference_is_released_only_after_its_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "input.tsv"
            prepare(self.fixture(), ["X-test"], {}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertEqual("", rows[0]["references"])
            time, value = rows[1]["references"].split(":")
            self.assertEqual("1030000", time)
            self.assertAlmostEqual(6 * MGDL_PER_MMOL, float(value))
            self.assertNotIn("never-copy-this", target.read_text())

    def test_calibration_display_units_are_explicit(self):
        data = self.fixture()
        data["calibrations"]["calibrations"][0]["userValue"] = 120
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "input.tsv"
            prepare(data, ["X-test"], {}, target, "mg/dL")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertEqual("1030000:120", rows[1]["references"])

    def test_manual_raw_references_are_not_hidden_auto_references(self):
        data = self.fixture()
        data["calibrations"]["calibrations"][0]["isRawMode"] = True
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "input.tsv"
            prepare(data, ["X-test"], {}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertTrue(all(not row["references"] for row in rows))
            data["calibrations"]["calibrations"][0]["journalEntryId"] = 123
            prepare(data, ["X-test"], {}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertTrue(rows[1]["references"])

    def test_auto_enablement_is_respected_without_using_raw_enablement(self):
        data = self.fixture()
        data["calibrations"]["sensorEnablement"] = [
            {"sensorId": "X-test", "autoEnabled": False, "rawEnabled": True}]
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "input.tsv"
            prepare(data, ["X-test"], {}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertTrue(all(not row["references"] for row in rows))
            data["calibrations"]["sensorEnablement"][0].update(autoEnabled=True, rawEnabled=False)
            prepare(data, ["X-test"], {}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertTrue(rows[1]["references"])

    def test_explicit_reset_identity_is_not_guessed_from_raw_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "input.tsv"
            prepare(self.fixture(), ["X-test"], {"X-test": [1_060_000]}, target, "mmol/L")
            rows = list(csv.DictReader(target.read_text().splitlines(), delimiter="\t"))
            self.assertEqual("1000000", rows[0]["sessionStartMs"])
            self.assertEqual("1060000", rows[1]["sessionStartMs"])

    def test_reference_scoring_uses_prediction_not_post_reference_fit(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "readings.csv"
            target.write_text("sensor,model,glucoseMmol,scale,referenceMmol,preReferenceMmol,factoryMmol,rawProxyMmol\nX-test,reference_only,6.0,1,6.0,4.0,5.0,3.0\n")
            model = summarize(target, {})["models"]["X-test"]["reference_only"]
            self.assertEqual(2.0, model["mae_mmol"]["adaptive"])
            self.assertEqual(1.0, model["mae_mmol"]["factory_same_minutes"])


if __name__ == "__main__":
    unittest.main()
