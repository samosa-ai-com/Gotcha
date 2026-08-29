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
every resume and offers, one step at a time:

- **Install Termux from F-Droid** when Termux is missing.
- **Grant permission** when the RUN_COMMAND grant is missing — the button only
  appears once Termux is installed and the build is usable.
- **Open Termux** plus the two copy-paste lines when `allow-external-apps` is
  not confirmed. A **Copy commands** button puts both lines on the clipboard:
  ```
  echo 'allow-external-apps=true' >> ~/.termux/termux.properties
  termux-reload-settings
  ```
- **Check configuration** re-runs the probe and reports configured /
  not-configured / unknown. It (and the copy box) only appears once the
  RUN_COMMAND permission is granted, so a probe is never run before it can
  answer.

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
5. **Open Termux**, use **Copy commands** (the paste box reads correctly), paste and
   run the two lines, restart Termux, tap **Check configuration** → "Termux answered —
   allow-external-apps is enabled."
6. All four steps are checked and the "Termux is set up" banner shows. Ask the
   assistant to run something: `pkg install python -y` and `python3 --version`.
7. Revoke the permission in system Settings → the page's permission step unchecks on
   the next resume, and the copy box / Check button disappear until it is granted again.

## What may go wrong

`run_termux_command` works, but it is not a server. The realistic limits,
in order of how often they bite:

- **Each call is a fresh shell.** `cd`, `export`, activated virtualenvs,
  and `source` do not carry over. Chain dependent steps in one command
  with `&&`, never split across calls.
- **No interactive prompts.** The plugin API does not connect a TTY, so
  anything that waits for typed input (a `pkg install` without `-y`, an
  `ssh` password prompt, `read`, `mysql>`) blocks until the 600 s
  timeout. Always pass `-y` / `--yes` / `-q` where the tool supports it.
- **Output is capped** at 32 KB by Gotcha (and ~100 KB by Termux itself).
  For larger output, redirect to a file inside Termux and read it back
  through Gotcha's `read_file`.
- **The model cannot kill a command.** A timed-out command keeps running
  under Termux's UID. Before launching anything long, warn the user.
- **Hard ceiling: 600 s.** `pkg install` of `ffmpeg`, `chromium`, `rust`,
  `golang`, or `texlive` can hit this. Raise `timeout_seconds` to 600
  and warn the user before starting one.
- **Package-lock contention (`exit 100`).** If `pkg install` returns
  `Could not get lock ... lock-frontend ... held by process <pid> (dpkg)`,
  another package operation is still running under Termux's uid — usually a
  timed-out install that Gotcha could not kill. It is **not** a mirror or
  network problem. Check `ps -ef | grep -E '[d]pkg|[a]pt'`; if it never
  finishes, tap **Exit** on the Termux notification (that ends every session
  and releases the lock). Never delete the lock files or `kill -9` the
  process — that corrupts the package database without releasing the lock.
  Package-manager commands are automatically wrapped in `termux-wake-lock` so
  Doze cannot throttle them mid-download.
- **Secondary users / work profiles.** `/data/data/com.termux` is only the
  primary user's path. Gotcha derives Termux's real root from the installed
  package (`/data/user/<id>/com.termux/files/usr` under a work profile);
  commands should use `$PREFIX` rather than a hardcoded `/data/data/...`
  prefix.
- **Interactive `dpkg` prompts.** A package manager stuck at
  `Configuration file '...' ... Y/I/N/O/D/Z [default=N] ?` is waiting for an
  answer no terminal can provide and blocks until the timeout. Gotcha runs
  package operations non-interactively (`DEBIAN_FRONTEND=noninteractive`, keep
  the current config) so this should not appear; if a bare `dpkg --configure`
  still asks, answer it through the tool's `stdin` param
  (`echo N | dpkg --configure <pkg>`).
