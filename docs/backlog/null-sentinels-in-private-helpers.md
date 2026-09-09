---
worth: yes
added: 2026-09-08
---
# five private helpers return null for "absent", against the no-nulls rule

`AppRunner.directory` (AppRunner.java:47), `AppDirs.windowsValue` (AppDirs.java:37),
`LauncherSettings.read` (LauncherSettings.java:32), `PackageManager.readJson` (PackageManager.java:197)
and `PackageManager.setAside` (PackageManager.java:222) each return `null` to mean absent, the shape
`ConnectFlow.install` and `changeExpiredPassword` had until 326949c. Surfaced by the sweep after the
ConnectFlow revmux round (2026-09-08, `connectflow/01-initial`, finding `arch+quality-6`).

Excluded on purpose: `NxcpSessionService.getTrustedDeviceToken`, `LauncherWindow.readImageData`,
`loadLogo` and `sync` return null because the client library, `ImageDataProvider` and the
disposed-display case require it.

`setAside`'s null is what `restore` and both install handlers read as "nothing to put back", so it has
the widest blast radius of the five and its callers move with it.
