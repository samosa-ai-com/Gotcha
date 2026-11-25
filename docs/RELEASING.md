# Releasing Gotcha

For anyone bumping `versionCode`. The release pipeline does the rest, with one
step it cannot do for itself: handing the telemetry vocabulary to the ingest
service, which lives in a separate repository.

## How a release happens

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` on a branch off
   `development`, add the notes under `## [Unreleased]` in `CHANGELOG.md`, open a
   PR.
2. Merge it to `development`, then open `development` → `main`.
3. Merging to `main` triggers `.github/workflows/release.yml`. It reads the
   version from the Gradle file, builds and verifies the APK, tags
   `v<versionName>`, publishes the APK, rolls the changelog, regenerates
   `update.json`, and opens a PR for those two files.
4. **Squash-merge that `update.json` PR.** Until it lands, devices are not offered
   the update.

`main` is protected: one approving review, code-owner review and signed
commits. Squash-merging lets GitHub create the signed commit.

## Telemetry: what is automatic

Every release carries the ingest endpoint and enrollment key, with no per-release
action, because the workflow reads them from repository secrets:

- it passes `TELEMETRY_URL` and `TELEMETRY_KEY` to Gradle;
- it **fails the build** if either is unset, instead of shipping a build that
  cannot send;
- it fails if the telemetry host is missing from the built `classes*.dex`.

A build compiled without an endpoint is not broken — it is a deliberate no-op
for public checkouts — which is why the pipeline guards it explicitly rather
than relying on the build to complain.

## Telemetry: the one step that is not automatic

The vocabulary file is named after the `versionCode`, so **every bump renames
it**. Whether the server needs the new file depends on what changed inside it:

| What changed | Vendor to the ingest repo? |
|---|---|
| Only `app_version_code` | No. Existing vocabularies still validate every event. |
| A tool, skill, tour step or connector added, renamed or removed | **Yes** |
| An event or property added, changed or removed | **Yes** |
| `TelemetrySchema.VERSION` bumped | **Yes, and it needs server work** — see below |

Tool names change in most releases, so assume "yes" unless you checked.

### The step, in order

1. Export it — this is also what satisfies `verifyTelemetryVocabulary` in
   `check`:

   ```bash
   ./gradlew :app:exportTelemetryVocabulary
   ```

2. See what actually changed:

   ```bash
   git diff --stat app/src/main/telemetry/
   ```

3. If the content changed, the **Telemetry contract** workflow opens a PR in the
   ingest repository when you push the branch — it compares the `events` block
   against every vocabulary already vendored, so a bump that only renames the file
   opens nothing. Review and merge that PR, then tag and deploy it **before the APK
   is published**.

   If credentials for that repository are not configured (they never are on a fork),
   the workflow says so and stops. Vendor by hand instead: copy the file into
   `contracts/gotcha/schema-1/`, run that repository's `make check-contract`,
   commit, tag, deploy.

**Order matters, and it is server first, APK second.** Events from a client whose
vocabulary the server does not know are rejected and stored nowhere, so
publishing first loses the earliest data from the release — quietly, because the
client gets `200` either way.

Adding a vocabulary never invalidates older ones. The service accepts a value if
*any* vocabulary ever shipped matches, which is what lets old installs keep
reporting.

### If you skip it

Not silent, but not loud either: the affected events come back counted in
`rejected` on an otherwise successful batch. The ingest service's
`/v1/ops/summary` reports `events_rejected` with the offending event and property
key. Everything else keeps recording.

## Schema changes (rare)

Bumping `TelemetrySchema.VERSION` is not a vocabulary change; it is a protocol
change. It needs a new `schema-<n>` directory on both sides and the ingest
service's `MAX_SCHEMA_VERSION` raised, and it must be coordinated so no build
ships before the server understands it. Treat it as its own project.

## Checklist

- [ ] `versionCode` and `versionName` bumped
- [ ] `CHANGELOG.md` entry under `[Unreleased]`
- [ ] `./gradlew :app:exportTelemetryVocabulary` run; new `vocab.v<code>.json` committed
- [ ] `./gradlew :app:verifyTelemetryVocabulary` passes
- [ ] if the vocabulary content changed: vendored and deployed **before** the release
- [ ] PR approved (review + code owner), CI green
- [ ] release run printed `Telemetry endpoint compiled in: <host>`
- [ ] `v<versionName>` published and the `update.json` PR squash-merged
- [ ] in the following week: `events_rejected` still `0`

## Things that need a release, not a server change

Both are compiled into the APK, so they cannot be changed remotely:

- **The ingest origin.** Changing `TELEMETRY_URL` means a new build.
- **The enrollment key.** It ships in the APK. Minting a new one needs a release
  to carry it, while installs already registered keep working, because their
  tokens were exchanged at registration. Retiring a key stops only builds that
  still carry it, so do not retire one until the installs attributed to it have
  aged out.

## Privacy constraint on any new event

The schema is closed by design: fixed names, fixed properties, and property
values drawn from a fixed set — booleans, small integers, buckets, or words from
a list the app owns. There is no field that can hold text a user typed, saw, or
the name of an app or file. A new event that cannot be expressed that way does
not belong in telemetry.

`Telemetry.track` drops anything that does not match the schema, so a mistake at
a call site loses the event rather than sending something it should not. The same
table drives the "what we send" list on Settings ▸ Privacy.
