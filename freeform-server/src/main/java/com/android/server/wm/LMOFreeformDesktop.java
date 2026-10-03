/*
 * SPDX-FileCopyrightText: 2026 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.Display;

/**
 * Routes new-task launches aimed at an external (desktop) display into LMOFreeform windows
 * hosted on that display, instead of letting them start fullscreen there.
 */
public final class LMOFreeformDesktop {
    private static final String TAG = "LMOFreeform/Desktop";
    private static final String PROP_ENABLED = "persist.sys.lmofreeform.desktop";
    // Keys our PendingIntents apart so FLAG_CANCEL_CURRENT never cancels one the app made.
    private static final String SENDER_WHO = "lmofreeform-desktop";

    public interface Launcher {
        /** Called with the WM lock held; must only hand the work off to another thread. */
        void launchOnDesktop(IIntentSender target, ActivityInfo aInfo, int userId,
                int hostDisplayId);
    }

    private static volatile Launcher sLauncher;

    private LMOFreeformDesktop() {}

    public static void setLauncher(Launcher launcher) {
        sLauncher = launcher;
    }

    static boolean interceptLaunch(ActivityTaskManagerService atms, Intent intent,
            String resolvedType, ActivityInfo aInfo, int userId, int callingUid,
            String callingPackage, String callingFeatureId, TaskDisplayArea taskDisplayArea,
            ActivityRecord sourceRecord, ActivityRecord resultRecord, Task inTask,
            TaskFragment inTaskFragment) {
        final Launcher launcher = sLauncher;
        if (launcher == null || aInfo == null || taskDisplayArea == null
                || callingPackage == null) {
            return false;
        }
        // Anything bound to an existing task or expecting a result has to stay where WM puts it.
        if (resultRecord != null || inTask != null || inTaskFragment != null) return false;
        if (!startsNewTask(intent, aInfo, sourceRecord)) return false;
        final DisplayContent dc = taskDisplayArea.mDisplayContent;
        if (dc == null || dc.isPrivate()
                || dc.getDisplayInfo().type != Display.TYPE_EXTERNAL) {
            return false;
        }
        if (ActivityRecord.isHomeIntent(intent) || isExcludedPackage(atms, aInfo, userId)) {
            return false;
        }
        if (!SystemProperties.getBoolean(PROP_ENABLED, true)) return false;

        final Intent launchIntent = new Intent(intent);
        launchIntent.setComponent(new ComponentName(aInfo.packageName, aInfo.name));
        launchIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        // Replayed as the original caller so referrer, calling package and URI grants survive.
        final IIntentSender target = atms.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage, callingFeatureId,
                callingUid, userId, null /* token */, SENDER_WHO, 0 /* requestCode */,
                new Intent[] { launchIntent }, new String[] { resolvedType },
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null /* bOptions */);
        if (target == null) return false;
        Slog.i(TAG, "routing " + launchIntent.getComponent().flattenToShortString()
                + " u" + userId + " from " + callingPackage + " to freeform on display "
                + dc.getDisplayId());
        launcher.launchOnDesktop(target, aInfo, userId, dc.getDisplayId());
        return true;
    }

    // ActivityStarter only adds NEW_TASK to its own launch flags for these, never to the intent.
    private static boolean startsNewTask(Intent intent, ActivityInfo aInfo,
            ActivityRecord sourceRecord) {
        if ((intent.getFlags() & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT)) != 0) {
            return true;
        }
        if (sourceRecord == null) return true;
        return aInfo.launchMode == LAUNCH_SINGLE_TASK
                || aInfo.launchMode == LAUNCH_SINGLE_INSTANCE
                || aInfo.launchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK;
    }

    private static boolean isExcludedPackage(ActivityTaskManagerService atms,
            ActivityInfo aInfo, int userId) {
        final String pkg = aInfo.packageName;
        if ("android".equals(pkg) || pkg.startsWith("com.libremobileos.")) return true;
        final ComponentName sysUi = atms.getSysUiServiceComponentLocked();
        if (sysUi != null && pkg.equals(sysUi.getPackageName())) return true;
        // Home also owns recents and the per-display launcher, which must stay fullscreen.
        final ComponentName home =
                atms.getPackageManagerInternalLocked().getDefaultHomeActivity(userId);
        return home != null && pkg.equals(home.getPackageName());
    }
}
