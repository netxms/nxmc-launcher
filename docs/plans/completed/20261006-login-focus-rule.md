# Login window focus rule

## Overview

When the launcher window opens, when a server is picked from the saved-server combo, when the
Settings dialog closes, and when an attempt stops, keyboard focus lands on the first field that
still needs input. Today nothing sets focus at startup, picking a server only prefills, and a
stopped attempt leaves no field focused; on macOS the window was observed opening with focus on
Login and the prefilled user name selected.

The rule: Server blank → Server; else Login blank → Login; else Password. Password is the
unconditional fallback, so its content never decides.

## Context

- `ui/LauncherWindow` — `open()`, the `serverCombo` selection listener and `openSettings()` are the
  only callers of `fillLoginFromSelection()`; the rule applies at those three and in `finished()`.
- `ui/DisplayFormat` — home of every near-UI rule that has a test (`canPrefillLogin` is the same
  shape); nothing in the suite creates a `Display`.
- SWT is pinned at 3.132.0 (Eclipse 4.38, upstream tag `R4_38`); the facts under *Accepted cost* were
  read from that version.

## Solution Overview

- The rule is a pure function in `DisplayFormat`: `fieldToFocus(server, login)` returning a nested
  enum `FocusField { SERVER, LOGIN, PASSWORD }`. It takes no password argument — the signature is
  the statement that Password is the fallback. Blank counts as empty, the same notion
  `startConnect` and `parseAddress` already use, and a null reads as blank the way it does for the
  functions beside it.
- `LauncherWindow` gets one private method that maps the enum onto the three widgets and holds no
  logic.
- `fillLoginFromSelection()` stays a pure fill and the focus move is a separate call after it: the
  rule reads the login the prefill just wrote, so at every call site it comes last.
- In `open()` the focus is applied after `askAboutUpdateChecks()` returns, which is after every box
  `open()` can raise — the `loadWarning` box before the shell opens, the first-start question, and
  the warning shown when saving its answer fails.

### After a stopped attempt

Found in review and added by Alex's decision. `setBusy(true)` disables the three fields, and SWT's
`Control.setEnabled(false)` on the focused control hands focus to a parent or drops it. `finished()`
puts it back only for `RETRY_LOGIN`, so today every `STOPPED` outcome ends with no field focused:
the Cancel button, a pre-5.0 or unparseable server version, a declined download, a manifest or
package failure, `NO_GRACE_LOGINS`, a protocol failure, a launch failure. `finished()` now applies
the rule for `STOPPED`, after `setBusy(false)` has re-enabled the fields.

An unreachable server is not among them: `UNREACHABLE` returns `RETRY_LOGIN`, as does a
`SessionException` of kind `CANCELLED`. Only a tripped `CancelToken` is a `STOPPED` cancel.

- `STOPPED` only, named explicitly rather than "everything but `RETRY_LOGIN`": on `LAUNCHED` the
  shell is already closing.
- The pre-fill is still not re-run there; `CLAUDE.md` already says why.
- The rule does not know which field was at fault, and on the dead ends — a pre-5.0 or unparseable
  version, `NO_GRACE_LOGINS` — no field is. With Server and Login filled it lands on Password
  there too; the status line is what says what failed, and no exception is made for them.
- This takes away the argument `CLAUDE.md` makes for `NO_GRACE_LOGINS` stopping rather than
  retrying — "`RETRY_LOGIN`'s only effect is to focus the one field that cannot help" — because a
  stop now reaches the same field. The sentence is rewritten around the reason that was always
  underneath it: exhausted grace logins need an administrator reset, so the outcome is `STOPPED`
  rather than one that selects Password for replacement, and the ordinary stopped-attempt focus
  still applies.
- Nothing here promises an unselected password after a stop. The rule only does not call
  `selectAll()`; a selection left by an earlier `RETRY_LOGIN` may survive the toolkit's own
  focus handling.
