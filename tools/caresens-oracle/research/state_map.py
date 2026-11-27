"""Prints the byte offset, type and length of every field of the vendor's
state block (air1_opcal4_arguments_t, unpacked, 117 312 bytes) and debug
record (air1_opcal4_debug_t, packed), parsed from Common/src/main/cpp/air/air.hpp.
Needs no reference library.  python3 state_map.py > state-offsets.txt"""
import layout
def dump(name, dt):
    print('## %s (%d bytes)' % (name, dt.itemsize))
    print('%-8s %-6s %-6s %s' % ('offset', 'type', 'count', 'field'))
    for f in dt.names:
        sub, off = dt.fields[f][:2]
        base = sub.base if sub.shape else sub
        n = 1
        for d in sub.shape: n *= d
        print('%-8d %-6s %-6d %s' % (off, base.str if base.names is None else 'struct', n, f))
    print()
dump('air1_opcal4_arguments_t (vendor state)', layout.struct('air1_opcal4_arguments_t', False))
dump('air1_opcal4_debug_t (debug record)', layout.DBG)
dump('air1_opcal4_output_t (output record)', layout.OUT)
