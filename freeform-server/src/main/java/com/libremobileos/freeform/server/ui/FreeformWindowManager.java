package com.libremobileos.freeform.server.ui;

import static com.libremobileos.freeform.server.Debug.dlog;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;

import com.libremobileos.freeform.ILMOFreeformDesktopListener;
import com.libremobileos.freeform.LMOFreeformDesktopWindow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class FreeformWindowManager {
    private static final HashMap<String, FreeformWindow> freeformWindows = new HashMap<>(1);
    private static final ArrayMap<Integer, FreeformWindow> topWindows = new ArrayMap<>();
    private static final String TAG = "FreeformWindowManager";
    private static boolean displayListenerRegistered = false;
    // Windows whose host display vanished, waiting for a desktop display to come back.
    private static final ArrayList<FreeformWindow> parkedWindows = new ArrayList<>();
    // Long enough to cover a flaky cable and answering the mirror/desktop prompt.
    private static final long PARK_TIMEOUT_MS = 60_000;
    private static final Runnable parkTimeout = FreeformWindowManager::unparkToDefaultDisplay;
    private static final RemoteCallbackList<ILMOFreeformDesktopListener> desktopListeners =
            new RemoteCallbackList<>();
    // Rebuilt on the system handler, read from binder threads.
    private static volatile LMOFreeformDesktopWindow[] desktopSnapshot =
            new LMOFreeformDesktopWindow[0];

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
        topWindows.put(window.getHostDisplayId(), window);
    }

    /**
     * Called in system handler
     */
    public static void raiseWindow(FreeformWindow window) {
        if (!window.isDesktop() || topWindows.get(window.getHostDisplayId()) == window) return;
        window.raise();
        topWindows.put(window.getHostDisplayId(), window);
    }

    /**
     * Called in system handler
     */
    public static void onWindowMinimized(FreeformWindow window) {
        if (topWindows.get(window.getHostDisplayId()) == window) {
            topWindows.remove(window.getHostDisplayId());
        }
        notifyDesktopChanged();
    }

    /**
     * Called in system handler
     */
    public static void onWindowRestored(FreeformWindow window) {
        topWindows.put(window.getHostDisplayId(), window);
        notifyDesktopChanged();
    }

    /**
     * Called in system handler
     */
    public static void toggleDesktopWindow(int taskId) {
        for (FreeformWindow window : freeformWindows.values()) {
            if (!window.isDesktop() || window.getTaskId() != taskId) continue;
            if (window.getMinimized()) {
                raiseWindow(window);
            } else if (topWindows.get(window.getHostDisplayId()) == window) {
                window.minimize();
            } else {
                raiseWindow(window);
            }
            return;
        }
    }

    public static LMOFreeformDesktopWindow[] getDesktopWindows() {
        return desktopSnapshot;
    }

    public static void registerDesktopListener(ILMOFreeformDesktopListener listener) {
        desktopListeners.register(listener);
    }

    public static void unregisterDesktopListener(ILMOFreeformDesktopListener listener) {
        desktopListeners.unregister(listener);
    }

    /**
     * Called in system handler
     */
    public static void notifyDesktopChanged() {
        ArrayList<LMOFreeformDesktopWindow> windows = new ArrayList<>();
        for (FreeformWindow window : freeformWindows.values()) {
            // Until the task and display are known there is nothing a launcher could act on.
            if (!window.isDesktop() || window.getTaskId() == -1 || window.getDisplayId() < 0) {
                continue;
            }
            LMOFreeformDesktopWindow info = new LMOFreeformDesktopWindow();
            info.taskId = window.getTaskId();
            info.displayId = window.getDisplayId();
            info.hostDisplayId = window.getHostDisplayId();
            info.minimized = window.getMinimized();
            info.packageName = window.getPackageName();
            info.userId = window.getUserId();
            windows.add(info);
        }
        windows.sort((a, b) -> Integer.compare(a.taskId, b.taskId));
        LMOFreeformDesktopWindow[] snapshot = windows.toArray(new LMOFreeformDesktopWindow[0]);
        if (Arrays.equals(snapshot, desktopSnapshot)) return;
        desktopSnapshot = snapshot;
        int count = desktopListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                desktopListeners.getBroadcastItem(i).onDesktopWindowsChanged();
            } catch (RemoteException ignored) {
            }
        }
        desktopListeners.finishBroadcast();
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
        if (removedWindow != null
                && topWindows.get(removedWindow.getHostDisplayId()) == removedWindow) {
            topWindows.remove(removedWindow.getHostDisplayId());
        }
        if (close && removedWindow != null)
            removedWindow.close();
        if (removedWindow != null && removedWindow.isDesktop()) notifyDesktopChanged();
    }

    public static void removeWindow(String freeformId) {
        removeWindow(freeformId, false /*close*/);
    }

    /** Drops the mapping only if it still belongs to {@code window}, not a replacement. */
    public static void removeWindow(FreeformWindow window) {
        if (freeformWindows.get(window.getFreeformId()) == window) {
            removeWindow(window.getFreeformId());
        }
        parkedWindows.remove(window);
    }

    private static void registerDisplayListener(Handler handler, Context context) {
        if (displayListenerRegistered) return;
        displayListenerRegistered = true;
        context.getSystemService(DisplayManager.class).registerDisplayListener(
                new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                        rehostParked(handler, context, displayId);
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        rehostParked(handler, context, displayId);
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                        // Moving tasks recreates their activities, which some apps don't survive,
                        // so a brief dropout only parks them.
                        for (FreeformWindow window : new ArrayList<>(freeformWindows.values())) {
                            if (window.getHostDisplayId() == displayId && !window.getParked()) {
                                Slog.i(TAG, "host display " + displayId + " removed, parking "
                                        + window.getFreeformId());
                                window.park();
                                parkedWindows.add(window);
                            }
                        }
                        topWindows.remove(displayId);
                        if (!parkedWindows.isEmpty()) {
                            handler.removeCallbacks(parkTimeout);
                            handler.postDelayed(parkTimeout, PARK_TIMEOUT_MS);
                        }
                    }
                }, handler);
    }

    /**
     * Called in system handler
     */
    private static void rehostParked(Handler handler, Context context, int displayId) {
        if (parkedWindows.isEmpty()) return;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        // Only a desktop can take them back; a mirroring display hosts no tasks.
        if (display == null || display.getType() != Display.TYPE_EXTERNAL
                || !display.canHostTasks()) {
            return;
        }
        handler.removeCallbacks(parkTimeout);
        for (FreeformWindow old : new ArrayList<>(parkedWindows)) {
            parkedWindows.remove(old);
            int taskId = old.getTaskId();
            if (taskId == -1) {
                old.destroy("rehost: no task", false);
                continue;
            }
            Slog.i(TAG, "rehosting " + old.getFreeformId() + " on display " + displayId);
            // The old window lets go once its task moves into the replacement's display.
            if (freeformWindows.get(old.getFreeformId()) == old) {
                freeformWindows.remove(old.getFreeformId());
            }
            AppConfig appConfig = new AppConfig(old.getPackageName(), old.getActivityName(), null,
                    old.getUserId(), taskId, null, displayId);
            // Same size keeps the app from being recreated for a configuration change.
            FreeformConfig freeformConfig = new FreeformConfig(old.getFreeformConfig().getWidth(),
                    old.getFreeformConfig().getHeight(), DisplayMetrics.DENSITY_DEFAULT);
            addWindow(new FreeformWindow(handler, context, appConfig, freeformConfig));
        }
    }

    private static void unparkToDefaultDisplay() {
        for (FreeformWindow window : new ArrayList<>(parkedWindows)) {
            Slog.i(TAG, "no desktop came back, moving " + window.getFreeformId() + " to default");
            window.moveToDefaultDisplay();
        }
        parkedWindows.clear();
    }
}
