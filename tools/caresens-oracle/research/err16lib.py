import random

import numpy as np
W=np.array([0.19203786935092992,0.16522460111444,0.11844988518211,0.0716100935714,0.03473077453465,0.01186996852659,
            0.00419148479069,0.01186996852659,0.03473077453465,0.0716100935714,0.11844988518211,0.16522460111444])
def kern(x):  # x: newest first, 12 values
    return float(np.dot(W,x))


def decay_specs():
    """The seed-11 corpus used by e226.py and e228.py, in its original order."""
    rng = random.Random(11)
    for _ in range(160):
        lo = rng.choice([50, 60, 70, 80, 100])
        spec = dict(seed=rng.randrange(1, 10**9), readings=4320, start=1, lo=lo,
                    hi=lo + rng.choice([60, 120, 200, 300]), knot_min=rng.choice([10, 30, 60]),
                    knot_max=rng.choice([60, 200, 400]), noise=rng.choice([5, 12, 25]),
                    decay=rng.choice([1, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4]),
                    slope100=rng.uniform(3, 7), eapp=rng.choice([0.05, 0.1]), temp_mode=rng.choice(['const', 'vary']),
                    temp_lo=3300, temp_hi=3600)
        spec['knot_max'] = max(spec['knot_max'], 2 * spec['knot_min'])
        yield spec
