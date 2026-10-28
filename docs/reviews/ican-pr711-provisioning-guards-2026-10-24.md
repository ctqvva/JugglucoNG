# PR #711 provisioning guard follow-up

The full 13-character factory serial remains required for the proven regular
CE/RU list builder. Existing 8/9/12-character connection identities remain valid
for discovery and streaming; they do not supply enough evidence to invent the
factory calibration code. A fresh sensor with an unsupported identity now takes
an identity-error path, separately from a missing genuine table. Its status
explicitly requires the full 13-character serial number from the iCan box. It does not
receive a misleading cloud-unavailable notification for that identity error.

F6 with any installed marker bypasses lookup, parameter writes and pending
notifications, irrespective of serial length or marker-to-x13 agreement. No
universal identity inference is made from a marker mismatch. This preserves
officially provisioned sensors. Only an authenticated F6 `FFFF` sensor with the
full factory identity can initiate automatic lookup and provisioning. Pending
retries are canceled on a positive F6; canceled workers cannot commit cache rows
or revive their retries.

The known server fallback is rejected before caching, when reading old cache
entries, when loading manual files, and by the write builder. Matching compares
all fifteen transmitted coefficients except the client-derived x13; differences
in untransmitted x16..x25 cannot make a generic wire table eligible. Such a row is
rejected even if it might coincidentally describe a real sensor: HTTP 200 plus
these fallback values cannot establish per-sensor provenance.

The preserved PCAP `ican-hci-2026-10-20/02-full-0812-1316.pcap` was parsed again.
Its vendor-control stream contains 115 F3, 114 F4, 113 F6, three start commands,
three F2 writes, and **zero F7 writes**. The init/F7 SDK branch is separate from
this observed regular CE transaction. No speculative F7 is added. The existing
genuine reference table still reproduces all three captured F2 packets through
the guarded builder.

Negative F2 replies now abort immediately. Vendor notifications reject stale GATT
owners and run inline on the Bluetooth callback thread, alongside read/write
completions. Posting all vendor replies to the driver's handler introduced a race
between handshake queue operations and Bluetooth completions; that dispatch change
is reverted. F2 acknowledgement bookkeeping retains its handler post and checks
the current GATT and pending-chunk owners. Each delayed parameter chunk checks
authentication, fresh-marker eligibility, factory identity and challenge again.
Source-wiring regression tests guard this dispatch boundary; they do not establish
thread safety of every existing timer or lifecycle path in the driver.

Advertisement-based exclusion is removed. Advertised services can be a subset
of the real GATT services; their omission of 0x181F does not prove a non-CGM
peripheral. Existing post-discovery service and identity gates remain in place.
Tests cover a candidate with a non-CGM advertised subset that exposes CGM in its
full discovered service table.

Cloud credentials remain bundled by the existing PR decision; their comments
now state that accurately. Automatic lookup remains enabled for eligible fresh
sensors, and this patch adds no user-facing cloud opt-out. This is a product
policy to assess explicitly before public release. Physical fresh-sensor
activation and partial-transfer recovery remain unverified. No device writes
were performed during this follow-up.
