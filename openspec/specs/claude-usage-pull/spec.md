# claude-usage-pull Specification

## Purpose

Obtains the Claude subscription quota directly from the account API as an optional
second source, so the dashboard keeps reporting real figures — including the
per-model weekly windows that no other route exposes — when no Claude Code session is
running to push them.

## Requirements

### Requirement: Choose the quota source explicitly

The system SHALL let an operator select which source supplies the quota: the
statusline source alone, the account API alone, or both together. The statusline
source alone SHALL be the default, because it is the only one that needs no
credential.

Selecting the account API SHALL NOT change any behaviour of the statusline source
beyond which reading is published, and vice versa.

#### Scenario: Default configuration
- **WHEN** the operator has configured nothing
- **THEN** the system behaves exactly as it did before this capability existed, makes no outbound request, and reads no credential

#### Scenario: Account API selected alone
- **WHEN** only the account API is selected
- **THEN** quota is published from it, and a recorded statusline reading is not published

#### Scenario: Both sources selected
- **WHEN** both are selected
- **THEN** both run independently, and the reading published is the one observed most recently

#### Scenario: One source fails while both are selected
- **WHEN** one source is failing continuously and the other is healthy
- **THEN** the healthy source's readings continue to be published, and the failure does not suppress them

### Requirement: Read the existing credential without modifying it

The account API requires the credential Claude Code already holds. The system SHALL
read it and MUST NOT write, refresh, delete or otherwise modify it — rotation belongs
to Claude Code alone.

The credential store MAY hold more than one entry under the same service name, only
some of which are current. The system SHALL identify the intended entry
unambiguously rather than accepting whichever the store returns first, since a stale
entry is indistinguishable from a current one except by its own contents.

The system SHALL support a headless host, where the credential is a file rather than
an operating-system keystore.

When no usable credential is available — none present, or present but lacking the
authorization the endpoint requires — the system SHALL report that distinctly from a
network or service failure, so the operator can tell "not set up" from "not working".

#### Scenario: Multiple entries under one service name
- **WHEN** the credential store holds several entries under the service name, of which one is current
- **THEN** the system selects the current one rather than an arbitrary match

#### Scenario: Credential is never modified
- **WHEN** the system has read the credential, including when the endpoint rejects it as expired
- **THEN** the stored credential is left byte-identical, and refreshing it is left to Claude Code

#### Scenario: Expired credential
- **WHEN** the endpoint rejects the credential as no longer valid
- **THEN** the system re-reads the store on its next attempt, so a rotation performed by Claude Code is picked up without a restart

#### Scenario: Credential lacks the required authorization
- **WHEN** the available credential is not authorized for the usage endpoint
- **THEN** the system reports a not-authorized state distinct from a network failure, and stops retrying at the normal cadence

#### Scenario: Headless host
- **WHEN** the host has no operating-system keystore and the credential is present as a file
- **THEN** the system reads it from there

### Requirement: Require informed opt-in for the client fingerprint

The endpoint applies far stricter rate limits to requests that do not identify
themselves as the first-party client, so a usable poll requires presenting that
client's identity.

Because that means presenting as software the operator is not, the system SHALL NOT
do it implicitly. It SHALL require a separate, explicit configuration setting whose
name and documentation state plainly what it does. Enabling the account API source
without that setting SHALL cause the source to refuse to start, reporting why, rather
than running in the stricter bucket and failing obscurely.

#### Scenario: Opt-in not given
- **WHEN** the account API source is selected but the fingerprint setting is not enabled
- **THEN** the source does not start, and the reason is reported to the operator
- **AND** no request is made

#### Scenario: Opt-in given
- **WHEN** the setting is enabled
- **THEN** requests carry the identity the endpoint expects

### Requirement: Obey the endpoint's rate limiting

The endpoint enforces hard per-credential rate limits and answers a violation with a
penalty window of many minutes. Requesting again inside that window restarts the
penalty, so an unaware client can lock itself out indefinitely.

The system SHALL treat a server-supplied retry delay as authoritative and wait at
least that long, overriding its own schedule. Its own backoff SHALL grow on repeated
rejection and SHALL be permitted to grow beyond the longest penalty the endpoint is
known to impose, so recovery is always possible.

The system SHALL poll no more often than a configured interval even when healthy, and
SHALL NOT issue overlapping requests.

Rate limiting SHALL degrade the quota display only: it MUST NOT affect host frame
collection, host health, or any other dashboard behaviour.

#### Scenario: Server asks the client to wait
- **WHEN** the endpoint rejects a request and states how long to wait
- **THEN** the system waits at least that long before its next request, even if its own schedule would have polled sooner

#### Scenario: Repeated rejection
- **WHEN** requests are rejected repeatedly
- **THEN** the interval between attempts grows, and is allowed to exceed the endpoint's longest penalty window

