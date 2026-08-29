## Purpose

Surfaces the Claude subscription's remaining quota — the 5-hour session window
and the weekly window, with their reset times — inside the herdr-watch dashboard,
so an operator can tell before starting work whether there is headroom to finish.

## ADDED Requirements

### Requirement: Serve as the status line command itself

The system SHALL provide a single command that an operator configures as their
status line command. It SHALL read the payload on standard input, write exactly
one rendered line to standard output, record the quota figures it finds, and exit
successfully.

It SHALL NOT invoke any other program, open any network connection, or read any
credential. Its only input is the payload it is given.

Because it runs on the interactive path of another program, it MUST NOT fail
visibly. Any internal failure — an unwritable path, a malformed payload, absent
quota figures, an unreadable transcript, an unrecognised argument — SHALL still
produce a line, still exit successfully, and produce nothing on standard error.

Recording and rendering SHALL be independently suppressible, so that an operator
who keeps their own renderer can record without rendering, and so that each half
can be exercised on its own.

#### Scenario: Payload contains quota
- **WHEN** the command receives a payload carrying quota figures
- **THEN** it records them
- **AND** writes one rendered line describing the session

#### Scenario: Recording fails
- **WHEN** the command cannot write its state file
- **THEN** it still writes its line and exits successfully
- **AND** the line is exactly what it would have been had the recording succeeded

#### Scenario: Payload carries no quota
- **WHEN** the payload has no quota section, which is the case until Claude Code has observed rate limits
- **THEN** any previously recorded state is left untouched
- **AND** the line is still written, without the quota element

#### Scenario: Malformed payload
- **WHEN** the input is not valid, is empty, or has an unexpected shape
- **THEN** the command records nothing, writes a line, and exits successfully

#### Scenario: Nothing is written to standard error
- **WHEN** the command runs on any input at all, including invalid input
- **THEN** standard error is empty
- **AND** standard output carries exactly one line

#### Scenario: An argument is not recognised
- **WHEN** the command is invoked with an argument it does not know
- **THEN** it ignores the argument and behaves as it would by default, rather than reporting an error

#### Scenario: Recording without rendering
- **WHEN** the operator asks for recording alone
- **THEN** the quota is recorded and nothing is written to standard output

#### Scenario: Rendering without recording
- **WHEN** the operator asks for rendering alone
- **THEN** the line is written and no state file is created or modified

### Requirement: Render the session's state as one line

The line SHALL present the model in use, the consumption of the context window,
the cost and duration of the session, the lines of code changed, and each quota
window with the time remaining before it resets.

Every element SHALL be independent. An element whose data is absent, `null`, or of
an unexpected type SHALL be omitted together with its separator — never rendered
as a zero, a placeholder, or an error. A failure while rendering SHALL produce an
empty line, never a diagnostic.

Utilization SHALL be presented on a severity scale, so that pressure is readable
without reading the number. The thresholds of that scale SHALL be configurable by
the operator.

Consumption of the context window MAY be estimated from the session transcript
when the payload does not report it directly. That estimate SHALL be opt-in: by
default the command reads nothing but its input, and the element is omitted.

#### Scenario: Complete payload
- **WHEN** every element has data
- **THEN** the line carries all of them, separated consistently

#### Scenario: A field is missing or wrongly typed
- **WHEN** one field is absent, `null`, or of an unexpected type
- **THEN** its element is omitted along with its separator, and every other element renders normally

#### Scenario: Quota not yet observed
- **WHEN** the payload carries no quota
- **THEN** the quota element is absent, rather than showing zero utilization

#### Scenario: Utilization crosses a threshold
- **WHEN** a utilization figure reaches a configured threshold
- **THEN** it is presented at that severity
- **AND** an operator who configured different thresholds sees the severity change at their own figures

#### Scenario: Context consumption not reported directly
- **WHEN** the payload does not report context consumption and the estimate has not been enabled
- **THEN** the context element is omitted

#### Scenario: A reset time already in the past
- **WHEN** a window's reset time has passed
- **THEN** the window's utilization is still shown and no remaining time is claimed

### Requirement: Ignore a reading that is incomplete or has fallen behind

One record is shared by every Claude Code session on the machine, and those
sessions may be of different versions. A session that is behind reports figures
that disagree with the account's, and it reports them repeatedly.

