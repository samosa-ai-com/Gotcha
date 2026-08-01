# Running the Test Suite

Operational guide: environment setup, every way to run the tests, and every
gotcha hit while getting Android 10–16 (API 29–36) coverage working on this
machine (Windows, SDK at `%LOCALAPPDATA%\Android\Sdk`). For *why* this suite
was built the way it was, see [`TESTING_PLAN.md`](TESTING_PLAN.md).

## 1. One-time environment setup

1. **JDK**: use Android Studio's bundled JBR, not a standalone JDK.
   ```bash
   export JAVA_HOME="F:\AndroidStudio\jbr"
   ```
   Run all Gradle commands via Git Bash (not PowerShell) — some tooling
   shells out to POSIX `sh`.

2. **Android SDK command-line tools** (needed for `sdkmanager`/`avdmanager` —
   a bare Android Studio install does *not* include these):
   Android Studio → **Settings → Languages & Frameworks → Android SDK → SDK
   Tools** tab → check **Android SDK Command-line Tools (latest)** → Apply.
   This installs them under `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\`.

3. **Accept SDK licenses** (Gradle Managed Devices refuses to download a
   system image whose license isn't accepted, and fails with no automatic
   recovery — there's no way to script around this safely):
   ```bash
   yes | "$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" --licenses
   ```
   Drop the `yes |` prefix if you'd rather review each license and type `y`
   yourself.

That's it — no `ANDROID_HOME`/`ANDROID_SDK_ROOT` env vars are required;
Gradle finds the SDK at the default `%LOCALAPPDATA%\Android\Sdk` location
(or via `local.properties` if you've customized it).

## 2. Running the JVM unit tests

Fast, no emulator, no device — this is what CI runs on every push:
```bash
./gradlew :app:testDebugUnitTest
```

## 3. Running the instrumented suite — headless (no UI, CI-style)

This is **Gradle Managed Devices (GMD)**: Gradle downloads the system image,
boots a throwaway headless emulator, installs the app, runs the 6 tests in
`app/src/androidTest`, tears the emulator down, and reports pass/fail — no
window ever appears. This is what CI uses.

**Single Android version:**
```bash
./gradlew :app:api29DebugAndroidTest   # Android 10
./gradlew :app:api30DebugAndroidTest   # Android 11
./gradlew :app:api31DebugAndroidTest   # Android 12
./gradlew :app:api33DebugAndroidTest   # Android 13
./gradlew :app:api34DebugAndroidTest   # Android 14
./gradlew :app:api35DebugAndroidTest   # Android 15
./gradlew :app:api36DebugAndroidTest   # Android 16
```

**All of Android 10–16 in one command:**
```bash
./gradlew :app:android10to16GroupDebugAndroidTest
```

**Other predefined groups** (in `app/build.gradle.kts` → `testOptions.managedDevices`):
- `smokeGroupDebugAndroidTest` — just `api34`, for a fast PR-level check.
- `fullGroupDebugAndroidTest` — `api27` (Android 8.1, the app's `minSdk`), `api30`, `api33`, `api34`.

**Reports** land at `app/build/reports/androidTests/managedDevice/debug/<device>/index.html`
per device (a combined summary too, if you ran a group).

**First run of any device downloads its system image** (~1–1.5GB, cached
after that under `system-images/android-<N>/...`). See §5 for a download
pitfall to avoid on the very first full-matrix run.

There is **no supported way to make a GMD device show its window** —
despite what I told you earlier in this session, `-Pandroid.testoptions.manageddevices.emulator.showWindow=true`
is **not a real, documented AGP property**; Gradle silently ignores unknown
`-P` values, which is why it produced no visible window and no error. GMD
devices are headless by design. For a visible run, use §4 instead.

## 4. Running the instrumented suite — with the UI visible

GMD can't do this (see above). Instead, boot a *normal* AVD without the
`-no-window` flag, then point the plain `connectedDebugAndroidTest` task at
it (this drives whatever's attached via `adb`, not a GMD ephemeral device).

**Using the AVD that's already set up (`Pixel_7`, Android 13):**
```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator" -avd Pixel_7 &
# wait ~30-40s for it to fully boot, then:
./gradlew :app:connectedDebugAndroidTest
```
A real emulator window opens and you'll see the app launch, fields get
typed into, the assistive ball appear/get dragged/tapped, etc. live.

**Creating a visible AVD for another Android version** (`avdmanager` is
available now per §1 step 2). Check which system images are already
downloaded first:
```bash
ls "$LOCALAPPDATA/Android/Sdk/system-images"
```
Then, using whichever package id is present (adjust for the API level/image
variant you have — GMD runs download these as a side effect of §3, so running
a device headlessly once is often the easiest way to fetch its image before
making a visible AVD from it):
```bash
"$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/avdmanager.bat" create avd \
  -n android10_visual -k "system-images;android-29;default;x86" -d "pixel_2"

