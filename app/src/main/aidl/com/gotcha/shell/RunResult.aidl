package com.gotcha.shell;

parcelable RunResult {
    int exitCode;
    String stdout;
    String stderr;
    boolean timedOut;
    boolean truncated;
}
