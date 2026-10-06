# nxmc-launcher conventions

Standalone Java/SWT launcher that detects a NetXMS server's version, downloads the matching nxmc
build, logs in, and hands the session to nxmc via an ephemeral token. See [README.md](README.md)
for what it does and how to build it.

## Design rules

**No degraded fallback modes.** The rule the whole design hangs on. If the launcher cannot hand off a
properly authenticated session it says why and stops: no plain-launch path, no
`-ignore-protocol-version`, no "launch anyway", no retry with a file whose digest did not match. Only
user-correctable conditions retry in place — bad credentials, bad 2FA code, rejected new password.
The one sanctioned exception is `ConnectFlow.availableUpdate`, which swallows manifest
`FETCH_FAILED`/`MALFORMED` on a cache hit and launches the already-verified build. `UNTRUSTED_URL` is
tampering and stops. Nothing else may grow an exception like this without the same argument.

**The manifest is reached only on consent, then once a day per branch.** `ConnectFlow.checkDue` is
both gates: `LauncherSettings.checkForUpdates`, a `Boolean` in `settings.json` asked once at first
start where *unset reads as no*, and `UPDATE_CHECK_INTERVAL` of 24 h spent against `lastChecked` in
each branch's own `meta.json`. A declined update stays silent for 24 hours; that is the intent.

- The stamp is written whatever the fetch answered, failures included — but not when
  `availableUpdate` rethrows `UNTRUSTED_URL`, and not when the attempt was cancelled during the
  fetch (`connect` reads the token between fetch and stamp).
- The budget is a budget, not a distributed lock: concurrent instances may each spend it.
- `lastChecked` is a `CheckStamp` — the moment of the last write plus whether it was an *arm*.
  Settings' "Check for updates now" arms, and an armed branch is checked whatever the switch says.
  `arm` takes a moment strictly past the recorded one, `check` never goes below it, `installed` keeps
  an arm, `orLater` reads a tie as the check, `checkedAt` answers empty for an arm. It is stored as
  the one signed number the field always held, so either launcher reads the other's `meta.json`, and
  `NEVER` is zero so a description written before the field reads as never rather than armed.
- Only `markChecked` disarms, and only the arm it *found*: it compares the caller's `servedArm`, a
  compare-and-set on *which* arm rather than whether. `markCheckFailed`, `install` and
  `recoverInterruptedInstall` (whose `promoted` keeps the later stamp) never disarm.
  `stampChecked` swallows a failing stamp.

**Minimal command set.** `CMD_GET_SERVER_INFO` (via `connect()`), `CMD_LOGIN` with its 2FA exchange,
`CMD_REQUEST_AUTH_TOKEN`, and password change. Nothing else, base protocol only —
`setIgnoreProtocolVersion(true)` and a plain `connect()` with no component versions. Adding a call
couples the launcher to a client version, which is the problem it exists to solve.

- `CMD_REQUEST_AUTH_TOKEN` is also the floor at both ends. It and nxmc's `-token=` autologin both
  first shipped in NetXMS 5.0.0, so neither a pre-5.0 server nor a pre-5.0 build can complete the
  hand-off the launcher exists to make — the floor is a consequence of the design, not a policy. It
  is `ServerVersion.isSupported`, since one fact cannot have two homes: only the major is compared,
  5.0.0 being the whole floor.
- `ConnectFlow` stops on the status line the way the unparseable-version stop beside it does: no
  `Kind`, since nothing threw, and no pass-the-password fallback. `version.full()` is unbounded so
  it appears in the detail only. The guard sits before `recordSeen`, so nothing is spent and the
  address stays out of the combo: a server that cannot ever be launched is a dead end, like one
  whose version will not parse.
- `nxmcVersion` applies the same floor, so a pre-5.0 jar is an `INVALID_PACKAGE` like any other jar
  that is not an nxmc build, and there is no such thing as a cached build nothing can launch. It
  sits in `nxmcVersion` rather than `identify` because that is also the re-read of the temporary
  file, so a source swapped for a 4.x jar while the confirmation is open is caught too.
- Password change earns its place because the launcher is the *only* place a launcher user can make
  it: nxmc gates its own expired-password dialog on `AuthenticationType.PASSWORD`
  (`Startup.java:653`, and `:501` in the rwt build), so a token hand-off never reaches it. Declining
  the launcher's prompt burns a grace login that nothing downstream will mention, and
  `NO_GRACE_LOGINS` is where that path ends — which is why it is a `Kind` of its own rather than a
  `PROTOCOL_FAILURE` reported as "The connection to the server failed.". It stops rather than
  retrying: exhausted grace logins need an administrator to reset the account, so the outcome is
  `STOPPED` and not `RETRY_LOGIN`, which selects the password for a replacement that cannot help.
  The ordinary stopped-attempt focus still applies.

