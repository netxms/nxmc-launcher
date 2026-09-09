# nxmc-launcher

A version-agnostic launcher for **nxmc**, the NetXMS management client.

nxmc has to match the server it talks to, which normally means knowing which NetXMS version your
server runs and downloading the matching client by hand. The launcher does that for you: it asks the
server what version it is, downloads and caches the matching nxmc build, logs you in, and starts
nxmc with the session already open.

One launcher, every server version from 5.0 on. You type your password once.

## Requirements

- Java 17 or newer
- Linux (GTK), macOS or Windows — x86_64 or aarch64
- A NetXMS server running 5.0.0 or newer
- Network access to your NetXMS server and to netxms.org

The server floor is the hand-off: only 5.0.0 and later can issue the one-time token the launcher
starts nxmc with. Against an older server the launcher says so and stops, before downloading
anything or sending your password — use the nxmc build that matches that server instead.

## Running it

```sh
java -jar nxmc-launcher-1.0.0-SNAPSHOT-standalone.jar
```

On macOS, SWT needs the UI on the first thread:

```sh
java -XstartOnFirstThread -jar nxmc-launcher-1.0.0-SNAPSHOT-standalone.jar
```

Add `-version` to print the version and exit, or
`-Dnxmc.launcher.manifest=https://example.com/nxmc-releases.json` to use a mirror or a development
build list instead of netxms.org.

## Using it

The window is nxmc's login dialog: **Server**, **Login**, **Password**, a status line and
**Connect**. The server field is a drop-down of servers you have connected to — type an address to
add one; there is no separate "add server" step. IPv6 addresses go in bare (`::1`) or bracketed, and
need brackets when a port follows (`[::1]:1234`).

Press Connect and the launcher reads the server's version, picks the matching nxmc build (asking
before it downloads anything), logs you in — two-factor and expired-password prompts included — and
starts nxmc with a short-lived token, so it opens already connected.

Everything about an attempt is reported in the status line, download progress included; the tooltip
has the detail. Dialogs are only for questions and for nxmc failing to start, where you get the exit
code and output tail to copy.

The launcher has no degraded mode: it either hands over a properly authenticated session or tells
you why it could not. Cancel stops a running attempt cleanly, up until the login begins.

Your password never leaves the text field and the hand-off token never leaves nxmc's command line.
Neither is written to disk.

## Settings

**Settings…** holds the cached builds and the servers you have connected to, and three things worth
knowing about:

**Checking for updates.** On first start the launcher asks whether it may look for newer nxmc builds.
With that off — which is also what leaving the question unanswered means — connecting to a server
whose build is cached generates no traffic beyond the NetXMS server itself. With it on, it also
looks for a newer patch of that branch, at most once a day per branch, and always asks before
replacing anything. **Check for updates now** checks immediately whatever the setting says; it never
downloads, it just arranges the offer for the next time you connect.

**Adding a build from a file.** If netxms.org is unreachable, or your branch is not published
publicly, **Add from file...** installs an nxmc standalone jar you already have. The launcher reads
the version out of the jar — it is never typed — and from then on treats it like any other cached
build. The same 5.0.0 floor applies: an older jar is refused, since nothing could start it.

**The cache** keeps three branches. Installing a fourth evicts the least recently used one,
including one you imported, so delete branches you no longer need rather than letting the cache
choose.

## Files on disk

| Platform | Configuration | Build cache |
|---|---|---|
| Linux, macOS | `~/.config/nxmc-launcher` | `~/.local/share/nxmc-launcher` |
| Windows | `%APPDATA%\nxmc-launcher` | `%LOCALAPPDATA%\nxmc-launcher` |

On Linux and macOS `$XDG_CONFIG_HOME` and `$XDG_DATA_HOME` take precedence if set.

Configuration is the list of known servers (`servers.json`, which has no field capable of holding a
credential) and one setting (`settings.json`). The cache holds one nxmc jar per branch with a small
`meta.json` beside it, plus lock files coordinating several launcher instances. An install
interrupted by a crash is finished or rolled back at the next start.

Starting the jar also unpacks about 7 MB of nested jars into a private temp directory, deleted when
the JVM exits and never read back on a later run.

## Building from source

```sh
mvn package
```

`target/nxmc-launcher-1.0.0-SNAPSHOT-standalone.jar` is the runnable, self-contained jar and the one
to distribute. It carries all six SWT fragments — Linux GTK, macOS cocoa, Windows win32, each in
x86_64 and aarch64 — so a jar built on a Mac runs on Linux and Windows unchanged. Compiling needs an
SWT artifact for the build machine itself, selected by an OS/arch profile; there is no default, and
an unsupported machine fails the build rather than producing a jar that cannot open a window.

`mvn test` runs the unit tests. Integration tests need a real server and are excluded unless asked
for:

```sh
mvn verify -Pintegration \
  -Dnetxms.test.server=demo.netxms.org \
  -Dnetxms.test.user=admin \
  -Dnetxms.test.password=secret
```

## Release manifest

The launcher fetches one JSON document — `https://netxms.org/nxmc-releases.json` by default — and
looks up the branch of the server it just probed.

```json
{
  "releases": {
    "5.2": {
      "version": "5.2.3",
      "url": "https://netxms.org/download/nxmc/5.2.3/nxmc-5.2.3-standalone.jar",
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "size": 84213760
    }
  }
}
```

Keys are `<major>.<minor>` branches. All four fields are required; `version` must belong to the
branch it is listed under, `sha256` is the digest of the jar at `url`, and `size` is its size in
bytes. Unknown members are ignored, and one broken entry takes only its own branch offline.

Downloads are trusted by HTTPS plus hash and nothing else: `url` must be HTTPS on the same host and
port as the manifest itself — after redirects — and the downloaded file must match `sha256`. So
recompute both fields for every jar you replace; a stale digest is a hard failure with no retry.
Keep the document under 1 MiB. Adding the manifest update to the nxmc release process is the
intended way to keep it correct.

## Contributing

`ConnectFlow` owns the connect sequence and every error decision, `PackageManager` the build cache,
`NxcpSessionService` the only contact with the NetXMS client library, and `ui.LauncherWindow` the
SWT side. Conventions and the reasoning behind the design are in [CLAUDE.md](CLAUDE.md).
