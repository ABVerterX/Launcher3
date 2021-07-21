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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_BOTTOM_TAPPABLE_ELEMENT;
import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_EXTRA_NAVIGATION_BAR;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.R;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.taskbar.contextual.RotationButtonController;
import com.android.launcher3.touch.ItemClickHandler;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.Themes;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.util.ViewCache;
import com.android.launcher3.views.ActivityContext;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.WindowManagerWrapper;

/**
 * The {@link ActivityContext} with which we inflate Taskbar-related Views. This allows UI elements
 * that are used by both Launcher and Taskbar (such as Folder) to reference a generic
 * ActivityContext and BaseDragLayer instead of the Launcher activity and its DragLayer.
 */
public class TaskbarActivityContext extends ContextThemeWrapper implements ActivityContext {

    private static final boolean ENABLE_THREE_BUTTON_TASKBAR =
            SystemProperties.getBoolean("persist.debug.taskbar_three_button", false);
    private static final String TAG = "TaskbarActivityContext";

    private static final String WINDOW_TITLE = "Taskbar";

    private final DeviceProfile mDeviceProfile;
    private final LayoutInflater mLayoutInflater;
    private final TaskbarDragLayer mDragLayer;
    private final TaskbarControllers mControllers;

    private final WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mIsFullscreen;
    // The size we should return to when we call setTaskbarWindowFullscreen(false)
    private int mLastRequestedNonFullscreenHeight;

    private final SysUINavigationMode.Mode mNavMode;
    private final ViewCache mViewCache = new ViewCache();

    private final boolean mIsSafeModeEnabled;
    private boolean mIsDestroyed = false;

    public TaskbarActivityContext(Context windowContext, DeviceProfile dp,
            TaskbarNavButtonController buttonController) {
        super(windowContext, Themes.getActivityThemeRes(windowContext));
        mDeviceProfile = dp;

        mNavMode = SysUINavigationMode.getMode(windowContext);
        mIsSafeModeEnabled = TraceHelper.allowIpcs("isSafeMode",
                () -> getPackageManager().isSafeMode());

        float taskbarIconSize = getResources().getDimension(R.dimen.taskbar_icon_size);
        mDeviceProfile.updateIconSize(1, getResources());
        float iconScale = taskbarIconSize / mDeviceProfile.iconSizePx;
        mDeviceProfile.updateIconSize(iconScale, getResources());

        mLayoutInflater = LayoutInflater.from(this).cloneInContext(this);

        // Inflate views.
        mDragLayer = (TaskbarDragLayer) mLayoutInflater.inflate(
                R.layout.taskbar, null, false);
        TaskbarView taskbarView = mDragLayer.findViewById(R.id.taskbar_view);
        FrameLayout navButtonsView = mDragLayer.findViewById(R.id.navbuttons_view);
        View stashedHandleView = mDragLayer.findViewById(R.id.stashed_handle);

        // Construct controllers.
        mControllers = new TaskbarControllers(this,
                new TaskbarDragController(this),
                buttonController,
                new NavbarButtonsViewController(this, navButtonsView),
                new RotationButtonController(this, R.color.popup_color_primary_light,
                        R.color.popup_color_primary_light),
                new TaskbarDragLayerController(this, mDragLayer),
                new TaskbarViewController(this, taskbarView),
                new TaskbarKeyguardController(this),
                new StashedHandleViewController(this, stashedHandleView),
                new TaskbarStashController(this));

        Display display = windowContext.getDisplay();
        Context c = display.getDisplayId() == Display.DEFAULT_DISPLAY
                ? windowContext.getApplicationContext()
                : windowContext.getApplicationContext().createDisplayContext(display);
        mWindowManager = c.getSystemService(WindowManager.class);
    }

    public void init() {
        mLastRequestedNonFullscreenHeight = mDeviceProfile.taskbarSize;
        mWindowLayoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT,
                mLastRequestedNonFullscreenHeight,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle(WINDOW_TITLE);
        mWindowLayoutParams.packageName = getPackageName();
        mWindowLayoutParams.gravity = Gravity.BOTTOM;
        mWindowLayoutParams.setFitInsetsTypes(0);
        mWindowLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        WindowManagerWrapper wmWrapper = WindowManagerWrapper.getInstance();
        wmWrapper.setProvidesInsetsTypes(
                mWindowLayoutParams,
                new int[] { ITYPE_EXTRA_NAVIGATION_BAR, ITYPE_BOTTOM_TAPPABLE_ELEMENT }
        );

        // Initialize controllers after all are constructed.
        mControllers.init();

        mWindowManager.addView(mDragLayer, mWindowLayoutParams);
    }

    public boolean isThreeButtonNav() {
        return mNavMode == Mode.THREE_BUTTONS;
    }

    @Override
    public LayoutInflater getLayoutInflater() {
        return mLayoutInflater;
    }

    @Override
    public TaskbarDragLayer getDragLayer() {
        return mDragLayer;
    }

    @Override
    public DeviceProfile getDeviceProfile() {
        return mDeviceProfile;
    }

    @Override
    public Rect getFolderBoundingBox() {
        return mControllers.taskbarDragLayerController.getFolderBoundingBox();
    }

    @Override
    public TaskbarDragController getDragController() {
        return mControllers.taskbarDragController;
    }

    @Override
    public ViewCache getViewCache() {
        return mViewCache;
    }