**`NxcpSessionService` is the only file that touches `NXCSession`.** `LoginErrorMapper` sees
`NXCException`/`RCC`; `ServerConnection` exposes no client types; nothing else imports
`org.netxms.client`. It is what makes the flow testable without a server.

**A connection is one-shot.** `NXCSession.connect()` throws after `disconnect()`, and the server
drops sessions left unauthenticated for the length of a download. Every path that installs, and every
prompt that waits on the user, closes the probe and opens a fresh connection afterwards. Reuse is a
bug; a closed connection throws `IllegalStateException`.

- The one box that waits on the user over an open probe is `store`'s once-per-run warning about an
  unwritable `servers.json`, which `recordSeen` raises on a cache hit while the probe is still in
  use. Left open past the server's unauthenticated timeout it turns that login into a connection
  failure; the next attempt shows no box and connects normally. Accepted: closing and reopening the
  probe around it would be a second reopen path for a fault a retry already clears.

**The build being launched is pinned, not just stamped.** `ConnectFlow` calls
`PackageManager.markUsed`, then holds a `PackageManager.Pin` — a shared branch lock — from before the
login prompt until the child has the jar open. Everything that could remove the branch takes the same
lock exclusively.

- The pin is taken *after* `markUsed` and released *before* the redownload offer; both need the lock
  exclusively.
- `BUSY` is not swallowed the way the stamp is: `pin` retries (budget sized by the startup sweep's
  whole-jar hash) and then `ConnectFlow` stops, as it does for `CACHE_ERROR`. Nothing is spent yet.
- A filesystem with no shared locks is the one case that launches unpinned: `sharedLocksSupported`
  probes once and `pin` returns an empty `Optional`, never `BUSY`.
- The build is chosen before the pin exists, so `ConnectFlow` re-reads `packages.cached(branch)` with
  the pin in hand and stops unless version and hash still match. `PackageManager.install` reads the
  installed build back under the branch lock for the same reason.

**An install never leaves the branch half-replaced.** A build is a jar *and* the `meta.json` that
describes it, and no filesystem swaps two files at once.

- `setAside` gives the installed jar a second name before the new one moves over it, and `restore`
  puts it back if the move or `writeMeta` fails. Both handlers read `restore`'s answer and delete the
  canonical jar when there is nothing to put back or the rename is refused — a jar its own metadata
  does not describe must never survive the call that wrote it.
- An install that put nothing in place gives back the directory it created (`deleteIfEmpty`, under
  the branch lock).
- For a kill: `install` writes `.pending-meta.json` before the jar changes over, and
  `cleanupStaleTemporaryFiles` replays it at the next startup — `recoverInterruptedInstall` promotes
  it only if the jar hashes to what the marker describes. The
  marker is replayed rather than swept, so it is not in `isTemporary`, and `writeJson` renames into
  place so a truncated marker cannot be replayed. Recovery runs before the temporary files beside it
  are deleted; `install` replays it before its own fill and deletes the marker either way. Both
  callers hold the lock exclusively.
- Accepted: until the replay, another instance's `cached` launches the new jar under the previous
  description. It was verified before it moved; only the label is wrong.

**An install never moves a branch backwards.** The build was chosen from a reading taken before a
transfer and a user prompt, so `install` compares the version it is about to write against what the
branch holds, under the lock it already takes; if what is there is newer the fill never runs and that
build is what the caller gets.

- The comparison reads the branch *after* `recoverInterruptedInstall`, never before.
- An *equal* version is not skipped: a deliberate reinstall must not become a no-op.
- Only `download` carries the comparison. `importBuild` replaces whatever is there — it is the way
  back from a build that will not start.
- Only the *fill* is skipped; the temporary-file sweep and `evictLeastRecentlyUsed` still run, and
  the sweep is swallowed on this path.

**Only a real directory is a branch.** `branches()` skips symbolic links, and `delete` and the
temporary-file sweep clear a branch only when the path itself is a directory.

- "Clear cache" is the exception: it works from `cacheEntries()`, every branch-named entry whatever
  it holds, because nothing else the user can reach takes back a link in a branch's place. `delete`
  removes such an entry itself and still never sweeps through it.
