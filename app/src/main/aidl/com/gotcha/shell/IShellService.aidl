// Binder interface between Gotcha and Gotcha Shell.
//
// Gotcha carries a copy of this directory. From interface version 2 on,
// changes are additive only: never reorder, rename or remove methods or
// fields. Add new ones at the end, raise ShellService.INTERFACE_VERSION and
// note the change in CHANGELOG.md.
package com.gotcha.shell;

import com.gotcha.shell.IInstallCallback;
import com.gotcha.shell.JobStatus;
import com.gotcha.shell.RunRequest;
import com.gotcha.shell.RunResult;
import com.gotcha.shell.ShellInfo;

interface IShellService {
    ShellInfo getInfo();

    /** Runs request.cmd with /bin/sh -c and waits. Output is capped at 32 KB per stream. */
    RunResult run(in RunRequest request);

    /** Starts a login shell on a pty; returns our end of a socket pair carrying its bytes. */
    ParcelFileDescriptor openSession(int rows, int cols);

    /**
     * Downloads, verifies and unpacks the rootfs, replacing any existing one.
     * Blocks until done; progress goes to callback. Returns null on success,
     * otherwise an error message.
     */
    @nullable String install(IInstallCallback callback);

    /** Rewrites the companion's config files in the rootfs and finishes interrupted package installs. */
    RunResult repair();

    /** Kills all jobs and sessions and deletes the rootfs. */
    void reset();

    /** Starts request.cmd in the background; returns a job id. Throws IllegalStateException when too many jobs run. */
    String startJob(in RunRequest request);

    /**
     * Status of a job and its output from the given byte offsets (at most
     * 32 KB per stream per call). Pass the returned offsets to the next call.
     */
    JobStatus pollJob(String jobId, long stdoutOffset, long stderrOffset);

    /** Kills a running job. Returns false if the job is unknown or already finished. */
    boolean killJob(String jobId);

    /** Writes everything read from source to guestPath, creating parent directories. */
    RunResult importFile(in ParcelFileDescriptor source, String guestPath);

    /** Opens guestPath for reading, or returns null if it is not a readable regular file. */
    @nullable ParcelFileDescriptor exportFile(String guestPath);
}
