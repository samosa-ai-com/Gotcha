package com.gotcha.shell;

parcelable ShellInfo {
    int interfaceVersion;
    boolean rootfsReady;
    /** For example "ubuntu-24.04.5"; null when no rootfs is installed. */
    @nullable String rootfsRelease;
    boolean installing;
    /** Bytes the companion uses on disk, rootfs included. */
    long dataBytes;
    int runningJobs;
    /** Open pty sessions (added in interface version 3). */
    int sessionCount;
    /** Most sessions that may be open at once; an open beyond it throws IllegalStateException("Too many sessions"). */
    int maxSessions;

    // Added in interface version 4.

    /** True when the Linux runtime (PRoot, downloaded separately) is installed. */
    boolean runtimeReady;
    /** For example "5.1.107.91-1"; null when the runtime is not installed. */
    @nullable String runtimeVersion;

    // Added in interface version 6.

    /** True while the runtime is being downloaded or unpacked. */
    boolean runtimeInstalling;
}
