package com.libremobileos.freeform;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.view.InputEvent;
import android.view.Surface;
import android.os.IBinder;
import com.libremobileos.freeform.ILMOFreeformDesktopListener;
import com.libremobileos.freeform.ILMOFreeformDisplayCallback;
import com.libremobileos.freeform.LMOFreeformDesktopWindow;

/** {@hide} */
@PermissionManuallyEnforced
interface ILMOFreeformUIService {
    // start freeform in system
    void startAppInFreeform(String packageName, String activityName, int userId, int taskId,
                       in PendingIntent pendingIntent, int width, int height, int densityDpi) = 0;
    // remove freeform by freeformId: packageName,activityName,userId
    void removeFreeform(String freeformId) = 1;
    // create freeform in user
    void createFreeformInUser(String name, int width, int height, int densityDpi, float refreshRate,
                       long presentationDeadlineNanos, boolean secure, boolean ownContentOnly,
                       boolean shouldShowSystemDecorations, in Surface surface,
                       ILMOFreeformDisplayCallback callback) = 2;
    void resizeFreeform(IBinder appToken, int width, int height, int densityDpi) = 3;
    void releaseFreeform(IBinder appToken) = 4;
    boolean ping() = 5;

    // Desktop windows hosted on external displays; callers need MANAGE_ACTIVITY_TASKS.
    LMOFreeformDesktopWindow[] getDesktopWindows() = 6;
    void registerDesktopListener(ILMOFreeformDesktopListener listener) = 7;
    void unregisterDesktopListener(ILMOFreeformDesktopListener listener) = 8;
    // Restores a minimized window, raises a background one, or minimizes the front one.
    void toggleDesktopWindow(int taskId) = 9;
}