- No ordering guard is needed. Every prompt and diagnostic of an attempt is raised synchronously
  from the worker, so `finished()` cannot be queued while one is open, and on success
  `launchSucceeded` queues the close ahead of it.

### Unchanged on purpose

`startConnect`'s two validation moves (combo on a bad address, Login on an empty one) and
`finished`'s `RETRY_LOGIN` branch (`passwordField.selectAll()` plus focus). Those name the field
that was wrong; the rule only says where the next attempt starts.

### Ruled out

- **`display.asyncExec` around the focus move.** No concrete cocoa or GTK failure was found for a
  direct `setFocus()` from inside the combo's selection handler. If the manual run shows one, that
  is the evidence the wrapper needs; it is not written ahead of it.
- **A flag telling a key from a mouse pick.** A `KeyDown` flag consumed by the next `Selection` goes
  stale: GTK's `Combo.gtk3_key_press_event` changes the active item only when the index actually
  changes, so Down on the last item sets the flag with no `Selection` to consume it, and the next
  mouse pick is misread. Cocoa also synthesizes `KeyDown` for an open popup only for unshifted
  Up/Down.
- **Last-input-modality tracking through `Display` filters.** No stale state, but it is an input
  policy with its own wrong answers (popup opened by key, then clicked) and three toolkits to prove
  by hand.
- **Applying the rule at start and after Settings only.** The dropdown pick is the first clause of
  the request.

### Accepted cost

`Selection` is not only a dropdown pick. On GTK, Up/Down/PageUp/PageDown on a closed editable combo
change the active item and send it; win32 maps `CBN_SELCHANGE`, which includes arrow-key changes in
the list, without waiting for `CBN_SELENDOK`; cocoa's `comboBoxSelectionDidChange` raises it from
the native notification with no check for a commit. So every saved-list selection change leaves
Server, keyboard navigation included. Decided by Alex: SWT combo selection handling is poor anyway,
at least on macOS, so users pick with the mouse.

What follows from it, recorded and not fixed:

- An arrow or page key on the combo changes the server and then focus leaves; from Password back to
  Server is two Shift+Tabs.
- After an arrow has moved focus, Enter presses Connect instead of committing a list choice. A fast
  Down, Down, Enter therefore attempts a login on the first stepped-to server whenever the
  resulting Login is not blank — a recorded login, or one typed by hand, which `canPrefillLogin`
  keeps across the selection — with whatever the Password field holds: nothing clears that field on
  a selection change, so a password typed for a failed or cancelled attempt is still there, and
  `startConnect` does not reject an empty one. Only a blank resulting Login stops at "Enter a user
  name."
- On macOS, typing in an open popup does an incremental search and may change the selection
  mid-word, sending the rest of the word to Login or Password. Documented by Apple, not reproduced.

## Implementation Steps

### Task 1: Focus the first field that needs input

Touches the UI package only: `DisplayFormat` (rule), `LauncherWindow` (dispatch and four call
sites), `DisplayFormatTest`, and the project `CLAUDE.md`.

**Files:**
- Modify: `src/main/java/org/netxms/launcher/ui/DisplayFormat.java`
- Modify: `src/main/java/org/netxms/launcher/ui/LauncherWindow.java`
- Modify: `src/test/java/org/netxms/launcher/ui/DisplayFormatTest.java`
- Modify: `CLAUDE.md`

The unit test pins the rule and cannot pin the wiring: swapped arguments at a call site compile and
pass it. The start-state cases of the manual run are what catch that.

The `CLAUDE.md` bullet goes under "The window is nxmc's login dialog, not a management tool." and is
written after the macOS run, so it states what was seen rather than what was expected. It names the
rule's home and the accepted cost — every saved-list selection change leaves Server, keyboard
navigation and popup incremental search counting as far as the run confirmed them — together with
the Enter consequence above, and says why there is no event state so the flag is not reintroduced.
The architecture section's list of non-formatting rules in `DisplayFormat` gains the new one, and
the `NO_GRACE_LOGINS` sentence under "Minimal command set" is rewritten as described above.

