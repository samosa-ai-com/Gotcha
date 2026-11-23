# Third-party notices

Gotcha bundles the following third-party components. Their licences require
their notices to be reproduced.

## Figtree

Copyright 2022 The Figtree Project Authors. Licensed under the SIL Open Font
License, Version 1.1. The full licence text accompanies the font in
`assets/licenses/OFL_figtree.txt`.

## openWakeWord

See `assets/licenses/openwakeword-notice.txt` for the model and notice this
app uses for wake-word detection.

## Termux terminal emulator and view

The terminal in Settings ▸ Shell ▸ Gotcha Shell uses the terminal emulator and
terminal view libraries of the Termux project
(https://github.com/termux/termux-app, tag v0.118.3), unchanged. They are
licensed under the Apache License, Version 2.0.

Copyright (c) 2005-2008, The Android Open Source Project.
Copyright (c) Fredrik Fornwall and the Termux contributors.

The full licence text and attribution are in
`assets/licenses/termux-terminal-notice.txt`. Only these two libraries are
used; the rest of Termux (GPLv3) is not part of Gotcha.

## Not in Gotcha: Gotcha Shell's components

Gotcha Shell is a separate app. It does **not** ship its Linux runtime in its
APK: PRoot (GPL-2.0-only), talloc (LGPL-3.0-or-later) and libandroid-shmem
(BSD-3-Clause) are downloaded once, on first use, and run as a separate
process. Their licence texts are installed next to the binaries with a pointer
to their complete corresponding source, so they appear in Gotcha Shell rather
than here.

Gotcha Shell also bundles Apache Commons Compress (Apache-2.0) for unpacking
the Ubuntu base archive, and carries its licence and NOTICE. It is not part of
Gotcha, so its notice is not reproduced here.
