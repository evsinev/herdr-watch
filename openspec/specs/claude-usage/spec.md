# claude-usage Specification

## Purpose

Surfaces the Claude subscription's remaining quota — the 5-hour session window
and the weekly window, with their reset times — inside the herdr-watch dashboard,
so an operator can tell before starting work whether there is headroom to finish.

## Requirements

### Requirement: Capture quota from the statusline hook without disturbing it

The system SHALL provide a hook that receives the Claude Code statusline payload
on standard input, extracts the quota figures, records them, and forwards the
input unchanged to the operator's existing statusline command.

The hook is on the interactive path of another program, so it MUST be
transparent: if recording fails for any reason — unwritable path, malformed
payload, missing quota data — the hook MUST still forward the input, exit
successfully, and produce no output of its own on standard output.

The hook MUST NOT write anything to standard output that is not the wrapped
command's own output, and MUST NOT delay that output perceptibly.

#### Scenario: Payload contains quota
- **WHEN** the hook receives a payload containing quota figures
- **THEN** it records them
- **AND** forwards the payload unchanged to the wrapped command
- **AND** the wrapped command's output reaches the terminal unaltered

#### Scenario: Recording fails
- **WHEN** the hook cannot write its state file
- **THEN** it still forwards the payload and exits successfully
- **AND** the statusline renders exactly as it would without the hook

#### Scenario: Payload carries no quota
- **WHEN** the payload has no quota section, which is the case until Claude Code has observed rate limits
- **THEN** the hook forwards the payload and leaves any previously recorded state untouched

#### Scenario: Figures arrive as fractional numbers
- **WHEN** a utilization figure arrives with a fractional part rather than as a whole number
- **THEN** it is recorded as the nearest whole percent, and the window is recorded rather than dropped

#### Scenario: Malformed payload
- **WHEN** the input is not valid or has an unexpected shape
- **THEN** the hook forwards it unchanged and exits successfully without recording

### Requirement: Record quota so a concurrent reader never sees a partial write

Several Claude Code sessions may run at once, each invoking the hook, while
herdr-watch reads concurrently.

Writes SHALL be atomic: a reader MUST observe either the complete previous
contents or the complete new contents, never a partial or interleaved write.
Concurrent writers SHALL be safe, with the most recent write winning.

Each record SHALL carry the time at which it was captured. That time MUST NOT be
refreshed when the figures have not changed: the hook may be invoked on a timer,
with no new figures observed in between, and a refreshed time would present old
figures as current.

#### Scenario: Figures unchanged since the last recording
- **WHEN** the hook runs again and the figures are identical to those already recorded
- **THEN** the record is left untouched, so its time keeps pointing at when those figures were observed

#### Scenario: Reader during a write
- **WHEN** herdr-watch reads the state while the hook is writing
- **THEN** it reads a complete, valid record — either the old one or the new one

#### Scenario: Concurrent sessions
- **WHEN** two Claude Code sessions invoke the hook at the same time
- **THEN** the state file remains valid and reflects one of the two writes

### Requirement: Publish quota, and represent absence honestly

The system SHALL read the recorded quota and publish it as a snapshot carrying,
for each window present, its utilization and reset time, plus the capture time.

A window absent from the record MUST be reported as absent — never substituted
with a zero utilization or a fabricated reset time. When no record exists at all,
the snapshot SHALL report that state rather than an empty success.

The system SHALL NOT re-publish an unchanged snapshot.

#### Scenario: Both windows recorded
- **WHEN** the record contains the session and weekly windows
- **THEN** the snapshot publishes each one's utilization and reset time, and the capture time

#### Scenario: Only one window recorded
- **WHEN** only one window is present in the record
- **THEN** only that window is published, and the other is reported absent rather than as zero

#### Scenario: No record yet
- **WHEN** no state file exists, because the hook has never run
- **THEN** the snapshot reports the not-configured state and no figures

#### Scenario: Unchanged data
- **WHEN** the record has not changed since the last read
- **THEN** no new snapshot is published to stream clients

### Requirement: Age the data and degrade visibly

Quota figures only advance while a Claude Code session is running, so a record
can be arbitrarily old.

The system SHALL treat a record older than a configured threshold as stale, and
SHALL publish the capture time with every snapshot so a consumer can judge age
for itself. Stale data SHALL still be served, marked stale, rather than
discarded.

An unreadable or unparseable record SHALL be treated as a failure that preserves
the previously published snapshot, marked stale, and MUST NOT surface partial
values. Failures MUST NOT affect host frame collection, host health, or any other
dashboard behaviour.