- [x] `DisplayFormat.fieldToFocus` answers Server, Login or Password by the ordered rule, blank
      counting as empty
- [x] the window applies it on open (after every box `open()` can raise), on a combo selection and
      on return from Settings, each time after the prefill, and in `finished()` for `STOPPED`;
      `startConnect`'s validation moves and the `RETRY_LOGIN` branch are untouched
- [x] unit test in `DisplayFormatTest` pins the three outcomes, the ordering (a blank server wins
      over a present login) and blank-as-empty, in the file's style: no inline prose, reasons in
      assertion messages
- [x] `mvn test` passes
- [x] `mvn package` succeeds, including its six per-variant `-version` runs
- [x] manual run on macOS covers the cases below; a result worse than the accepted cost goes back
      to Alex before anything is recorded
  - ⚠️ run by Alex on 2026-10-06 and reported as "tested, works fine", with no per-case notes; the
    "note whether" cases are therefore recorded in `CLAUDE.md` as read from SWT source, not as seen
- [x] `CLAUDE.md` records the rule and its accepted cost as the run showed them, and argues
      `NO_GRACE_LOGINS` from the administrator reset rather than from which outcome focuses Password
- [x] move this plan to `docs/plans/completed/`

### Manual run cases

Start states — the first and third are the evidence, the second passes today as well:
- no saved servers → Server focused
- saved server without a recorded login → Login focused
- saved server with one → Password focused
- first start, update-check question showing → focus correct after it closes
- malformed `servers.json`, so the load warning shows first → focus correct once the window is up

Selection — after each keyboard case, check that the Server text and the prefilled Login belong to
the same server; a popup closed by the focus move reverting its selection would be worse than the
accepted cost:
- mouse pick from the dropdown → focus moves per the rule, popup closes cleanly
- mouse pick of the server already shown → note whether focus moves; toolkits differ on whether an
  unchanged pick sends `Selection`
- arrows on a closed combo → one step, then focus leaves
- arrows in an open popup → note whether the first step closes it
- typing in an open popup → note whether focus leaves mid-word
- arrow, then Enter → note what it connects to and with which password
- Down on the last item, then a mouse pick → focus moves
- a pick that lands on Password while it holds text → note whether the text is selected
- Login typed by hand, then a pick of a server with no recorded login → the typed name stays and
  Password is focused

Settings:
- opened and closed with nothing changed → focus per the rule, off the Settings button
- closed after forgetting the selected server → the address stays in the combo and an auto-filled
  login is cleared, so Login is focused; Password if the login was typed by hand

Stopped attempts — each should end on Password when Server and Login are filled, with the status
line still showing what failed:
- the Cancel button pressed during the connect
- a download offer declined
- a dead end: a pre-5.0 server, or an account with no grace logins left
- a stop that ends in a diagnostic box, such as a launch failure → focus is restored only once the
  box is dismissed
- a wrong password, then a stop with the password unchanged → note whether the selection the
  first attempt left is still there

Outcomes the rule must not touch:
- wrong password → `RETRY_LOGIN`: Password focused with its text selected
- an address that does not answer → also `RETRY_LOGIN`, same result
- Connect with a malformed address → Server focused; with an empty or blank Login → Login focused
- a successful launch → the window closes without coming back to the front
- the window closed while an attempt is running → nothing is thrown

Any focus move that misfires on timing, by mouse or by key, reopens the `asyncExec` decision. The
keyboard cost Alex accepted is the policy, not a licence to leave a real race in.

## Post-Completion

**Manual run on GTK and Windows**, where reachable, with the same cases: the selection paths differ
per toolkit, and the win32 ones were read from source only.

## Tandem

| Task | Writer | Reviewer |
|------|--------|----------|
| 1    | claude | codex    |