- `sweepBranch` — the one path both go through — opens the branch by name from the cache root with
  `NOFOLLOW_LINKS` where a `SecureDirectoryStream` exists and removes entries relative to it.
  Windows has none and takes the `else` branch.
- The three that resolve a branch by name apply the rule themselves: `cached` reads a link as a miss,
  `markUsed` declines to stamp it, `createBranchDir` refuses to install into it.
- The `versions` root itself *is* followed: it is a relocation of a tree the launcher created, and
  whoever can swap it can equally drop a jar to be executed.
- Lock files live in `versions/.locks/<branch>.lock`, outside the directory they guard, and are never
  deleted.

**On Windows a running build holds its own jar, and that is documented rather than coded around.**
Windows refuses to rename over or delete a file another process has open, and nxmc holds its jar open
for the session. Each consequence is an existing stop reached by a condition POSIX does not produce;
a Windows special case would be a second path through the install.

- An update of a running branch fails at the move and rolls back. The second name `setAside` gave the
  open jar cannot be deleted either, so a `.part-` entry survives until nxmc exits and costs the next
  install its message (the fill path does not swallow `deleteTemporaryFiles`).
- Eviction and deletion fail the same way, so a running nxmc effectively pins its own build.
  `deleteEntries` sorts `JAR_NAME` to the front — the one line Windows made necessary — so the
  refusal aborts before `meta.json` is gone and leaves a jar nothing describes.
- Accepted loss: `javaw.exe` reports its own failures (corrupt jar, missing main class,
  `UnsupportedClassVersionError`) in a modal `MessageBox`, writes nothing to stderr and outlives the
  health window, so `EARLY_EXIT`, `launchFailed` and the redownload offer are unreachable for that
  class. `java.exe` would mean a console window behind the whole session. The child JVM's version is
  worth naming in a bug report.

**The server list is merged, not overwritten.** `ServerRegistry` journals each change and `save`
replays the journal onto the file as it is on disk, under `servers.json.lock` (its own file, since
`save` renames over `servers.json`). The in-memory list is a view for the window. `LauncherSettings`
gets neither: a single scalar has nothing to merge, and a missing or malformed `settings.json` reads
as unset and is replaced by the next `save`.

**A Jackson failure is converted where it happens.** Jackson 3's `JacksonException` is unchecked and
carries an unreadable file as well as unparseable JSON, so a call left unconverted lands in the SWT
dispatch loop instead of the channel its caller reports on. Reading `settings.json` and `meta.json`
answers "nothing" — the next `save` replaces the one, the other is a cache miss; `ServerRegistry`
rethrows both directions as the `IOException` that `store` and `SettingsDialog` show;
`PackageManager.writeJson` catches `IOException | JacksonException` for one `CACHE_ERROR`; and
`ReleaseManifest.parse` gives `MALFORMED`. What goes into the message is `getOriginalMessage()`: the
full text appends a parser location the user cannot act on.

**The window is nxmc's login dialog, not a management tool.** A fixed-size shell
(`SWT.TITLE | SWT.CLOSE | SWT.MIN`), logo left, Server / Login / Password right, and a footer of two
rows: the status line spanning the inner width, then Settings… and Connect.

- Everything the launcher does beyond the login is that one line, never a panel. No progress bar:
  `DisplayFormat.downloadStatus` plus `DownloadEta` — whole-download average, rounded to five seconds
  so it never counts upward, hours past the hour, percent floored. The byte-count form is defence in
  depth; `ReleaseManifest` rejects a release whose size is not positive.
- No server table and no `ServerEditDialog`: the server field is an editable `Combo`, typing an
  address adds one, forgetting one lives in `SettingsDialog`.
- A window the user cannot resize must not change height: the status label does not wrap (full text
  in the tooltip) and no state adds or removes a widget.
- Focus goes to the first field that still needs input: Server when blank, else Login when blank,
  else Password. It is applied on open (after every box `open()` can raise), on a combo `Selection`
  and on return from Settings — each time after the prefill, whose result it reads — and in
  `finished()` for `STOPPED`, once `setBusy(false)` has re-enabled the fields, over what they hold.
  The rule is `DisplayFormat.fieldToFocus`, which takes no password argument because Password is
  the fallback. `startConnect`'s validation moves and `RETRY_LOGIN`'s select-and-focus are not the
  rule: they name the field that was wrong.
