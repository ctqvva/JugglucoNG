# iCan activation capture (for #679 / #680)

One capture of a **fresh sensor activated by the vendor app** answers three open questions at once:

1. **C2 framing** (PR #680): the exact `F2` chunk run and sensor acknowledgements.
   The preserved regular CE capture has three F2 writes and no F7. The SDK's
   separate init/F7 branch must not be assumed to be part of this activation path.
2. **C1 / Track B**: the SDK's own `parameterCode:` line plus the wire shape of a real fetch, which
   is what the missing `sign`/`appId` reproduction needs.
3. **C3 semantics**: whether `planCalib`'s `newBg` is the fingerstick or the record's glucose — the
   call site is behind the ijiami dispatcher, so only a capture settles it.

## What you need

* a **fresh** iCan sensor that is going to be activated in the vendor app anyway;
* the vendor app on the phone (`com.sinocare.ican.health.ru` on the current test phone);
* `adb`; root (`su`) makes the snoop pull trivial, otherwise take a bug report afterwards;
* Developer options → **Enable Bluetooth HCI snoop log**.

## Run

```bash
./capture_ican_activation.sh ru.luch.testcgm ./ican-activation
```

The script starts logcat **before** the app is opened, waits for you to press Enter after the
activation and ~15 minutes of streaming, then pulls the HCI log and prints a redacted summary.

**Where the HCI log lives depends on the phone.** Stock Android writes
`/data/misc/bluetooth/logs/btsnoop_hci.log`; this MIUI/MediaTek phone writes
`/data/misc/bluetooth/logs/BT_HCI_YYYY_MMDD_HHMMSS.cfa.curf` instead. Both are pulled by the script
(newest file in that directory) and both are readable by `tshark` — verified on the `.curf` here
(341k frames, 50k ATT).

Manual equivalent:

```bash
adb shell su -c "settings put secure bluetooth_hci_log 1"   # or the Developer options toggle
adb logcat -c && adb logcat -v threadtime > logcat.txt
#   ... activate the sensor in the vendor app, let it stream ~15 min ...
adb shell su -c "cat /data/misc/bluetooth/logs/$(adb shell su -c 'ls -t /data/misc/bluetooth/logs | head -1' | tr -d '\r')" > hci.log
python3 ican_capture_summary.py --logcat logcat.txt --snoop hci.log
```

## What the summary shows

* `path correctCode …` vs `path initParameter …` — which provisioning write the vendor SDK ran
  (they share the native chunker but pass a different 4th flag);
* `F6 values seen` — `0xFFFF` = no `correctCode`, anything else (500/510/530/570/600) = provisioned;
* the recovered 25-float table from `full code:` / `parameterCode:`;
* on the `initParameter` path, the per-chunk hex the SDK logs;
* from the snoop: every `F3`/`F4`/`F6`/`F2`/`F7` write on the vendor op handle, the **`F2` chunk
  runs** with lengths and bytes (the framing ground truth), and the `F6` read-backs.

Serials, user ids and MAC addresses are masked by default; `--no-redact` is for a private copy.

## What to attach to #679

`summary.txt` (it is redacted), and `btsnoop_hci.log` if you are comfortable sharing it. Nothing is
uploaded automatically.

## Screenshot-free acceptance baseline

While the same sensor is still in the vendor app, note what it displays for a few minutes. After the
capture, connect with JugglucoNG and read `F6`, `2AAA` and the first live values — that pair is the
"values match" half of the acceptance test in #679.
