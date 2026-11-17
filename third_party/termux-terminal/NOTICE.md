# Termux terminal emulator and view

This directory contains source code from the Termux project's two terminal libraries,
`terminal-emulator` and `terminal-view`, copied into this repository so the build needs no
extra Maven repository.

| | |
|---|---|
| Source | https://github.com/termux/termux-app |
| Tag | `v0.118.3` |
| Commit | `5b657c6adf4304e5198951ce815fe0205dcac29c` |
| Licence | Apache License 2.0 (text in `LICENSE`) |

## Licence

Termux's `LICENSE.md` (under "Exceptions") says the code of these two libraries is released
under the Apache License 2.0; it comes from the Android Terminal Emulator project
(https://github.com/jackpal/Android-Terminal-Emulator). Termux's source files carry no licence
header of their own and the two modules have no licence file of their own, so the statement in
that root file is what we rely on. The rest of the Termux repository is GPLv3 and is not used
here. Apache-2.0 code may be combined with Gotcha's AGPL-3.0.

The Apache licence text is in `LICENSE` here and is shipped in the app as
`app/src/main/assets/licenses/termux-terminal-notice.txt`.

## What is copied unchanged

Every file listed in `UPSTREAM.sha256` is byte for byte what Termux ships at the commit above,
with its original package names:

- `com.termux.terminal`: `ByteQueue`, `KeyHandler`, `Logger`, `TerminalBuffer`,
  `TerminalColorScheme`, `TerminalColors`, `TerminalEmulator`, `TerminalOutput`, `TerminalRow`,
  `TerminalSessionClient`, `TextStyle`, `WcWidth`.
- `com.termux.view`: `GestureAndScaleRecognizer`, `TerminalRenderer`, `TerminalView`,
  `TerminalViewClient`, and `textselection/*`.
- `res/`: the strings and the two text-selection handle drawables of `terminal-view`.

`ShellTerminalPinTest` fails if any of these files differs from its checksum, so an edit made
here cannot go unnoticed. Do not edit them: to take a newer Termux, copy the files again and
update the tag, the commit and `UPSTREAM.sha256`.

## What is not copied, and what replaces it

- `TerminalSession.java`, `JNI.java` and the native code (`termux.c`): Termux's session starts a
  local process on a pty. Gotcha's shell runs in another app (Gotcha Shell), so none of that is
  used.
- `src/main/java/com/termux/terminal/TerminalSession.java` here is **Gotcha's own** class (AGPL-3.0
  like the rest of Gotcha, not Termux code). It has the same name and the methods Termux's view
  calls, so the copied view is used unmodified. It reads the shell's output from a stream and
  sends keystrokes and size changes to a `Host`.
- Termux's tests are not copied.

## Changes to the upstream files

None.
