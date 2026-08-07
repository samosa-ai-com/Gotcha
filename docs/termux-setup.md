# Termux

Termux gives the assistant a full Linux user-space — `pkg`/`apt`, Python, and the
long tail of tools no phone ships with. Gotcha talks to it through Termux's official
`RUN_COMMAND` plugin API: a command is sent, Termux runs it in the background (no
terminal pops up), and returns stdout/stderr/exit code.

The assistant reads the status every turn (`TermuxStatus`) and the **Settings →
Termux (Linux shell)** page is the guided setup that makes it work.

## What has to be true

Four things, in order. The Settings page shows each as a checklist item with an
action button.

1. **Termux is installed** — from the **F-Droid** or **GitHub** build. The Google
   Play build is unmaintained and strips the entire RUN_COMMAND API, so it can never
   work with Gotcha — the page calls that out rather than asking for a permission
   that cannot exist.
2. **The build exposes the Run Commands API** — true for F-Droid/GitHub builds,
   false for the Play build.
3. **The Run Commands permission is granted** — `com.termux.permission.RUN_COMMAND`,
   a dangerous permission Termux itself declares. The page's "Grant permission"
   button requests it. Caveat: if Termux was installed *after* Gotcha, Android may
   not be able to grant it until Gotcha is updated or reinstalled.
4. **`allow-external-apps=true`** — set in `~/.termux/termux.properties`. The file
   lives in Termux's private storage, so Gotcha cannot read it; the page probes it
   by running a trivial command (`echo gotcha-probe`):
   - with the property set → the command succeeds → **configured**;
   - with it unset → Termux answers an errno naming the property → **not configured**;
   - permission missing, Termux stopped, or no answer at all → **unknown**.

## Guided setup (Settings → Termux)

Open **Settings → Termux (Linux shell)**. It re-reads the first three checks on
every resume and offers:

- **Install Termux from F-Droid** when Termux is missing.
- **Grant permission** when the RUN_COMMAND grant is missing.
- **Open Termux** plus copy-paste lines when `allow-external-apps` is not confirmed:
  ```
  echo 'allow-external-apps=true' >> ~/.termux/termux.properties
  termux-reload-settings
  ```
- **Check configuration** re-runs the probe and reports configured / not-configured /
  unknown.

The Settings → Permissions → *Termux Commands (Optional)* row links to the same page.

## Tools

| Tool | Use for |
|---|---|
| `run_termux_command(command, workdir, timeout_seconds, stdin)` | Run a shell command in Termux's user-space. `run_command` is the unprivileged shell inside Gotcha's own sandbox; Termux is the full one. |

## Manual end-to-end check

1. **No Termux**: open Settings → Termux → "Install Termux" step is unchecked, the
   F-Droid button is present; "Check configuration" is not offered.
2. **Install the F-Droid build**: the page now shows the version under step 1 and the
   Run Commands permission step becomes actionable.
3. **Play build**: the build step is red with the "Google Play build" message, and no
   permission button appears.
4. **Grant the permission**: the step ticks off; if no dialog appears, Termux was
   installed after Gotcha (reinstall Gotcha).
5. **Open Termux**, run the two copy-paste lines, restart Termux, tap **Check
   configuration** → "Termux answered — allow-external-apps is enabled."
6. All four steps are checked and the "Termux is set up" banner shows. Ask the
   assistant to run something: `pkg install python -y` and `python3 --version`.
7. Revoke the permission in system Settings → the page's permission step unchecks on
   the next resume.