- Accepted cost of the rule: SWT raises `Selection` for every saved-list change, not only a dropdown
  pick, so an arrow or page key on the combo changes the server and focus then leaves Server, and an
  Enter after it presses Connect — on the stepped-to server, with whatever Login and Password then
  hold — instead of committing a list choice. Typing in an open popup on macOS searches the list and
  may do the same mid-word. Decided: the list is picked with the mouse. The macOS run found nothing
  worse than this; the keyboard and popup cases were not noted one by one, so they stand as read
  from SWT 3.132.0.
- There is no event state telling a key from a mouse pick, and none is to be added: a `KeyDown` flag
  consumed by the next `Selection` goes stale (GTK sends no `Selection` for Down on the last item,
  so the next mouse pick is misread). Nor is the move wrapped in `asyncExec`; a direct `setFocus()`
  from the selection handler showed no failure, and one that does is the evidence the wrapper needs.

**The logo is four PNGs.** `logo.png` and `logo-dark.png` at 150×166, `logo@2x.png` and
`logo-dark@2x.png` at exactly double — verbatim copies of nxmc's `login*.png`, renamed, moving as a
set. Nothing in the build links the two repos.

- `LauncherWindow.logoBase` picks the pair by measuring the window's own `COLOR_WIDGET_BACKGROUND`
  with `DisplayFormat.isDarkColor`, *not* `Display.isSystemDarkTheme()`: the API answers what the
  desktop prefers, not what SWT paints. nxmc shipped that API in 2023 and removed it in 2024
  (`5b6e434644`).
- Intended consequence: a Windows user in dark app mode sees the light asset for as long as SWT win32
  paints light controls. Not a bug and not to be fixed.
- Chosen once at construction — no `SWT.Settings` listener; the window lives seconds.
- `logoProvider` answers 100 and 200 and `null` for every other zoom, because SWT reads a provider's
  data as already sized for the zoom it asked for. A missing `@2x` is not a failure; the 1x is read
  eagerly because a `null` at zoom 100 raises `ERROR_INVALID_ARGUMENT`.
- Both are named package-private methods so the two rules are tests rather than comments.

**Everything the user can set is in `SettingsDialog`.** The update checkbox saves on toggle, and
"Check for updates now" sits beside "Delete selected", enabled with it.

- The button fetches under `BusyIndicator.showWhile` (no worker thread — `ManifestClient` bounds the
  exchange), decides with the same `packages.updateAvailable` the connect path uses, and never
  downloads: finding something newer *arms* the branch.
- Sentence, failure flag and stamp all come from `DisplayFormat.updateCheckResult`, a pure function,
  because nothing in the suite reaches the dialog. It is handed the manifest as well as the answer,
  since `updateAvailable` returns empty both for a current branch and for one the manifest does not
  carry, and "up to date" must not claim a comparison that never happened. Both stamp `CHECKED`.
- A failed fetch is `Dialogs.error` here, unlike the connect path: the user pressed the button.
- The stamp is written inside `checkFor`, before the sentence is chosen. A failed `CHECKED` is
  swallowed; a failed `ARMED` *is* the offer, so `DisplayFormat.updateNotArmed` replaces the promise.
  `armUpdateCheck` returns whether the branch took the arm, and a branch that went away during the
  fetch gets `DisplayFormat.updateBranchGone` rather than an answer.
- One reading of the branch keeps answer, sentence and stamp about the same build: `checkFor` takes
  it after the fetch and hands it to `updateAvailable`, which is why that takes a `CachedPackage` and
  has no branch-name overload. `ConnectFlow` hands it the reading from *before* the fetch. The table
  row is not that reading — but its `checkStamp` is what `markChecked` gets as `servedArm`.
- Both stamps report whether there was a *build*, which `cached` answers and `meta.json` cannot.
  `markUsed` and `markCheckFailed` discard the answer; *which* build goes unchecked.

**The update-check question is asked once, before anything can be typed.** `LauncherWindow.open()`
puts it up when `checkForUpdates()` is unset, after `shell.open()`; it needs no counter, since
`open()` runs once per run. `DisplayFormat.updateCheckQuestion` holds the wording, including the
sentence saying what the check transmits — literally true of `ManifestClient`'s bare GET, and pinned
by a test that reads the request the fixture server received.

- The `User-Agent` is an explicit bare `nxmc-launcher` with no version, since the JDK default carries
  the JVM's. The download request carries the same one.
- The host named is `manifests.host()`, not `DEFAULT_MANIFEST_URL`, so a
  `-Dnxmc.launcher.manifest=` mirror is what the user is asked about. That is why the window holds a
  `ManifestSource`.

