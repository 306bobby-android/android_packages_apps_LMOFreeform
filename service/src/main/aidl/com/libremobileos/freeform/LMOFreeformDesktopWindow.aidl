package com.libremobileos.freeform;

/** {@hide} */
@JavaDerive(equals = true, toString = true)
parcelable LMOFreeformDesktopWindow {
    int taskId;
    // Virtual display the app actually runs on.
    int displayId;
    // Physical display the window is drawn on.
    int hostDisplayId;
    boolean minimized;
    String packageName;
    int userId;
}