    /**
     * Sets a new data-source for this taskbar instance
     */
    public void setUIController(@NonNull TaskbarUIController uiController) {
        mControllers.uiController.onDestroy();
        mControllers.uiController = uiController;
        mControllers.uiController.init(mControllers);
    }

    /**
     * Called when this instance of taskbar is no longer needed
     */
    public void onDestroy() {
        mIsDestroyed = true;
        setUIController(TaskbarUIController.DEFAULT);
        mControllers.onDestroy();
        mWindowManager.removeViewImmediate(mDragLayer);
    }

    public void updateSysuiStateFlags(int systemUiStateFlags, boolean forceUpdate) {
        mControllers.navbarButtonsViewController.updateStateForSysuiFlags(
                systemUiStateFlags, forceUpdate);
        mControllers.taskbarViewController.setImeIsVisible(
                mControllers.navbarButtonsViewController.isImeVisible());
        mControllers.taskbarKeyguardController.updateStateForSysuiFlags(systemUiStateFlags);
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        mControllers.rotationButtonController.onRotationProposal(rotation, isValid);
    }

    public void disableNavBarElements(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getDisplayId()) {
            return;
        }
        mControllers.rotationButtonController.onDisable2FlagChanged(state2);
        mControllers.taskbarKeyguardController.disableNavbarElements(state1, state2);
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        mControllers.rotationButtonController.onBehaviorChanged(displayId, behavior);
    }

    /**
     * Updates the TaskbarContainer to MATCH_PARENT vs original Taskbar size.
     */
    public void setTaskbarWindowFullscreen(boolean fullscreen) {
        mIsFullscreen = fullscreen;
        setTaskbarWindowHeight(fullscreen ? MATCH_PARENT : mLastRequestedNonFullscreenHeight);
    }

    /**
     * Updates the TaskbarContainer height (pass deviceProfile.taskbarSize to reset).
     */
    public void setTaskbarWindowHeight(int height) {
        if (mWindowLayoutParams.height == height || mIsDestroyed) {
            return;
        }
        if (height != MATCH_PARENT) {
            mLastRequestedNonFullscreenHeight = height;
            if (mIsFullscreen) {
                // We still need to be fullscreen, so defer any change to our height until we call
                // setTaskbarWindowFullscreen(false). For example, this could happen when dragging
                // from the gesture region, as the drag will cancel the gesture and reset launcher's
                // state, which in turn normally would reset the taskbar window height as well.
                return;
            }
        }
        mWindowLayoutParams.height = height;
        mWindowManager.updateViewLayout(mDragLayer, mWindowLayoutParams);
    }

    protected void onTaskbarIconClicked(View view) {
        Object tag = view.getTag();
        if (tag instanceof Task) {
            Task task = (Task) tag;
            ActivityManagerWrapper.getInstance().startActivityFromRecents(task.key,
                    ActivityOptions.makeBasic());
        } else if (tag instanceof FolderInfo) {
            FolderIcon folderIcon = (FolderIcon) view;
            Folder folder = folderIcon.getFolder();
            setTaskbarWindowFullscreen(true);

            getDragLayer().post(() -> {
                folder.animateOpen();

                folder.iterateOverItems((itemInfo, itemView) -> {
                    mControllers.taskbarViewController
                            .setClickAndLongClickListenersForIcon(itemView);
                    // To play haptic when dragging, like other Taskbar items do.
                    itemView.setHapticFeedbackEnabled(true);
                    return false;
                });
            });
        } else if (tag instanceof WorkspaceItemInfo) {
            WorkspaceItemInfo info = (WorkspaceItemInfo) tag;
            if (info.isDisabled()) {
                ItemClickHandler.handleDisabledItemClicked(info, this);
            } else {
                Intent intent = new Intent(info.getIntent())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    if (mIsSafeModeEnabled && !PackageManagerHelper.isSystemApp(this, intent)) {
                        Toast.makeText(this, R.string.safemode_shortcut_error,
                                Toast.LENGTH_SHORT).show();
                    } else  if (info.isPromise()) {
                        intent = new PackageManagerHelper(this)
                                .getMarketIntent(info.getTargetPackage())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);

                    } else if (info.itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                        String id = info.getDeepShortcutId();
                        String packageName = intent.getPackage();
                        getSystemService(LauncherApps.class)
                                .startShortcut(packageName, id, null, null, info.user);
                    } else if (info.user.equals(Process.myUserHandle())) {
                        startActivity(intent);
                    } else {
                        getSystemService(LauncherApps.class).startMainActivity(
                                intent.getComponent(), info.user, intent.getSourceBounds(), null);
                    }
                } catch (NullPointerException | ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT)
                            .show();
                    Log.e(TAG, "Unable to launch. tag=" + info + " intent=" + intent, e);
                }
            }
        } else {
            Log.e(TAG, "Unknown type clicked: " + tag);
        }

        AbstractFloatingView.closeAllOpenViews(this);
    }

    /**
     * Called when we detect a long press in the nav region before passing the gesture slop.
     * @return Whether taskbar handled the long press, and thus should cancel the gesture.
     */
    public boolean onLongPressToUnstashTaskbar() {
        return mControllers.taskbarStashController.onLongPressToUnstashTaskbar();
    }

    /**
     * Called when we detect a motion down or up/cancel in the nav region while stashed.
     * @param animateForward Whether to animate towards the unstashed hint state or back to stashed.
     */
    public void startTaskbarUnstashHint(boolean animateForward) {
        mControllers.taskbarStashController.startUnstashHint(animateForward);
    }
}
