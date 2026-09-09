---
worth: later
where: src/main/java/org/netxms/launcher/ui/SettingsDialog.java:331
added: 2026-09-03
---
# the servedArm choice in Settings' manual check is not pinned by any test

`checkFor`/`recordCheck` hand `markChecked` the table row's `checkStamp`, read before the fetch, and
not the post-fetch reading's. Changing that one expression to `compared.get().checkStamp()` leaves the
suite green: `PackageManagerTest.markCheckedLeavesAnArmWrittenWhileItsFetchWasOnTheWire` pins the
mechanism one layer down, `ConnectFlowTest` records only the branch of a stamp, and nothing in the suite
creates a `Display`. Surfaced by the first revmux scan (2026-09-03, `whole-project/01-initial`,
finding `docs+tests-4`).

The fix would extract the choice into a package-private static on `SettingsDialog` — given the row's
stamp, the post-fetch `Optional<CachedPackage>`, the manifest and the `updateAvailable` answer, return
the `UpdateCheckResult` plus which stamp to write with which value — leaving the instance method to
call `armUpdateCheck`/`markChecked` and show the box, and rewrite CLAUDE.md's Settings paragraph with
it.

Unresolved: whether one more `DisplayFormat`-style extraction is worth it for a seam CLAUDE.md already
records as knowingly uncovered ("The SWT widget classes have no automated coverage"). It becomes a
`yes` the next time that expression is touched for any reason.
