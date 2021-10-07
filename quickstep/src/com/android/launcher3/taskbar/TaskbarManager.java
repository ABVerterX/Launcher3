/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.taskbar;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.launcher3.util.DisplayController.CHANGE_ACTIVE_SCREEN;
import static com.android.launcher3.util.DisplayController.CHANGE_DENSITY;
import static com.android.launcher3.util.DisplayController.CHANGE_SUPPORTED_BOUNDS;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.BaseQuickstepLauncher;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.DisplayController.Info;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.SimpleBroadcastReceiver;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TouchInteractionService;
import com.android.quickstep.util.ScopedUnfoldTransitionProgressProvider;

/**
 * Class to manage taskbar lifecycle
 */
public class TaskbarManager implements DisplayController.DisplayInfoChangeListener,
        SysUINavigationMode.NavigationModeChangeListener {

    private static final Uri USER_SETUP_COMPLETE_URI = Settings.Secure.getUriFor(
            Settings.Secure.USER_SETUP_COMPLETE);

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final SysUINavigationMode mSysUINavigationMode;
    private final TaskbarNavButtonController mNavButtonController;
    private final SettingsCache.OnChangeListener mUserSetupCompleteListener;
    private final ComponentCallbacks mComponentCallbacks;
    private final SimpleBroadcastReceiver mShutdownReceiver;

    // The source for this provider is set when Launcher is available
    private final ScopedUnfoldTransitionProgressProvider mUnfoldProgressProvider =
            new ScopedUnfoldTransitionProgressProvider();

    private TaskbarActivityContext mTaskbarActivityContext;
    private BaseQuickstepLauncher mLauncher;
    /**
     * Cache a copy here so we can initialize state whenever taskbar is recreated, since
     * this class does not get re-initialized w/ new taskbars.
     */
    private int mSysuiStateFlags;

    private static final int CHANGE_FLAGS =
            CHANGE_ACTIVE_SCREEN | CHANGE_DENSITY | CHANGE_SUPPORTED_BOUNDS;

    private boolean mUserUnlocked = false;

    public TaskbarManager(TouchInteractionService service) {
        mDisplayController = DisplayController.INSTANCE.get(service);
        mSysUINavigationMode = SysUINavigationMode.INSTANCE.get(service);
        Display display =
                service.getSystemService(DisplayManager.class).getDisplay(DEFAULT_DISPLAY);
        mContext = service.createWindowContext(display, TYPE_APPLICATION_OVERLAY, null);
        mNavButtonController = new TaskbarNavButtonController(service);
        mUserSetupCompleteListener = isUserSetupComplete -> recreateTaskbar();
        mComponentCallbacks = new ComponentCallbacks() {
            private Configuration mOldConfig = mContext.getResources().getConfiguration();

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                int configDiff = mOldConfig.diff(newConfig);
                int configsRequiringRecreate = ActivityInfo.CONFIG_ASSETS_PATHS
                        | ActivityInfo.CONFIG_LAYOUT_DIRECTION;
                if ((configDiff & configsRequiringRecreate) != 0) {
                    // Color has changed, recreate taskbar to reload background color & icons.
                    recreateTaskbar();
                }
                mOldConfig = newConfig;
            }

            @Override
            public void onLowMemory() { }
        };
        mShutdownReceiver = new SimpleBroadcastReceiver(i -> destroyExistingTaskbar());

        mDisplayController.addChangeListener(this);
        mSysUINavigationMode.addModeChangeListener(this);
        SettingsCache.INSTANCE.get(mContext).register(USER_SETUP_COMPLETE_URI,
                mUserSetupCompleteListener);
        mContext.registerComponentCallbacks(mComponentCallbacks);
        mShutdownReceiver.register(mContext, Intent.ACTION_SHUTDOWN);

        recreateTaskbar();
    }

    @Override
    public void onNavigationModeChanged(Mode newMode) {
        recreateTaskbar();
    }

    @Override
    public void onDisplayInfoChanged(Context context, Info info, int flags) {
        if ((flags & CHANGE_FLAGS) != 0) {
            recreateTaskbar();
        }
    }

    private void destroyExistingTaskbar() {
        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.onDestroy();
            mTaskbarActivityContext = null;
        }
    }

    /**
     * Called when the user is unlocked
     */
    public void onUserUnlocked() {
        mUserUnlocked = true;
        recreateTaskbar();
    }

    /**
     * Sets a launcher to act as taskbar callback
     */
    public void setLauncher(@NonNull BaseQuickstepLauncher launcher) {
        mLauncher = launcher;
        mUnfoldProgressProvider.setSourceProvider(launcher
                .getUnfoldTransitionProgressProvider());

        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.setUIController(
                    new LauncherTaskbarUIController(launcher, mTaskbarActivityContext));
        }
    }

    /**
     * Clears a previously set Launcher
     */
    public void clearLauncher(@NonNull BaseQuickstepLauncher launcher) {
        if (mLauncher == launcher) {
            mLauncher = null;
            if (mTaskbarActivityContext != null) {
                mTaskbarActivityContext.setUIController(TaskbarUIController.DEFAULT);
            }
            mUnfoldProgressProvider.setSourceProvider(null);
        }
    }

    private void recreateTaskbar() {
        destroyExistingTaskbar();

        DeviceProfile dp =
                mUserUnlocked ? LauncherAppState.getIDP(mContext).getDeviceProfile(mContext) : null;

        boolean isTaskBarEnabled =
                FeatureFlags.ENABLE_TASKBAR.get() && dp != null && dp.isTaskbarPresent;

        if (!isTaskBarEnabled) {
            SystemUiProxy.INSTANCE.get(mContext)
                    .notifyTaskbarStatus(/* visible */ false, /* stashed */ false);
            return;
        }

        mTaskbarActivityContext = new TaskbarActivityContext(mContext, dp.copy(mContext),
                mNavButtonController, mUnfoldProgressProvider);
        mTaskbarActivityContext.init();
        if (mLauncher != null) {
            mTaskbarActivityContext.setUIController(
                    new LauncherTaskbarUIController(mLauncher, mTaskbarActivityContext));
        }
        onSysuiFlagsChangedInternal(mSysuiStateFlags, true /* forceUpdate */);
    }

    public void onSystemUiFlagsChanged(int systemUiStateFlags) {
        onSysuiFlagsChangedInternal(systemUiStateFlags, false /* forceUpdate */);
    }

    private void onSysuiFlagsChangedInternal(int systemUiStateFlags, boolean forceUpdate) {
        mSysuiStateFlags = systemUiStateFlags;
        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.updateSysuiStateFlags(systemUiStateFlags, forceUpdate);
        }
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.onRotationProposal(rotation, isValid);
        }
    }

    public void disableNavBarElements(int displayId, int state1, int state2, boolean animate) {
        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.disableNavBarElements(displayId, state1, state2, animate);
        }
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        if (mTaskbarActivityContext != null) {
            mTaskbarActivityContext.onSystemBarAttributesChanged(displayId, behavior);
        }
    }

    /**
     * Called when the manager is no longer needed
     */
    public void destroy() {
        destroyExistingTaskbar();
        mDisplayController.removeChangeListener(this);
        mSysUINavigationMode.removeModeChangeListener(this);
        SettingsCache.INSTANCE.get(mContext).unregister(USER_SETUP_COMPLETE_URI,
                mUserSetupCompleteListener);
        mContext.unregisterComponentCallbacks(mComponentCallbacks);
        mContext.unregisterReceiver(mShutdownReceiver);
    }

    public @Nullable TaskbarActivityContext getCurrentActivityContext() {
        return mTaskbarActivityContext;
    }
}