- **ffmpeg link error (`libplacebo` / `libc++`).** If `ffmpeg` prints
  `CANNOT LINK EXECUTABLE ... cannot locate symbol ... referenced by
  libplacebo.so`, a freshly installed `libplacebo` was built against a newer
  `libc++` than the device has. Fix it with the targeted
  `apt-get install -y libc++` (a small package that reconfigures `ffmpeg`
  itself) rather than the heavy `pkg upgrade`, which re-asks the conffile
  questions above.
- **4 commands in flight, process-wide.** Sub-agents share the same
  semaphore. A 5th call returns an error.
- **Android 12+ background restriction.** If Gotcha is in the background
  and the assistive ball is off, `run_termux_command` fails with
  *Not allowed to start service*. Bring Gotcha to the foreground, or
  switch the assistive ball on.
- **The Play-Store build of Termux has no RUN_COMMAND API** and can
  never work with Gotcha. Only the F-Droid or GitHub build is usable.

The `termux_operations` bundled skill is the in-prompt summary of these
limits; the model picks it up automatically when Termux is in the
foreground.

## What works but is non-obvious

Most things that look broken have a non-obvious workaround. The model
should know these so the user does not have to:

- **Mirror switching.** The default `packages.termux.dev` mirror is slow
  or blocked on networks in China, parts of India, behind corporate
  firewalls, and on some ISPs. The user runs `termux-change-repo` inside
  Termux once and picks a closer mirror (Tsinghua, USTC, ISCAS, CERN, NJU
  for China; the official primary or the Netherlands mirror otherwise).
  After a mirror change, run `pkg update -y` to verify.
- **Large packages.** `ffmpeg`, `python`, `rust`, `chromium`, `golang`,
  `nodejs-lts`, `texlive`, and `qt` typically take 5-15 minutes to
  install even on a fast mirror. Always raise `timeout_seconds` to 600.
  If a `pkg install` keeps failing midway, split the network step from
  the install step with `pkg download <pkg>` and `dpkg -i` from the local
  cache.
- **ffmpeg is used by three tools.** `media_convert` (audio format
  conversion), `synthesize_podcast` / `synthesize_podcast_dialogue` with
  `format='mp3'`, and the podcast MP3 salvage path all run through Termux's
  `ffmpeg`. When it is missing, `media_convert` says exactly what to install;
  a requested `.mp3` podcast degrades to `.m4a` and explains how to finish the
  MP3 once ffmpeg is installed.
- **Shared storage (`/sdcard`).** `/sdcard/Download` is the only safe
  cross-uid bridge between Gotcha and Termux. It is empty inside Termux
  until the user runs `termux-setup-storage` once in Termux. When that grant
  is missing or a `cp` through FUSE is refused, the `pull_from_termux` tool
  falls back to a loopback transfer over `127.0.0.1` (Termux `python3`
  sender → Gotcha receiver) that crosses the uid boundary without the
  storage grant at all — so a file produced inside Termux always reaches the
  user, whichever bridge works.
- **Long-running processes.** A foreground process started by
  `run_termux_command` is orphaned the moment Gotcha returns. The
  persistence pattern is
  `nohup cmd > /sdcard/Download/x.log 2>&1 < /dev/null & echo $! > /sdcard/Download/x.pid; disown`,
  then `exit 0` immediately. For tasks longer than ~30 s, also call
  `termux-wake-lock` (and `termux-wake-unlock` to release).
- **glibc binaries.** Termux uses bionic, so a `*.deb` built for glibc
  (Debian/Ubuntu) will not run. Use `proot-distro install debian` (or
  `ubuntu`, `alpine`, `fedora`, `archlinux`) and run the command inside
  via `proot-distro login debian -- bash -c "..."`. Performance penalty
  is ~10× because proot uses `ptrace`.
- **Real root.** `proot` is not a security boundary — it does not grant
  real root. On a rooted device, `pkg install tsu -y` then `tsu -c "cmd"`
  runs with real root via Magisk/SuperSU.

The `termux_repositories`, `termux_filesystem`, `termux_background`, and
`termux_proot` bundled skills cover each of these in detail.
