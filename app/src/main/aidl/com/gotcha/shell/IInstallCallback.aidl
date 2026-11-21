package com.gotcha.shell;

oneway interface IInstallCallback {
    /**
     * phase is "download", "verify", "extract" or "configure" for the Linux
     * system, or "download-runtime", "verify-runtime" or "install-runtime"
     * for the runtime. total is -1 when unknown.
     */
    void onProgress(String phase, long done, long total);
}
