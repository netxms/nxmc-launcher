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
- A NetXMS server running 5.0 or newer
- Network access to your NetXMS server and to netxms.org

## Running it

```sh
java -jar nxmc-launcher-1.0.0-standalone.jar
```

On macOS, SWT needs the UI on the first thread:

```sh
java -XstartOnFirstThread -jar nxmc-launcher-1.0.0-standalone.jar
```

Add `-version` to print the version and exit, or
`-Dnxmc.launcher.manifest=https://example.com/nxmc-releases.json` to use a mirror or a development
build list instead of netxms.org.

## Settings

**Settings…** holds the cached builds and the servers you have connected to, and three things worth
knowing about:

**Checking for updates.** On first start the launcher asks whether it may look for newer nxmc builds.
With that off — which is also what leaving the question unanswered means — connecting to a server
whose build is cached generates no traffic beyond the NetXMS server itself. With it on, it also
looks for a newer patch of that branch, at most once a day per branch, and always asks before
replacing anything.

**Adding a build from a file.** If netxms.org is unreachable, or your branch is not published
publicly, **Add from file...** installs an nxmc standalone jar you already have into the cache.

## Files on disk

| Platform | Configuration | Cache |
|---|---|---|
| Linux, macOS | `~/.config/nxmc-launcher` | `~/.local/share/nxmc-launcher` |
| Windows | `%APPDATA%\nxmc-launcher` | `%LOCALAPPDATA%\nxmc-launcher` |

On Linux and macOS `$XDG_CONFIG_HOME` and `$XDG_DATA_HOME` take precedence if set.

Configuration is the list of known servers (`servers.json`) and one setting (`settings.json`).
The cache holds one nxmc jar per branch with a small `meta.json` beside it, plus lock files
coordinating several launcher instances.

## Building from source

```sh
mvn package
```

`target/nxmc-launcher-1.0.0-standalone.jar` is the runnable, self-contained jar and the one
to distribute. It carries all six SWT fragments — Linux GTK, macOS cocoa, Windows win32, each in
x86_64 and aarch64 — so a jar built on a Mac runs on Linux and Windows unchanged.

`mvn test` runs the unit tests. Integration tests need a real server and are excluded unless asked
for:

```sh
mvn verify -Pintegration \
  -Dnetxms.test.server=demo.netxms.org \
  -Dnetxms.test.user=admin \
  -Dnetxms.test.password=secret
```

## Release manifest

The launcher fetches JSON document — `https://netxms.org/nxmc-releases.json` by default — and
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
bytes.

Downloads are trusted by HTTPS plus hash and nothing else: `url` must be HTTPS on the same host and
port as the manifest itself — after redirects — and the downloaded file must match `sha256`.