**The status line is the only channel an attempt reports on.** `showFailure(summary, detail)` is
every failure, correctable or not — whether to retry is carried by `Outcome`. The summary is one
sentence composed from the exception's `Kind`; the detail is the full text, shown in the tooltip.

- A `SessionException` the user can correct keeps the server's own wording as the summary.
  `PROTOCOL_FAILURE` does not: what the client library threw is bounded by nothing.
- A box is for what is not an attempt state — questions (`confirmDownload`, `confirmUpdate`,
  `confirmRedownload`, `promptNewPassword`, the first-start switch), `showWarning` and
  `showDiagnostic`. Every `LaunchFailure` is a box: an exit code and a stderr tail are lines the user
  has to copy.
- Address validation is a status-line message, not a dialog.
- `promptNewPassword` reopens over the failure's status line carrying the reason
  (`DisplayFormat.passwordExpiredMessage`) — the same question saying why it is asked twice.

**A server is recorded when its version was read and cleared the version floor, its login when the
launch worked.** `recordSeen` runs right after the pre-5.0 guard, `remember` only after the child is
up. Both go through `store`, which applies to `registry.find(...).orElse(server)` so a failed
re-login cannot erase an earlier `lastLogin`. The combo offers every server the launcher could
launch against, the login field only a name that worked.

- The combo is ordered by `DisplayFormat`'s history comparator over `lastUsed`, stamped from
  `ConnectFlow`'s injected `Clock`.
- `finished()` refills the combo but does not re-run the pre-fill, which would take back the user
  name the attempt was made with.
- `store` warns about an unwritable `servers.json` once per launcher run, not once per record.

**A cancel is a stop, not a failure.** `CancelToken` is one `boolean cancelled()`, created per
attempt by the window and passed as a parameter — never a field, never a thread interrupt. Status
"Cancelled.", no error box, `Outcome.STOPPED`; the existing cleanup carries it.

- `ConnectFlow` checks at phase boundaries only (after the probe, after each manifest fetch, before
  the login prompt); `streamTo` checks per read chunk beside the `ResponseDeadline`, raising
  `CANCELLED`. `CancelToken.NONE` and the 3-argument overloads stay.
- The two calls a download blocks in answer the token themselves, never by interrupt. Headers are
  `sendAsync` plus a short-step `get`, which also enforces the header timeout — `HttpRequest.timeout`
  covers body consumption from JDK 26 on (JDK-8370631) and would abort a merely slow transfer.
  Abandoning is two cases: `cancel(true)`, or closing the body of a response that beat it.
- A blocked read cannot poll, so the token also goes to `ResponseDeadline`, whose watchdog closes the
  body; `streamTo` asks the token *before* `expired()` so a cancel does not come back as a stall or a
  checksum mismatch.
- A token tripped after the last byte stops nothing. The check before the login prompt is
  deliberately the last — past it the server is spending state on this attempt, and
  `aCancelPressedAfterTheLastBoundaryStillHandsTheSessionOver` pins that.

**Secrets never reach disk, or into a dialog.** `ServerEntry` has no field able to hold one. The
password lives only in the SWT field, the hand-off token only in the child's argv, and
`AppRunner.redact` strips it from the child's stderr. `StderrTail` redacts as it fills rather than
when read, since a token halved by the 8 KiB trim could not be matched afterwards.
`ServerRegistryTest` scans the serialized JSON.

**Base directories through `AppDirs` only.** `System.getenv` and `user.home` appear there and nowhere
else, except `AppRunner`, which reads `JAVA_HOME` and `PATH`. No flatpak-specific paths — that
packaging lives outside this repo.

- It is `AppDirs` and not `XdgDirs` because Windows uses `%APPDATA%\nxmc-launcher` and
  `%LOCALAPPDATA%\nxmc-launcher` — roaming config, local cache. The directory name is the unqualified
  `nxmc-launcher` everywhere.
- Each platform reads only its own convention, with no fallback across: a value unset, blank,
  relative or refused by `Path.of` falls back *within* it (`~/.config`, `~/.local/share`,
  `...\Roaming`, `...\Local`). This runs before there is a window to report an exception through.
- `HOME` is read on the XDG platforms only and carries no `Path.of` guard, being what the others fall
  back to.

**The shipped jar is a jar of jars, and `Bootstrap` is the only class at its root.** All six SWT
fragments (GTK, cocoa, win32 × x86_64, aarch64) sit intact under `BOOT-INF/swt/` and every
application jar under `BOOT-INF/core/`. `Bootstrap.swtVariant` reads `os.name`/`os.arch`,
`extractClasspath` unpacks the core jars plus that fragment, and `launch` invokes `Launcher.main`
through a `URLClassLoader` whose parent holds nothing but `Bootstrap`. A platform outside the six is
a stderr line and exit 1, never a fallback.

