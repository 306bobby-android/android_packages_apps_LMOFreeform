package com.libremobileos.freeform.server.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.IIntentSender
import android.view.Display

data class AppConfig @JvmOverloads constructor(
    val packageName: String,
    val activityName: String,
    val pendingIntent: PendingIntent?,
    val userId: Int,
    val taskId: Int,
    val intentSender: IIntentSender? = null,
    val hostDisplayId: Int = Display.DEFAULT_DISPLAY
)
