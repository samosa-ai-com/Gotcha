package com.gotcha.shell;

parcelable SessionHandle {
    /** Random id; pass it to resizeSession and closeSession. Valid until the session ends. */
    String sessionId;
    /** Our end of the socket pair carrying the pty's bytes, as from openSession. */
    ParcelFileDescriptor socket;
}
