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
}
