#!/usr/bin/env python3
"""Replay the production Kotlin AiDex model using a PRIVATE Everything export.

Reads selected glucose rows, enabled calibration references and temperature only.
Never copies the export, shared preference dump, keys or account settings.
Requires kotlinc and java; outputs remain local and must not be published as fixtures.
"""
import argparse
import bisect
import csv
import json
import math
import os
from pathlib import Path
import statistics
import subprocess

MGDL_PER_MMOL = 18.0182
REPO = Path(__file__).resolve().parents[2]


def prepare(data, sensors, resets, target, reference_unit):
    readings = data["history"]["readings"]
    calibration_settings = data["calibrations"]
    calibrations = calibration_settings["calibrations"]
    auto_enabled = {entry["sensorId"]: entry.get("autoEnabled", True)
                    for entry in calibration_settings.get("sensorEnablement", [])}
    temperature_values = data["settings"]["sharedPreferences"].get("AiDexTemperaturePrefs", {}).get("values", {})
    metadata = {}
    with target.open("w") as output:
        output.write("sensor\ttimestampMs\tsessionStartMs\trawProxyMgDl\tfactoryMgDl\ttemperatureC\treferences\n")
        for sensor in sensors:
            rows = sorted((r for r in readings if r["sensorSerial"] == sensor), key=lambda r: r["timestamp"])
            if not rows:
                raise ValueError(f"No readings for {sensor}")
            # Export lacks activation boundaries. First recorded minute is only
            # an age proxy. Resets must be supplied explicitly, never guessed.
            sessions = sorted([rows[0]["timestamp"], *resets.get(sensor, [])])
            timestamps = [r["timestamp"] for r in rows]
            references = {}
            unmatched = 0
            for c in sorted(calibrations, key=lambda c: c["timestamp"]):
                if (c["sensorId"] != sensor or not c["isEnabled"] or
                        not auto_enabled.get(sensor, calibration_settings.get("autoEnabled", True))):
                    continue
                # Match the driver's Auto reference set: manual Raw calibrations
                # stay in Raw; journal fingersticks are eligible in both lanes.
                if c.get("isRawMode", False) and c.get("journalEntryId") is None:
                    continue
                # CalibrationEntity stores display units; history stores mg/dL.
                # The export does not label calibration units. Require the
                # caller to establish them rather than infer them from magnitude.
                value = c["userValue"] * (MGDL_PER_MMOL if reference_unit == "mmol/L" else 1)
                if not math.isfinite(value) or not MGDL_PER_MMOL <= value <= 35 * MGDL_PER_MMOL:
                    continue
                # References are released at the first sample AT OR AFTER the
                # reference, within one minute. No future reference leakage.
                pos = bisect.bisect_left(timestamps, c["timestamp"])
                if pos >= len(rows) or timestamps[pos] - c["timestamp"] > 60000:
                    unmatched += 1
                    continue
                references.setdefault(timestamps[pos], []).append((c["timestamp"], value))
            temperatures = {}
            encoded = temperature_values.get(f"aidex_temp_history_{sensor}", {}).get("value", "")
            for pair in encoded.split(";"):
                if pair:
                    time, value = pair.split(",")
                    temperatures[int(time)] = int(value) / 10
            for row in rows:
                time = row["timestamp"]
                start = sessions[bisect.bisect_right(sessions, time) - 1]
                raw = row["rawValueMgDl"]
                factory = row["valueMgDl"]
                output.write("\t".join(map(str, [sensor, time, start, raw if raw is not None else "NaN",
                    factory if factory is not None else "NaN", temperatures.get(time, "NaN"),
                    ";".join(f"{t}:{v}" for t, v in references.get(time, []))])) + "\n")
            metadata[sensor] = {"rows": len(rows), "reference_minutes": len(references),
                "unmatched_references": unmatched, "explicit_resets": resets.get(sensor, []),
                "reference_unit_supplied_by_caller": reference_unit,
                "age_origin": "first exported reading, not verified activation"}
    return metadata


def summarize(path, metadata):
    groups = {}
    with path.open() as input_file:
        for row in csv.DictReader(input_file):
            key = row["sensor"], row["model"]
            group = groups.setdefault(key, {"outputs": 0, "scores": [], "scales": []})
            glucose = float(row["glucoseMmol"])
            if math.isfinite(glucose):
                group["outputs"] += 1
                group["scales"].append(float(row["scale"]))
            reference = float(row["referenceMmol"])
            prediction = float(row["preReferenceMmol"])
            if math.isfinite(reference) and math.isfinite(prediction):
                group["scores"].append((prediction - reference, float(row["factoryMmol"]) - reference,
                    float(row["rawProxyMmol"]) - reference))
    result = {"input": metadata, "evaluation": "pre-reference predictions; saved user references are not verified meter/laboratory truth", "models": {}}
    for (sensor, model), group in groups.items():
        scores = group.pop("scores")
        scales = group.pop("scales")
        group["scored_reference_minutes"] = len(scores)
        group["scale_range"] = [min(scales), max(scales)] if scales else None
        group["mae_mmol"] = {name: statistics.mean(abs(s[i]) for s in scores) for i, name in enumerate(["adaptive", "factory_same_minutes", "raw_same_minutes"])} if scores else None
        group["median_absolute_error_mmol"] = statistics.median(abs(s[0]) for s in scores) if scores else None
        result["models"].setdefault(sensor, {})[model] = group
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("--sensor", action="append", required=True)
    parser.add_argument("--reset", action="append", default=[], metavar="SENSOR=EPOCH_MS")
    parser.add_argument("--reference-unit", choices=["mmol/L", "mg/dL"], required=True)
    parser.add_argument("--output", type=Path, default=REPO / ".local" / "aidex-evaluation")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    resets = {}
    for reset in args.reset:
        sensor, epoch = reset.rsplit("=", 1)
        resets.setdefault(sensor, []).append(int(epoch))
    data = json.loads(args.export.read_text())
    metadata = prepare(data, args.sensor, resets, args.output / "input.tsv", args.reference_unit)
    sources = sorted((REPO / "Common/src/main/java/tk/glucodata/drivers/aidex/adaptive2").glob("*.kt"))
    jar = args.output / "replay.jar"
    subprocess.run(["kotlinc", *map(str, sources), str(REPO / "scripts/aidex/AiDexReplay.kt"), "-jvm-target", "17", "-include-runtime", "-d", str(jar)], check=True, env=os.environ)
    subprocess.run(["java", "-cp", str(jar), "tk.glucodata.drivers.aidex.adaptive2.AiDexReplay", str(args.output / "input.tsv"), str(args.output / "readings.csv")], check=True, env=os.environ)
    report = summarize(args.output / "readings.csv", metadata)
    (args.output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
