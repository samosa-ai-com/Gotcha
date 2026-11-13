package com.gotcha.shell;

parcelable JobStatus {
    const int RUNNING = 0;
    const int EXITED = 1;
    const int KILLED = 2;
    const int TIMED_OUT = 3;
    /** The id is unknown, for example because the companion restarted. */
    const int UNKNOWN = 4;

    int state;
    /** Valid when state is not RUNNING or UNKNOWN. */
    int exitCode;
    String stdout;
    String stderr;
    long stdoutOffset;
    long stderrOffset;
    /** The job wrote more than the companion keeps (16 MB per stream); later output was dropped. */
    boolean outputDropped;
}
