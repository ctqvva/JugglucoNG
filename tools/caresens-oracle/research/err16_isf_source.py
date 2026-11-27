"""Verify which current history feeds error 16 during low-signal recovery."""
from err16lib import W
from err16_replay import sweep_case
from err16_state_probe import PREFIX, Replay
from sc import REF, co, np, scenarios


SOURCE = 'err128_CGM_c_noise_revised_value'


def main():
    if REF.sha256 not in co.KNOWN_REFERENCE:
        raise SystemExit(f'unexpected reference SHA-256: {REF.sha256}')
    spec, _ = sweep_case(101, 61, False)
    device = co.default_device()
    for field in ('slope100', 'vref', 'eapp'):
        device[field] = spec.get(field, scenarios.DEFAULTS[field])
    inputs = co.records(*scenarios.generate(**spec))
    count, output, debug, _, states = REF.run(device, inputs, snapshots=True)
    assert not (count > 1).any()
    debug, output = debug[:, 0], output[:, 0]
    observed = debug[PREFIX + 'smooth']
    present = (output['data_type'] != 2) & np.isfinite(observed)

    def filtered(field):
        return np.round(states[field][:, -12:] @ W[::-1] * 100 / device['slope100'][0])

    raw_errors = int((present & (filtered('curr_avg_arr') != observed)).sum())
    revised_errors = int((present & (filtered(SOURCE) != observed)).sum())
    print(f'{present.sum()} finite ISF samples; raw current mismatches {raw_errors}; revised current mismatches {revised_errors}')
    assert raw_errors == 85 and revised_errors == 0
    assert not (output['errcode'] & 128).any()

    # Input 104 has returned to data type 0 while the stored current is still
    # revised. Changing only the preceding stored values moves error-16 ISF.
    before = 103
    replay = Replay(device, states[before - 1:before], inputs[before:before + 1])
    baseline, baseline_debug, _ = replay.step()
    altered, altered_debug, _ = replay.step([(SOURCE, slice(-11, None), 0.0)])
    assert baseline['data_type'] == altered['data_type'] == 0
    assert baseline_debug['tran_inA_5min'] == altered_debug['tran_inA_5min']
    assert baseline_debug[PREFIX + 'smooth'] == 82 and altered_debug[PREFIX + 'smooth'] == 16
    print(f'Input {before + 1}, seq {inputs[before]["seq_number"]}: altering only the preceding revised current '
          f'changes ISF {baseline_debug[PREFIX + "smooth"]:.0f} to {altered_debug[PREFIX + "smooth"]:.0f}')


if __name__ == '__main__':
    main()
