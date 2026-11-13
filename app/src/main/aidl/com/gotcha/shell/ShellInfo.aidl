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
}
