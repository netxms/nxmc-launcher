# Profile

## What it is

- A standalone Java 17 / SWT desktop launcher for nxmc, the NetXMS management client, shipped as one
  fat jar to end users on Linux (GTK), macOS and Windows, x86_64 and aarch64.
- It probes a NetXMS server's version over the base NXCP protocol, downloads and caches the matching
  nxmc build (HTTPS plus sha256 against a published manifest), logs in including 2FA and an expired
  password, and hands the session to a child JVM through an ephemeral token.
- Several instances may run at once against one build cache. The cache is guarded by per-branch file
  locks, a shared pin held across the launch, atomic renames and a replayed install marker.

## What a real failure looks like

- A session handed to the wrong server, or a token spent on a child that cannot use it.
- A launch from a jar whose recorded version or hash does not match its bytes; a branch left
  half-replaced after a failed or killed install; a build one instance is launching evicted or
  replaced under it by another.
- A password or hand-off token reaching disk, a dialog, or the child's stderr unredacted.
- A download accepted from an origin the manifest did not vouch for, or with its digest unchecked.
- The manifest reached when the user never permitted it, or more than once a day per branch.
- Any "launch anyway" path: a fallback that hands over a session the launcher could not fully verify.
- The SWT display thread blocked on a resolver, the network or an unbounded read.

## Blast radius

- Every user of a NetXMS deployment that adopts the launcher: a wrong hand-off costs a spent token and
  a confusing login dialog, a cache defect a re-download or a build that will not start.
- Operators of mirrors through `-Dnxmc.launcher.manifest=`.

## The reporting bar

- Material over merely true. Worth reporting: a deviation from a rule in `CLAUDE.md`, a window between
  a decision and a write that another instance or a kill can exploit, a state the code says is
  impossible that is reachable.
- `CLAUDE.md` names many accepted losses: the Windows `javaw` message box, the pre-#3465 IPv6 hand-off,
  filesystems without shared locks, a kill-stranded temp directory, imported builds being evicted.
  Re-reporting one is noise unless the recorded reasoning is wrong.
- Guards for states no released version can produce are unwanted; the project prefers accepting the
  case and documenting it.
- Style, naming, comment wording and method length are not findings. Comments exist only to say why;
  the absence of a comment that would restate the code is not a defect.
- A change to code without a test in the same commit is incomplete.

## Where the rules live

- `CLAUDE.md`: every design rule with its argument. A deviation is always worth reporting.
- `README.md`: user-facing behaviour, file layout, the failure table, the manifest format.
- `docs/plans/`: historical implementation plans, not a source of current rules.

## Conventions that are deliberate

- Allman braces, three-space indent, no braces on single-statement blocks.
- Java 17: records for data, `Optional` for absence, no null returns.
- Failures are typed exceptions carrying a `Kind` enum; callers branch on the kind, never on text.
- `ConnectFlow` owns sequencing and every error decision; `LauncherWindow` only dispatches onto the
  display thread; UI rules worth a test live in `DisplayFormat` and `DownloadEta`.
- `NxcpSessionService` is the only class that imports `org.netxms.client`; `AppDirs` the only place
  that reads `System.getenv` and `user.home`, with `AppRunner` reading `JAVA_HOME` and `PATH`.
- `Bootstrap` references nothing outside the JDK, and the fat jar carries no `Class-Path` entry.
- No mocking framework: fakes are hand-written subclasses, which is why some classes are not final.
  Package-private constructors and methods are test seams, not oversights.
- The SWT widget classes have no automated coverage; nothing in the suite creates a `Display`.

## Languages

- Java for main and test sources, Maven `pom.xml` with an assembly descriptor, Markdown docs.
