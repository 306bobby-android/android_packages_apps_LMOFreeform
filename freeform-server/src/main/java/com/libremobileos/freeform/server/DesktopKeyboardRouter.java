/*
 * SPDX-FileCopyrightText: 2026 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.libremobileos.freeform.server;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.InputDevice;

import java.util.Objects;

/**
 * Ties external keyboards to the desktop display while one is connected. The phone display then
 * reports no keyboard and keeps its soft keyboard, and desktop typing never lands in phone apps.
 * LMOFreeform windows forward the keys they receive into their app's display.
 */
public class DesktopKeyboardRouter
        implements DisplayManager.DisplayListener, InputManager.InputDeviceListener {
    private static final String TAG = "LMOFreeform/DesktopKeyboardRouter";
    private static final String PROP_ENABLED = "persist.sys.lmofreeform.desktop";

    private final DisplayManager mDisplayManager;
    private final InputManager mInputManager;
    private final ArraySet<String> mAssociated = new ArraySet<>();
    private String mAssociatedDisplayUniqueId;

    public DesktopKeyboardRouter(Context context, Handler handler) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mInputManager = context.getSystemService(InputManager.class);
        mDisplayManager.registerDisplayListener(this, handler);
        mInputManager.registerInputDeviceListener(this, handler);
        handler.post(this::update);
    }

    private void update() {
        final Display desktop = SystemProperties.getBoolean(PROP_ENABLED, true)
                ? findDesktopDisplay() : null;
        final String uniqueId = desktop != null ? desktop.getUniqueId() : null;
        final ArraySet<String> wanted = new ArraySet<>();
        if (uniqueId != null) {
            for (int id : mInputManager.getInputDeviceIds()) {
                final InputDevice device = mInputManager.getInputDevice(id);
                if (device != null && isDesktopKeyboard(device)) {
                    wanted.add(device.getDescriptor());
                }
            }
        }
        final boolean displayChanged = !Objects.equals(uniqueId, mAssociatedDisplayUniqueId);
        for (String descriptor : new ArraySet<>(mAssociated)) {
            if (displayChanged || !wanted.contains(descriptor)) {
                removeAssociation(descriptor);
            }
        }
        for (String descriptor : wanted) {
            if (!mAssociated.contains(descriptor)) {
                addAssociation(descriptor, uniqueId);
            }
        }
        mAssociatedDisplayUniqueId = uniqueId;
    }

    private Display findDesktopDisplay() {
        for (Display display : mDisplayManager.getDisplays()) {
            // A mirroring display can't host tasks; only a desktop takes the keyboard.
            if (display.getType() == Display.TYPE_EXTERNAL && display.canHostTasks()) {
                return display;
            }
        }
        return null;
    }

    private static boolean isDesktopKeyboard(InputDevice device) {
        return !device.isVirtual() && device.isExternal()
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private void addAssociation(String descriptor, String displayUniqueId) {
        try {
            SystemServiceHolder.inputManagerService.addUniqueIdAssociationByDescriptor(
                    descriptor, displayUniqueId);
            mAssociated.add(descriptor);
            Slog.i(TAG, "keyboard " + descriptor + " -> " + displayUniqueId);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to associate keyboard " + descriptor, e);
        }
    }

    private void removeAssociation(String descriptor) {
        try {
            SystemServiceHolder.inputManagerService.removeUniqueIdAssociationByDescriptor(
                    descriptor);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to release keyboard " + descriptor, e);
        }
        mAssociated.remove(descriptor);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        update();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        update();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        update();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        update();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        update();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        update();
    }
}
