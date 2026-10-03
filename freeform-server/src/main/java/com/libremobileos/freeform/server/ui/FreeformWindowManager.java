package com.libremobileos.freeform.server.ui;

import static com.libremobileos.freeform.server.Debug.dlog;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Slog;

import java.util.HashMap;

public class FreeformWindowManager {
    private static final HashMap<String, FreeformWindow> freeformWindows = new HashMap<>(1);
    private static final String TAG = "FreeformWindowManager";

    public static void addWindow(
            Handler handler, Context context,
            String packageName, String activityName, int userId, int taskId,
            PendingIntent pendingIntent, int width, int height, int densityDpi) {
        AppConfig appConfig = new AppConfig(packageName, activityName, pendingIntent, userId, taskId);
        FreeformConfig freeformConfig = new FreeformConfig(width, height, densityDpi);
        addWindow(new FreeformWindow(handler, context, appConfig, freeformConfig));
    }

    /**
     * Called in system handler
     */
    public static void addDesktopWindow(Handler handler, Context context, IIntentSender target,
            ActivityInfo aInfo, int userId, int hostDisplayId) {
        registerDisplayListener(handler, context);
        String freeformId = aInfo.packageName + "," + aInfo.name + "," + userId;
        FreeformWindow existing = freeformWindows.get(freeformId);
        if (existing != null && existing.getHostDisplayId() == hostDisplayId) {
            dlog(TAG, "addDesktopWindow: reusing " + freeformId);
            existing.relaunch(target);
            return;
        }
        AppConfig appConfig = new AppConfig(aInfo.packageName, aInfo.name, null, userId, -1,
                target, hostDisplayId);
        // Real size and density are taken from the host display in FreeformWindow.
        FreeformConfig freeformConfig = new FreeformConfig(0, 0, DisplayMetrics.DENSITY_DEFAULT);
        addWindow(new FreeformWindow(handler, context, appConfig, freeformConfig));
    }

    private static void addWindow(FreeformWindow window) {
        dlog(TAG, "addWindow: freeformId=" + window.getFreeformId()
                + ", existing freeformWindows=" + freeformWindows);
        FreeformWindow oldWindow = freeformWindows.get(window.getFreeformId());
        if (oldWindow != null) {
            oldWindow.close();
            oldWindow.destroy("addWindow", false);
        }
        freeformWindows.put(window.getFreeformId(), window);
    }

    public static int countWindowsOn(int hostDisplayId) {
        int count = 0;
        for (FreeformWindow window : freeformWindows.values()) {
            if (window.getHostDisplayId() == hostDisplayId) count++;
        }
        return count;
    }

    /**
     * @param freeformId packageName,activityName,userId
     */
    public static void removeWindow(String freeformId, Boolean close) {
        FreeformWindow removedWindow = freeformWindows.remove(freeformId);
        if (close && removedWindow != null)
            removedWindow.close();
    }

    public static void removeWindow(String freeformId) {
        removeWindow(freeformId, false /*close*/);
    }
}
