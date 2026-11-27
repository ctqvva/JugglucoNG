"""Replay an empty-calibration error-16 transition from a captured state."""
import argparse

from err16lib import decay_specs
from sc import REF, co, np, scenarios


PREFIX = 'err16_CGM_ISF_'
EARLY_PROFILE = 'diabetes_before_7days_flag'


class Replay:
    def __init__(self, device, state, record):
        self.device = device
        self.state = state
        self.input = record

    @classmethod
    def from_decay(cls, run, sequence):
        spec = list(decay_specs())[run]
        device = co.default_device()
        for name in ('slope100', 'vref', 'eapp'):
            device[name] = spec.get(name, scenarios.DEFAULTS[name])
        inputs = co.records(*scenarios.generate(**spec))[:sequence]
        if len(inputs) == 0 or inputs[-1]['seq_number'] != sequence:
            raise ValueError(f'Sequence {sequence} is outside decay run {run}')
        _, _, _, state, _ = REF.run(device, inputs[:-1])
        return cls(device, state, inputs[-1:])

    def step(self, changes=()):
        state = self.state.copy()
        for field, slot, value in changes:
            state[field][0, slot] = value
        count, outputs, debug, _, _ = REF.run(self.device, self.input, state=state)
        assert count[0] == 1
        return outputs[0, 0], debug[0, 0], state[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=int)
    parser.add_argument('sequence', type=int)
    parser.add_argument('--profiles', action='store_true', help='change only the pre-record diabetes flag')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    replay = Replay.from_decay(args.run, args.sequence)
    output, debug, after = replay.step()
    original = debug['err16_condi']
    print(f'run {args.run} seq {args.sequence}: {original.tolist()}, bit 16 = {bool(output["errcode"] & 16)}')
    if args.profiles:
        print(f'{EARLY_PROFILE}: {replay.state[EARLY_PROFILE][0]} -> {after[EARLY_PROFILE]}')
        for profile in (0, 1, 2, 3, 4, 5, 6, 100):
            _, result, _ = replay.step([(EARLY_PROFILE, Ellipsis, profile)])
            print(profile, result['err16_condi'][:3].tolist())
        return
    for name in co.STATE.names:
        if not name.startswith(PREFIX):
            continue
        typ = co.STATE[name].base
        if typ.kind != 'f':
            continue
        for value in (0.0, 80.0, 300.0, np.nan):
            o, g, _ = replay.step([(name, Ellipsis, value)])
            observed = g['err16_condi']
            if not np.array_equal(original, observed):
                print(f'{name.removeprefix(PREFIX):30} = {value:6g} -> {observed[:3].tolist()}')


if __name__ == '__main__':
    main()
