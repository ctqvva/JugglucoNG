# CareSens Air error 16 evidence

## Isolating the condition histories

`err16_state_probe.py` regenerates a named run from the existing seed-11
decay corpus, runs its prefix with an empty calibration list, and replays a
single input against copies of the preceding state. It varies only named
fields from the public state layout. This isolates the observed conditions
from the output latch, which may already be set.

```sh
cd tools/caresens-oracle/research
python3 err16_state_probe.py 98 1728
python3 err16_state_probe.py 123 2018
python3 err16_state_probe.py 123 1728
```

The unmodified states fire conditions 0, 1 and 2 respectively, in
zero-based condition order. In particular, condition 1 can fire at sequence
2018 with an empty calibration list. Replacing the mean-fit slope history
or the fit's R² history clears it. Condition 2 responds to the steady-rate
history and the minimum and mean trend histories. Condition 0 has more
than one path. For example, changing the minimum-trend regression clears
condition 0 in run 98 at sequence 1728, while it remains set in run 23 at
sequence 3732.

The history dependence explains why fitting conditions to only the last
reported scalar per statistic missed transitions.