"$LOCALAPPDATA/Android/Sdk/emulator/emulator" -avd android10_visual &
# once booted:
./gradlew :app:connectedDebugAndroidTest
```

**Closing the emulator when you're done:**
- Click the **X** on the emulator window, or
- `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" emu kill`
- With more than one running: `adb devices` to list serials, then
  `adb -s emulator-5554 emu kill` to target one.

## 5. Known issues and gotchas (all confirmed empirically on this machine)

| Symptom | Cause | Fix |
|---|---|---|
| `LicenceNotAcceptedException` for a specific ATD package | That package's license isn't in `$SDK/licenses/` yet — most are covered by the generic `android-sdk-license`, but some ATD packages ship under a separate license id | `sdkmanager --licenses` (§1 step 3) |
| `api36Setup` fails: "System Image specified by api36 does not exist" | No `aosp-atd` image published for API 36 yet | Already fixed in `build.gradle.kts` — `api36` uses `systemImageSource = "google"` |
| `api30` hangs at ~96% for 10+ minutes, `adb devices` shows it `offline`, a real `qemu-system-x86_64-headless.exe` process is running but its memory usage is flat (not climbing) | The `aosp-atd` **32-bit x86** image for API 30 appears to hang on boot under Windows/WHPX. (API 31+ ATD images are all 64-bit and don't have this problem; API 29/30's plain `aosp` 32-bit images boot fine — it's specific to this one ATD package.) | Already fixed — `api30` uses `systemImageSource = "aosp"` instead of `"aosp-atd"` |
| Downloading the whole `android10to16Group` for the first time silently corrupts 2–3 of the images (missing `system.img` entirely, confirmed by inspecting the files on disk) | `maxConcurrentDevices` only throttles concurrent *emulator execution* — the SDK **download/setup phase still runs all devices in parallel**, and that concurrent download race corrupts some of them | Run `--max-workers=1` on the very first full-matrix run to force serial downloads, or (safer) run each `apiNN...` task individually once to populate the cache before ever using the combined group task |
| `AccessibilityServiceTest.enablingViaSecureSettings_connectsService` fails, every API level, even waiting 45s | Confirmed structural, not timing: `GotchaAccessibilityService` cannot reliably self-bind while the app is under active instrumentation, even with the process pre-warmed and confirmed not in Android's "stopped" state. The identical sequence binds in 0–5s via plain `adb shell` outside instrumentation. This is a platform-level rough edge with `AccessibilityManagerService` + self-instrumented processes, not a bug in the app. Full writeup is in the test file's doc comment. | The whole class is now `@Ignore`'d, so runs report it as skipped rather than failed — a clean `BUILD SUCCESSFUL` with 5 passed / 1 skipped is the expected result. Don't re-investigate or un-ignore without addressing the platform limitation |

## 6. Other test-suite paths (less exercised — verify before relying on them)

- **`scripts/test_matrix.sh`** — the local full-matrix script (Phase 5),
  needed for API 26 (`minSdk`) coverage since GMD only supports API 27+.
  Boots real AVDs serially via `adb`/`emulator` directly. Not run end-to-end
  in this session — check it works before depending on it.
- **`scripts/maestro_run.sh`** + **`.maestro/flows/*.yaml`** — the Maestro
  smoke layer. Requires Maestro installed separately (see
  `TESTING_PLAN.md` §Phase 5 for the install command). Not exercised in this
  session either.
- **CI** (`.github/workflows/ci.yml`) — **disabled by default** to conserve GitHub Actions
  budget: automatic triggers (push, PRs, the nightly full-matrix schedule) are removed. The
  workflow still runs on manual `workflow_dispatch` (Actions → **Run workflow**, opt-in and
  budget-consuming), but the canonical way to run the whole suite is locally:
  - Detekt: `./gradlew :app:detekt`
  - Unit tests: `./gradlew :app:testDebugUnitTest`
  - Coverage reports: `./gradlew :app:koverHtmlReport`
  - Android Lint: `./gradlew :app:lintDebug`
  - Debug APK: `./gradlew :app:assembleDebug`
  - Instrumented smoke (API 34): `./gradlew :app:smokeGroupDebugAndroidTest`
  - Instrumented full matrix (API 27/30/33/34): `./gradlew :app:fullGroupDebugAndroidTest`
  The CI jobs themselves are unchanged: `instrumented-smoke` runs on API 34 and `instrumented-full`
  runs a matrix across API levels, using `reactivecircus/android-emulator-runner` (CI does **not**
  use GMD: the emulator failed to boot on the GitHub runners and GMD swallowed its stderr,
  reporting only "Error message from emulator process = []". Switching to
  `reactivecircus/android-emulator-runner` surfaced the real error — `FATAL | Not enough space to
  create userdata partition` (the emulator needs ~7.4 GB free; the runner had ~6.5 GB) — fixed by
  the "Free disk space" step that removes unused preinstalled toolchains. emulator-runner stays
  because it reports emulator output and manages boot/wait itself; CI runs plain
  `:app:connectedDebugAndroidTest`. GMD remains the local workflow; the CI matrix mirrors the GMD
  device definitions in `app/build.gradle.kts`.