- Nothing outside the JDK may be referenced from `Bootstrap`: one import of SWT, Jackson or another
  launcher class is a `NoClassDefFoundError` at startup.
- The assembly must not add a `Class-Path` to the fat jar's manifest — it is the one way the parent
  loader could resolve launcher or SWT classes.
- The thread context class loader is deliberately left alone (nxmc parity; no `ServiceLoader`,
  jackson and slf4j fall back to their own loaders, simple-xml's `strategy.Loader` is unreachable).
  Setting it is what would need an argument.
- `Bootstrap.scratchDir` makes a per-run `Files.createTempDirectory` under `java.io.tmpdir` and
  extracts every jar inside it. Not tidiness: `netxms-base`/`netxms-client` carry a nested
  `Class-Path`, and `URLClassLoader` searches those names ahead of URLs it has not opened, so a
  shared `/tmp` would let any local account pre-create one and run code inside the process holding
  the password and the token.
- The directory is registered with `deleteOnExit` *before* the jars in it (hooks unwind in reverse),
  and `launch` closes the loader before `main` returns so Windows can delete the copies. A kill
  strands a scratch directory; accepted, nxmc parity.

**The hand-off is `-server=host:port`, with the default port left off.** nxmc's `LoginJob` splits on
the colon and reads host plus port only for exactly two parts. `ServerEntry.compactAuthority` omits
`DEFAULT_PORT` and always brackets an IPv6 literal, giving `srv`, `srv:1234`, `[::1]` and
`[::1]:1234`; the first three parse in every released nxmc, the fourth waits on
[netxms/netxms#3465](https://github.com/netxms/netxms/issues/3465). `DisplayFormat.address` shares
`compactAuthority` for the combo and says so — fixing the combo for #3465 must not change the
hand-off with it.

- Brackets are unconditional because `String.split` discards *trailing* empties: `2001:db8::` gives
  exactly two parts, so a bare literal would send nxmc to host `2001` while the launcher reported a
  successful hand-off. A wrong server, silently, is the one failure worse than a stop.
- The stored address is unbracketed, enforced in `ServerEntry`'s compact constructor rather than
  `of()`, because a hand-edited `servers.json` deserializes straight into it. Brackets belong to
  `bracketedHost()` alone.
- `DisplayFormat.parseAddress` validates with `URI.create("//[" + literal + "]")`, never
  `InetAddress.getByName` (which blocks the display thread and accepts malformed literals), and asks
  what host the `URI` produced — a literal carrying its own `]` closes the bracket early. Unbracketed
  text with two or more colons is a whole address; a zone identifier is rejected by an explicit `%`.
- No version gate and no IPv6 guard in `ConnectFlow`. A pre-#3465 nxmc takes `[::1]:1234` for a host
  name and blocks in its own login dialog with the token spent; accepted, since the only route in is
  typing an address that has never worked and the guard would have to be removed when #3465 ships.

**Child JVM.** On macOS it needs `-XstartOnFirstThread` or SWT dies with "Invalid thread access"
(`AppRunner.command`). On Windows it is `javaw.exe` and nothing else — `java.exe` leaves a console
window behind the session, and since no runtime ships one without the other a missing `javaw.exe` is
`JAVA_NOT_FOUND` naming what was searched for. No `PATHEXT` handling.

- The `PATH` lookup goes through `AppDirs.windowsValue`: `System.getenv()`'s map is keyed by the
  process's own spelling, and Windows spells it `Path`. Case-insensitive on Windows only, since
  `path` beside `PATH` is another variable on POSIX.
- The separator comes from the injected `os.name`, not `File.pathSeparator`, which answers for the
  host and so cannot be reached from a suite on one platform.
- An entry `Path.of` rejects — a quoted directory — costs the search that entry rather than throwing
  past the `LaunchFailure`.

**Download trust is HTTPS plus hash.** The release URL must be HTTPS on the manifest's own host and
port, and the sha256 of the *closed* temporary file must match before the atomic rename. The client
follows redirects, so `PackageManager` also rejects a response whose final URI left the published
scheme, host or port (origin compared with scheme defaults filled in, `PackageManager.port`).
`ReleaseManifest.parse` is given the configured URL and not `response.uri()` — a host that only
received a redirect must not vouch for its own downloads. Signing is deliberately absent.

**A build the user brings is a build.** `SettingsDialog`'s *Add from file...* takes a jar the user
has, and downstream nothing can tell it from a download: `importBuild` runs the same
`install(branch, Filler)` sequence, so the hash is recorded, the jar appears only after the atomic
rename, and rollback and replay work unchanged. Only the trust anchor moves, and only the fill
differs — a download refuses a digest that is not the published one, an import digests as it copies.
The recorded hash is how `ConnectFlow.sameBuild` tells a replacement apart; it was never a provenance
claim.

- The jar names itself: version and branch come from `META-INF/MANIFEST.MF`'s `Package-Version`,
  never typed. The gate is that attribute plus `Main-Class: org.netxms.nxmc.BootstrapLoader`,
  together, because nxmc's `standalone` profile alone sets either — so it also refuses the nearest
  miss a support channel sends, the plain nxmc jar of the right release.
- `identify` and `importBuild` are two calls, the branch being known only once the jar is read, and
  the gate is applied again inside the filler to the temporary file: the source can be swapped, or
  still be copying, while the confirmation is open.
- There is deliberately no origin marker anywhere, which keeps the design from growing a second case
  of everything. Three accepted costs: `confirmRedownload` can delete an imported build; a public
  branch is still offered manifest updates over an imported jar; eviction can remove an imported
  build. Settings is the way back from each.
- `INVALID_PACKAGE` covers every `identify` rejection and no caller branches on it. `IMPORT_HINT` is
  appended to the *detail*, never the summary, at the two exits with nothing to download — a branch
  the manifest does not carry, and a fetch or parse failure on a cache miss. Not on `UNTRUSTED_URL`.

**A stalled body is a failure, not a wait.** `HttpRequest.timeout` covers an exchange only up to the
headers before JDK 26, so `ManifestClient` and `streamTo` both arm a `ResponseDeadline` on the body —
otherwise a quiet server blocks a window that cannot be closed, holding the branch lock with it.

- Only `ManifestClient` sets a request timeout, and it enforces `MAX_BODY_BYTES`: its host is
  authenticated by TLS and nothing else, so an unbounded body could exhaust the heap before a field
  is validated. A build has a published size instead, which `streamTo` refuses to exceed while
  bounding its headers in `send`.
- The download deadline is an *idle* one, and both check `expired()` after the read as well as on
  failure — closing a body ends a read as an end of stream, and a truncated download must not surface
  as a checksum mismatch.

## Architecture

`ConnectFlow` owns the sequence and every error decision and returns an `Outcome`
(`LAUNCHED` / `RETRY_LOGIN` / `STOPPED`). It talks to services through narrow seams and to the UI
through `LauncherView` — never a widget. `LauncherWindow` implements `LauncherView`, marshals onto
the display thread and holds no logic beyond dispatch.

Anything worth a test that lives near the UI goes in `DisplayFormat` instead, including rules that
are not formatting — `canPrefillLogin`, `fieldToFocus`, `parseAddress`, the history comparator,
`isDarkColor` — or in `ui.DownloadEta` where it needs state across calls. The only per-attempt state
the window keeps is that `DownloadEta`, replaced in `startConnect` because the first sample is the
origin later estimates are measured from, and sampled inside `showDownloadProgress` so it measures
the time on screen. Shared widget scaffolding (modal shell, OK/Cancel row, message boxes, dialog
event loop) lives in `ui.Dialogs`, so a dialog class is only its own fields and validation.

There is exactly one `LauncherSettings` per run, created in `Launcher` and handed to both flow and
window — a second copy would make the Settings checkbox take effect only after a restart.

Failures are typed exceptions with a `Kind` enum: `ManifestException`, `PackageException`,
`SessionException`, `LaunchFailure`. Callers branch on the kind, never on a message string; a new
failure condition means a new `Kind`.

Test seams are package-private constructors injecting `Clock`, `HttpClient`, environment maps,
timeouts and paths, plus small interfaces — `ConnectFlow.ManifestSource`, implemented by
`ManifestClient`, which carries `host()` beside `fetch()` and so is no longer a lambda target. No
mocking framework; fakes are hand-written subclasses, which is why `PackageManager` and `AppRunner`
are not `final`.

## Code style

- K&R braces, four-space indent, braces on every block, single-statement ones included. The IDE's
  formatter applies it; a file that drifted is reformatted, not hand-aligned.
- Java 17: records for data, `Optional` for "may be absent", no nulls in return values.
- No javadoc by default, on public types included: a name and a signature carry the contract, and
  the argument behind a rule lives in this file. A comment, javadoc or inline, goes only where the
  reason is not evident from the code — why a lock is taken, why an order matters, why a case is
  deliberately ignored, what an enum constant means to its caller — and it says the reason, never
  what the statement below it does.
- A comment long enough to explain a method in stages is standing in for structure. Split the method
  and let each name carry its step — `install` and `streamTo` were each roughly halved that way.
- One home per fact. The argument behind a rule lives here, a local non-obvious reason in one inline
  line. A fact written in both drifts in one.

## Testing

- JUnit 5, `mvn test`. Every change to code changes tests in the same commit.
- A test says what it pins in its name, what it does in its code, and why it failed in its assertion
  message. Inline prose is a fourth copy, so the suite has almost none — what is left carries a fact
  none of the three can, such as an upstream issue number. Name a fixture
  (`blockAgainstEveryRename`) instead of describing it; give a data table a name column.
- HTTP fixtures use the JDK's `com.sun.net.httpserver.HttpServer`; the fake nxmc jar is compiled
  in-test with `javax.tools.JavaCompiler` into a `@TempDir`. No external test dependencies, no
  fixture files in `target/`.
- Tests never touch the real home directory: pass an environment map and an `os.name` to `AppDirs.of`
  and a `@TempDir` to everything else, and build seam paths from that `@TempDir` rather than a
  literal `/cfg` or `C:\`, since `Path.isAbsolute` follows the host.
- The suite runs on a Windows host too: permission-bit tests carry `assumeTrue(posix)`, symlink tests
  self-skip on `UnsupportedOperationException`, "unwritable" is a directory in a file's place, and
  `JavaSource.pathLiteral` escapes a host path before it is pasted into compiled source.
- Integration tests are `*IT.java`, excluded by surefire, run under `-Pintegration` with
  `-Dnetxms.test.server/user/password`, and skip themselves when unconfigured.
- `mvn package` must succeed before a task is considered done — the assembly is easy to break and the
  unit tests do not cover it. There is no default SWT artifact: each OS/arch has its own profile and
  a platform without one fails the build on purpose. A profile selects the *compile* classpath only;
  the standalone jar carries all six fragments whatever machine assembled it.
- `java -Dos.name=<os> -Dos.arch=<arch> -jar target/nxmc-launcher-<version>-standalone.jar -version`
  guards the assembly and runs **once per supported variant** — `Linux`/`amd64`, `Linux`/`aarch64`,
  `Mac OS X`/`x86_64`, `Mac OS X`/`aarch64`, `Windows 11`/`amd64`, `Windows 11`/`aarch64` — never
  once, since a single run proves only the build machine's own fragment. The build runs all six
  itself (`exec-maven-plugin`, bound to `package` after the assembly), because what they guard is a
  list of `artifactItem`s something can fall off. They do not catch a missing core jar, `-version`
  being answered before `BOOT-INF/core/` is resolved; the descriptor covers that by taking every
  non-SWT dependency rather than a list.
- A manual GUI run is the only check that the natives load and that the logo renders in both
  appearances. The *selection* needs no `Display` — it is `DisplayFormat.isDarkColor`, and it is
  tested.
- `LauncherWindowLogoTest` decodes the four PNGs off the classpath through the same `ImageData` call
  the window makes, so a dropped, truncated or mis-sized resource fails the suite; it compares the
  variants' mean luminance, because the hand copy invites a dark variant that is a second copy of the
  light one. It also pins `logoProvider` (100 and 200 and nothing else) and `logoBase` (a dark
  background takes `logo-dark`), and reads the returned stems back against the resources, since the
  stems and the file names are two sets of literals nothing else joins.
- `DisplayFormatTest` pins `isDarkColor` at its boundary: `#777777` (L* 50.03) light, `#767676`
  (49.64) dark, making "L* < 50" a tested rule. That pair also tells the CIELAB rule from a plain
  relative-luminance cutoff, and `#0000FF` against `#00FF00` — same channel average, opposite
  verdicts — tells it from an average. `#121212` covers the piecewise linear branch.
- The SWT widget classes have no automated coverage; nothing in the suite creates a `Display`.
  Anything that must be verified has to be reachable without one (`TwoFactorDialog.selectMethod`,
  `DisplayFormat`, `DownloadEta`, `LauncherWindow.logoBase`, and the logo resources and
  `logoProvider` through `ImageData`). Changes to the windows themselves need a manual run.