#### Scenario: Record ages past the threshold
- **WHEN** the newest record is older than the staleness threshold
- **THEN** the snapshot is served marked stale, with its capture time

#### Scenario: Record becomes unreadable
- **WHEN** the state file cannot be read or parsed and a previous snapshot exists
- **THEN** the previous snapshot is served marked stale with a failure reason
- **AND** no partial values are published

#### Scenario: Host collection is unaffected
- **WHEN** reading the record is failing continuously
- **THEN** host frames, host health, and host CRUD behave exactly as before

### Requirement: Publish over the existing stream and a REST endpoint

The system SHALL publish each new snapshot to connected dashboard clients as an
additive event on the existing event stream, and SHALL expose the current
snapshot through a read-only REST endpoint for clients without a stream
connection.

The new event type MUST NOT alter the existing initial-snapshot handshake or the
existing host events, and clients that do not recognise it MUST continue to
function unchanged. A slow or disconnected consumer MUST NOT disrupt delivery to
other consumers.

#### Scenario: Snapshot pushed to stream clients
- **WHEN** a new snapshot is produced
- **THEN** every connected stream client receives it as a quota event

#### Scenario: Existing clients unaffected
- **WHEN** a client that does not recognise the quota event is connected
- **THEN** it continues to receive host snapshot and host update events unchanged

#### Scenario: Late-joining client
- **WHEN** a client requests the current snapshot over REST
- **THEN** it receives the most recent snapshot, or the not-configured state

### Requirement: Serve quota to embedded clients over the Snapshot API

Embedded clients cannot hold a streaming connection and poll for state instead.
The system SHALL expose the quota snapshot through the Snapshot API as its own
endpoint, separate from the agents endpoint.

Adding it MUST NOT alter the agents endpoint, MUST NOT change the field
composition of any existing response profile, and MUST NOT change the protocol
version.

The response MUST obey the Snapshot API's existing conventions:

- no field may be `null`; absence SHALL be expressed by omitting the window from
  a collection rather than by a null or a substituted zero;
- a single integer severity code SHALL be provided so a client driving a
  text-less indicator can act without interpreting fractional utilization;
- utilization SHALL be reported as an integer percentage.

The endpoint SHALL support conditional requests so a polling client is not
charged a full body when the snapshot has not changed.

#### Scenario: Embedded client polls quota
- **WHEN** a client requests the quota endpoint and a snapshot exists
- **THEN** it receives the protocol version, the state, a severity code, and one entry per recorded window with its integer utilization and reset time

#### Scenario: No nulls in the response
- **WHEN** any quota response is produced in any state
- **THEN** no field in it is `null`

#### Scenario: A window is absent
- **WHEN** a window was not recorded
- **THEN** it is omitted from the collection entirely rather than present with a zero or null value

#### Scenario: Unchanged snapshot
- **WHEN** a client re-polls with the validator from its previous response and nothing has changed
- **THEN** the server responds not-modified without a body

#### Scenario: Existing Snapshot API is untouched
- **WHEN** a client requests the agents endpoint in any profile
- **THEN** its response is what it would have been before this change
- **AND** the reported protocol version is unchanged

#### Scenario: Nothing recorded yet
- **WHEN** no record exists
- **THEN** the endpoint still responds successfully with the state and an empty window collection, rather than an error

### Requirement: Present quota as account-scoped

The dashboard SHALL display the quota gauge inline on the local host's card only,
and MUST label it as account-scoped.

Because the quota belongs to the Claude account rather than to the machine, the
presentation MUST NOT imply the figures describe that host's own consumption, and
remote hosts MUST NOT display a gauge.

The display SHALL show each window's reset time, SHALL indicate how recent the
figures are, and SHALL visually distinguish a stale snapshot from a fresh one.

#### Scenario: Fresh snapshot on the local host card
- **WHEN** a fresh snapshot is available and a local host is configured
- **THEN** the local host's card shows each window's utilization and reset time
- **AND** the display identifies the figures as account-scoped

#### Scenario: Remote hosts show no gauge
- **WHEN** the dashboard renders a host that is not the local host
- **THEN** that host's card shows no quota gauge

#### Scenario: Stale snapshot
- **WHEN** the snapshot is marked stale
- **THEN** the display is visually distinguished from a fresh one and shows the capture time

#### Scenario: Not configured
- **WHEN** no record has ever been produced
- **THEN** no quota element is rendered on any host card, and no error is shown

#### Scenario: Utilization severity
- **WHEN** a window's utilization crosses into an elevated or critical band
- **THEN** the display reflects that severity using the dashboard's existing status colour tokens
