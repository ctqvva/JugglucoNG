# Activation capture: heap snapshot at the `F2` write

Goal: catch the **plaintext calibration table** in the vendor app's heap at the moment it is
chunked into `F2 10 <16 B>` writes, and pair it with the ciphertext from the HCI capture.
That pair is what #680 still needs (the payload transform is unknown).

## What the 2026-10-20 capture established

Timeline of a CE 01.17 activation (sensor link, ACL `0x0201`):

```
t+0      F3  -> 06 F3 01 <4 B>            (bind starts)
t+0.6    F4 10 <16 B> -> 1C F4 01
t+0.7    F6  -> 1C F6 01 FF FF
t+28     1A  -> 1C 1A 01, then Session Start Time (0x001D)
t+31..32 F2 10 <16 B> x3, each acked 1C F2 01     <-- table is written here
t+90     next bind: F6 -> 1C F6 01 4E 02 (590)
```

So the plaintext lives in memory roughly between the server response and the last `F2` ack —
a window of a few seconds around `t+31`. Snapshots every ~10 s will cover it.

## Prep (day of the new sensor)

1. Phone rooted, Magisk; the vendor app **must stay in the DenyList** (it does not start otherwise).
2. Bluetooth HCI snoop log on (Developer options), so the `F2` ciphertext is captured too.
3. Push the dumper:
   `adb push tools/ican-capture/dump_heap.sh /data/local/tmp/dump_heap.sh`
4. Log in to the CE app beforehand (account, no pending dialogs).
5. Start the watcher **before** the app is opened:

```bash
cd tools/ican-capture
./watch_activation.sh com.sinocare.ican.health.ce ./activation-$(date +%Y%m%d) 10 8
```

It snapshots the app's heap every 10 s (up to 8 snapshots), keeps the tarred snapshots on the
phone and pulls them at the end. Each snapshot is heap-only (`dalvik-*` + `libc_malloc` + anon) —
**measured 196 MB in ~1.6 s** on the CE app, so 8 snapshots ≈ 1.6 GB on the phone; delete
`/data/local/tmp/actsnaps` afterwards. With 10 s spacing the `F2` moment (~t+31 s) is covered by the
snapshots at 20/30/40 s; a 5 s interval is fine too if the phone has the space.

## Run

1. Start the watcher (above).
2. In the app: add the sensor and activate it; keep the app in the foreground.
3. Wait until the warm-up screen appears (i.e. past `F2`), then let the watcher run a couple more
   snapshots and stop it (Ctrl-C).
4. Pull the HCI file as usual:

```bash
adb exec-out su -c "cat /data/misc/bluetooth/logs/BT_HCI_*.cfa.curf" > activation.curf
tshark -r activation.curf -F pcap -w activation.pcap
python3 parse_att.py activation.pcap <from> <to> > att.txt   # find the F2 rows
```

## Analyse

```bash
python3 scan_heap_for_table.py ./activation-YYYYMMDD <f2_hex_1> <f2_hex_2> <f2_hex_3>
```

`scan_heap_for_table.py` reports:

* ASCII markers (`correctCode`, `parameterCode`, `full code`, `"x1"`, `cgmVal`);
* float32 sequences beginning with the values shared by the known table (`32.0, 25.0`) and prints
  the surrounding 25 floats — a candidate table;
* any of the given `F2` ciphertext blobs (locates the chunking buffer, which usually sits next to
  the plaintext in the same allocation).

If a float table shows up next to a `F2` ciphertext, we have the pair: plaintext → transform →
16-byte chunks.

## Cleanup / privacy

* Delete the snapshots from the phone afterwards (`rm -rf /data/local/tmp/actsnaps`).
* Heap snapshots contain the user profile, glucose values and session tokens — treat like the raw
  HCI captures: keep private, redact before sharing.
