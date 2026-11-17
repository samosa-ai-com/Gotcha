# Termux terminal emulator and view

This directory contains source code from the Termux project's two terminal libraries,
`terminal-emulator` and `terminal-view`, copied into this repository so the build needs no
extra Maven repository.

| | |
|---|---|
| Source | https://github.com/termux/termux-app |
| Tag | `v0.118.3` |
| Commit | `5b657c6adf4304e5198951ce815fe0205dcac29c` |
| Licence | Apache License 2.0 (text in `LICENSE`), see the caveat below |

## Licence

Termux's root `LICENSE.md` says, word for word:

> The `termux/termux-app` repository is released under GPLv3 only license.
>
> Exceptions
>
> - Terminal Emulator for Android code is used which is released under Apache License 2.0.
>   Check `terminal-view` and `terminal-emulator` libraries.

That is the only licence statement covering these files: they carry no licence header, and the
two modules have no licence file of their own. We read it as placing the two libraries under
Apache-2.0, which is also how Termux publishes and others reuse them.

**Caveat.** The wording names the code that came from Terminal Emulator for Android
(https://github.com/jackpal/Android-Terminal-Emulator); it does not say in so many words that
Termux's own later additions to these libraries are Apache-2.0 too (for example
`textselection/TextSelectionCursorController`, added by Termux in 2021). If any of that were
GPLv3-only instead:

- distributing Gotcha is still fine, because GPLv3 section 13 and AGPLv3 section 13 allow the
  two to be combined;
- but that code could not be relicensed along with Gotcha's own code. Before any relicensing
  or dual licensing of Gotcha, get a written confirmation from Termux or replace these files.

Attribution: the original code is Copyright (c) 2005-2008 The Android Open Source Project (as
recorded in the `NOTICE` file of Terminal Emulator for Android); Termux's changes are by Fredrik
Fornwall and the Termux contributors.

The rest of the Termux repository is GPLv3 and is not used here. The Apache licence text is in
`LICENSE` here and is shipped in the app, with the attribution above, as
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

`TermuxCopyPinTest` fails if any of these files differs from its checksum, so an edit made
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
