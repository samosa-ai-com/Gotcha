package com.gotcha.shell;

parcelable RunRequest {
    String cmd;
    /** Guest working directory; null means /root. */
    @nullable String cwd;
    /** Extra environment entries as NAME=value. PROOT_* and LD_* are refused. */
    @nullable String[] env;
    /** Seconds before the command is killed; 0 or less means the default (60 for run, 24 h for jobs). */
    int timeoutSeconds;
    /** Read as the command's stdin; null means empty stdin. */
    @nullable ParcelFileDescriptor stdin;
}