#### Scenario: Recovery
- **WHEN** the endpoint accepts a request after a period of rejection
- **THEN** the system returns to its normal polling interval

#### Scenario: Host collection is unaffected
- **WHEN** the account API is rejecting every request
- **THEN** host frames, host health, and host CRUD behave exactly as before

### Requirement: Publish per-model weekly windows

The account API reports weekly windows scoped to individual models in addition to the
overall session and weekly windows. The system SHALL publish each model-scoped window
it receives, identified by the model it applies to, with its utilization and reset
time.

The set of models is open and MUST NOT be treated as fixed: a model the system has
never heard of SHALL be published as received rather than discarded. A model-scoped
window that is absent MUST be reported as absent, never as zero.

Per-model windows SHALL be additive everywhere they are exposed: adding them MUST NOT
change the existing session and weekly windows, MUST NOT change the composition of any
frozen response profile, and MUST NOT change the protocol version.

#### Scenario: Model-scoped windows reported
- **WHEN** the account API reports weekly windows scoped to models
- **THEN** each is published with its model identity, utilization and reset time

#### Scenario: Unrecognised model
- **WHEN** a model-scoped window names a model the system does not recognise
- **THEN** it is published as received rather than dropped

#### Scenario: No model-scoped windows
- **WHEN** the account API reports none
- **THEN** none are published, and the session and weekly windows are unaffected

#### Scenario: The other source produced the published reading
- **WHEN** both sources are selected and the reading published is the statusline one, while a usable account-API reading exists
- **THEN** the model-scoped windows from that account-API reading are still published, so a breakdown available only from one source does not appear and disappear with the freshness comparison

#### Scenario: The source of model-scoped windows degrades
- **WHEN** the account-API reading is marked stale
- **THEN** the model-scoped windows are no longer published, rather than being shown indefinitely as though current

#### Scenario: Existing consumers unaffected
- **WHEN** a client that predates per-model windows reads the quota
- **THEN** the session and weekly windows it already understood are unchanged, and the reported protocol version is unchanged

### Requirement: Report which source produced the figures

A reading's meaning depends on where it came from: the two sources have different
freshness characteristics and different failure modes, so the same number and age can
warrant different conclusions.

Every published quota reading SHALL identify the source that observed it. That
identification SHALL reach every consumer — stream clients, the REST endpoint and the
polled snapshot endpoint — and SHALL be visible in the dashboard alongside the
capture time.

#### Scenario: Reading carries its source
- **WHEN** any quota reading is published
- **THEN** it identifies which source observed it

#### Scenario: Operator can see the source
- **WHEN** the dashboard displays quota figures
- **THEN** it shows which source they came from, next to how recent they are

#### Scenario: Source changes while both are selected
- **WHEN** both sources are selected and the most recent reading comes from the other source than before
- **THEN** the displayed source identification changes with it

### Requirement: A stale client must not corrupt the shared record

Several Claude Code sessions can run on one machine at once, and they are **not
necessarily the same version**. Older ones report a quota shape whose figures agree
neither with the account's own nor with each other, so the assumption that every
session reports interchangeable account-level data does not hold.

The statusline record is shared by all of them. The system SHALL therefore accept a
reading only when it is recognisably complete, and SHALL leave the existing record
untouched otherwise — an unusable reading MUST NOT overwrite a usable one, and MUST
NOT be published in preference to it.

#### Scenario: Session reporting an older shape
- **WHEN** a session reports a quota reading that is not recognisably complete
- **THEN** the existing record is left exactly as it was, and nothing is published

#### Scenario: Old and current sessions running together
- **WHEN** sessions of different versions report in turn, and only some readings are complete
- **THEN** the published figures come from the complete readings, and the incomplete ones neither replace nor age them

#### Scenario: A newer complete reading still wins
- **WHEN** a complete reading arrives after incomplete ones
- **THEN** it replaces the record as normal

### Requirement: Degrade to the other source rather than to a broken gauge

The account API is undocumented and unversioned, so its shape may change without
notice.

When a response cannot be understood, the system SHALL treat it as a failure of that
source, preserving the previously published reading marked stale, and MUST NOT publish
partial or guessed values. Where the statusline source is also selected, its readings
SHALL continue to be published.

A failure of the account API SHALL NOT prevent the system from starting, nor require a
restart once the endpoint recovers.

#### Scenario: Response shape changed
- **WHEN** a response cannot be understood
- **THEN** no partial values are published, the previous reading is served marked stale, and the failure reason is available

#### Scenario: Other source still works
- **WHEN** the account API is failing and the statusline source is also selected and healthy
- **THEN** the statusline readings continue to be published

#### Scenario: Endpoint unreachable at startup
- **WHEN** the endpoint is unreachable as the system starts
- **THEN** the system starts normally, reports the quota state as not yet obtained, and retries
