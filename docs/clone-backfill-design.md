# Clone history recovery design

Status: design proposal. The current Clone implementation does not provide this
recovery workflow yet.

## Purpose

Clone normally sends new data and fills short connection gaps. History recovery
is an explicit action for larger gaps, such as restoring a nightly backup and
then recovering records that still exist on the paired phone.

Recovery can run in either direction:

- **Send to receiver** copies history from the sensor phone to its Clone
  receiver.
- **Recover from receiver** copies history from the Clone receiver back to the
  sensor phone.

The action is started on the configured sender in both cases. This keeps
recovery controls off passive follower phones and avoids reversing the normal
connection roles.

Recovery is not a replacement for database backups. It only covers supported
record categories, requires a compatible paired phone, and cannot recover data
that neither phone still has.

## User choices

Every recovery includes glucose history. Two additional categories are
independent opt-ins and default to off:

- **Journal entries** includes insulin, food, notes, edits, and deletions. Food,
  insulin preset, and other records required to render a selected journal entry
  travel with it automatically.
- **Hypo classifications** includes saved classifications and edits for detected
  hypo episodes. The episodes themselves are still derived from glucose
  history.

Calibration data is outside the first version. Calibration changes how history
is displayed and needs a separate conflict policy.

The recovery mode is selected independently of the categories:

- **Only missing data** merges records that are absent locally. Existing local
  records are not cleared or deleted. Deletion markers keep a deleted source
  record from being imported as missing.
- **Full History** validates the complete incoming package, clears the selected
  local categories, and imports the package as one database transaction. If
  validation or import fails, the existing history remains in place.

The confirmation screen must name the direction, mode, categories, source
phone, destination phone, and estimated package size. Full History also needs a
clear warning that the selected local categories will be replaced.

## Data included

### Glucose history

Glucose recovery includes:

- readings and timestamps;
- source and route provenance;
- first-stored timestamps;
- uncertainty and display metadata;
- deleted-reading markers.

Sensor files and physical sensor ownership are not transferred. A receiver can
create the minimal Clone sensor record needed to display imported readings, but
it must never claim or activate the physical sensor.

### Journal entries

Journal recovery includes stable entry identity, timestamps, values, notes,
origin information, edits, and deletion markers. Referenced food and insulin
records are embedded or mapped as dependencies. Database row IDs from the
other phone are never used as local identities because they may collide.

Origin information survives recovery. A manual entry remains manual, and a pen
reading remains a pen reading even when Clone carried it. The transport route
can be shown separately from the content origin.

Provider-specific delivery state remains local. Recovery does not copy pending
Nightscout or LibreView upload queues.

IOB, eIOB, and COB are recalculated from the recovered journal entries and the
destination phone's model settings. The current live IOB snapshot is transport
state, not a history category.

### Hypo classifications

Hypo recovery transfers the stable episode key, classification, source, update
time, and a deletion marker when a classification has been removed. This
category is optional at the protocol level so builds without the hypo feature
can reject or ignore it without blocking glucose recovery.

Trend, variance, and unclassified hypo episodes are derived again from glucose
history. Clone settings, alarms, app preferences, connection definitions, and
upload cursors are not part of a recovery package.

## Package format

The wire format starts with a small versioned manifest. The manifest contains:

- protocol and schema versions;
- a random job identifier;
- direction and recovery mode;
- requested and available category flags;
- uncompressed and compressed byte counts;
- record counts per category;
- a cryptographic digest for the compressed package.

Records use a streaming format inside a compressed package. Export and import
must not hold the complete history in memory. Package and chunk sizes have hard
limits, and all job identifiers and relative paths are validated before file
access.

The package is staged in the app's private files directory. Temporary files are
removed after success, cancellation, validation failure, or expiry.

## Transport

Recovery uses the existing authenticated, encrypted Clone connection. The
sender initiates all requests, including **Recover from receiver**.

The first request is a capability probe represented as a reserved virtual file
request. An older peer treats it as a missing file instead of receiving an
unknown command, so normal Clone traffic continues. A compatible peer returns
the supported protocol version, categories, and size limits.

For **Recover from receiver**, the sender asks the receiver to prepare a
package, reads its manifest, and then pulls bounded chunks. For **Send to
receiver**, the sender prepares the package and writes bounded chunks to a
staging area on the receiver. A final commit message asks the destination to
verify and import the staged package.

Recovery traffic uses the current Clone path, whether that path is local ICE,
direct remote ICE, or TURN. Rendezvous and STUN do not receive the history
package. TURN can relay its encrypted bytes but cannot read the contents.

Normal glucose delivery takes priority over recovery chunks. Interrupted jobs
can resume at a verified chunk boundary, and a network change must not force a
new export if the staged package is still valid.

## Import rules

All imports first verify the manifest, declared sizes, category support, and
package digest. Malformed or unsupported records fail the job before any Full
History deletion starts.

**Only missing data** uses stable cross-device identities and idempotent upserts.
Repeating the same job produces the same result. Existing local content wins
when the package cannot prove that its record is a newer revision.

**Full History** operates only on the selected categories. It validates and
stages the whole package, then clears and imports those categories inside one
Room transaction. An exception rolls back the transaction. A process death
before the transaction commits leaves the old database state intact.

Deleting or replacing glucose history must not delete native sensor data,
connection definitions, settings, alarms, or unrelated journal categories.

## Optional feature providers

The core Clone implementation owns glucose and the base journal format. Other
features can register a category provider with:

- a stable category identifier and schema version;
- capability and size reporting;
- streaming export;
- validation;
- merge import;
- transactional clear and full import.

This keeps Clone recovery compatible with builds that do not contain meal or
hypo features. Journal dependencies supplied by an installed provider travel
automatically when the journal category is selected. They do not add another
checkbox.

## Failure handling

The UI reports preparation, transfer, validation, import, completion,
cancellation, and a concise failure reason. It must not report success before
the destination commits the import.

Cancellation stops after the current bounded chunk. Only missing data remains
safe because each operation is idempotent. Full History cancellation before
the database transaction changes nothing; cancellation after commit reports
completion.

A failed recovery does not disable Clone, remove sensors, reset normal sync
cursors, or fall back to destructive database migration.

## Verification

Tests must cover:

- capability negotiation with new and old peers;
- both transfer directions;
- every category combination;
- Only missing data repeated twice;
- newer local journal revisions and deletion markers;
- dependency ID collisions;
- Full History success and rollback after an injected failure;
- package corruption, truncation, oversize input, and invalid paths;
- disconnect and network change during transfer;
- process restart before transfer, during staging, and during import;
- normal live glucose delivery while a large recovery runs;
- peers with and without optional meal and hypo providers.
