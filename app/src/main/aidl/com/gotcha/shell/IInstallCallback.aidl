package com.gotcha.shell;

oneway interface IInstallCallback {
    /** phase is "download", "verify", "extract" or "configure". total is -1 when unknown. */
    void onProgress(String phase, long done, long total);
}