A reading that does not carry a usable session window SHALL be discarded whole:
older clients emit a reading carrying only the weekly window, whose figures agree
neither with the account nor with each other.

A reading that goes backwards SHALL be discarded whole — a window whose reset time
precedes the recorded one, or a lower utilization inside the window already
recorded. Neither can be true of newer figures: utilization inside a fixed window
only grows, and reset times only move forward.

A discarded reading SHALL leave the record and its capture time untouched.

A window whose reset time has moved forward MAY report a lower utilization, down
to zero; that is a genuine reset and SHALL be recorded.

Recorded figures that are themselves unusable SHALL NOT block a fresh reading.

#### Scenario: Reading carries only the weekly window
- **WHEN** a reading has no usable session window
- **THEN** it is discarded whole and the previous record is left untouched, including its capture time

#### Scenario: Reading describes a window that has already reset
- **WHEN** a reading's window reports a reset time earlier than the recorded one
- **THEN** the whole reading is discarded

#### Scenario: Utilization steps back inside the recorded window
- **WHEN** a reading reports the same reset time as the record but a lower utilization
- **THEN** the whole reading is discarded

#### Scenario: Only the weekly window steps back
- **WHEN** the session window has grown but the weekly window has fallen back
- **THEN** the whole reading is discarded, not merely the window that fell back

#### Scenario: A window that has genuinely reset drops to zero
- **WHEN** a window reports a reset time later than the recorded one and a utilization of zero
- **THEN** the reading is recorded

#### Scenario: Recorded figures are unusable
- **WHEN** the recorded window cannot be read as figures
- **THEN** it does not protect itself, and a fresh reading is recorded over it

## MODIFIED Requirements

### Requirement: Record quota so a concurrent reader never sees a partial write

Several Claude Code sessions may run at once, each invoking the status line
command, while herdr-watch reads concurrently.

Writes SHALL be atomic: a reader MUST observe either the complete previous
contents or the complete new contents, never a partial or interleaved write.
Concurrent writers SHALL be safe, with the most recent write winning. The record
SHALL NOT be readable by other users of the machine.

Each record SHALL carry the time at which it was captured. That time MUST NOT be
refreshed when the figures have not changed: the command may be invoked on a
timer, with no new figures observed in between, and a refreshed time would present
old figures as current. When the figures have not changed the record SHALL NOT be
rewritten at all, so that neither its contents nor its modification time move.

A window absent from the reading SHALL be absent from the record — never recorded
as zero and never given a placeholder reset time. Figures SHALL be recorded as
whole percents; a figure arriving with a fractional part SHALL be recorded as the
nearest whole percent rather than dropped, and a figure above the maximum SHALL be
recorded at the maximum rather than dropped.

#### Scenario: Figures unchanged since the last recording
- **WHEN** the command runs again and the figures are identical to those already recorded
- **THEN** the record is left untouched, so its time keeps pointing at when those figures were observed
- **AND** its modification time does not move, so a reader polling for changes does not re-read it

#### Scenario: Reader during a write
- **WHEN** herdr-watch reads the state while the command is writing
- **THEN** it reads a complete, valid record — either the old one or the new one

#### Scenario: Concurrent sessions
- **WHEN** two Claude Code sessions invoke the command at the same time
- **THEN** the state file remains valid and reflects one of the two writes

#### Scenario: A record written by another implementation
- **WHEN** the record was written by a different implementation of this command, reporting the same figures in a different notation
- **THEN** the figures are recognised as unchanged and the record is left untouched

#### Scenario: Figures arrive as fractional numbers
- **WHEN** a utilization figure arrives with a fractional part rather than as a whole number
- **THEN** it is recorded as the nearest whole percent, and the window is recorded rather than dropped

## REMOVED Requirements

### Requirement: Capture quota from the statusline hook without disturbing it

**Reason**: the pass-through contract this requirement describes no longer exists.
There is no wrapped command to forward standard input to, no foreign exit status
to adopt, and no other program's output to stay out of the way of — the command
*is* the status line. Its surviving obligations (never fail visibly, tolerate a
malformed or quota-less payload, treat fractional figures as figures) are restated
under "Serve as the status line command itself" and "Record quota so a concurrent
reader never sees a partial write".

**Migration**: an operator replaces the wrapper prefix in their status line
command with the command itself. An operator who keeps their own renderer invokes
the command in record-only mode alongside it; the recipe is in `README.md`.
