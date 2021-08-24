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

import static com.android.launcher3.AbstractFloatingView.TYPE_ALL;
import static com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo.TOUCHABLE_INSETS_CONTENT;
import static com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo.TOUCHABLE_INSETS_FRAME;
import static com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo.TOUCHABLE_INSETS_REGION;

import android.content.res.Resources;
import android.graphics.Rect;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.R;
import com.android.launcher3.anim.AlphaUpdateListener;
import com.android.quickstep.AnimatedFloat;
import com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo;

/**
 * Handles properties/data collection, then passes the results to TaskbarDragLayer to render.
 */
public class TaskbarDragLayerController {

    private final TaskbarActivityContext mActivity;
    private final TaskbarDragLayer mTaskbarDragLayer;
    private final int mFolderMargin;

    // Alpha properties for taskbar background.
    private final AnimatedFloat mBgTaskbar = new AnimatedFloat(this::updateBackgroundAlpha);
    private final AnimatedFloat mBgNavbar = new AnimatedFloat(this::updateBackgroundAlpha);
    private final AnimatedFloat mKeyguardBgTaskbar = new AnimatedFloat(this::updateBackgroundAlpha);
    // Used to hide our background color when someone else (e.g. ScrimView) is handling it.
    private final AnimatedFloat mBgOverride = new AnimatedFloat(this::updateBackgroundAlpha);

    // Translation property for taskbar background.
    private final AnimatedFloat mBgOffset = new AnimatedFloat(this::updateBackgroundOffset);

    // Initialized in init.
    private TaskbarControllers mControllers;

    public TaskbarDragLayerController(TaskbarActivityContext activity,
            TaskbarDragLayer taskbarDragLayer) {
        mActivity = activity;
        mTaskbarDragLayer = taskbarDragLayer;
        final Resources resources = mTaskbarDragLayer.getResources();
        mFolderMargin = resources.getDimensionPixelSize(R.dimen.taskbar_folder_margin);
    }

    public void init(TaskbarControllers controllers) {
        mControllers = controllers;
        mTaskbarDragLayer.init(new TaskbarDragLayerCallbacks());
        mKeyguardBgTaskbar.value = 1;
        mBgOverride.value = 1;
    }

    public void onDestroy() {
        mTaskbarDragLayer.onDestroy();
    }

    /**
     * @return Bounds (in TaskbarDragLayer coordinates) where an opened Folder can display.
     */
    public Rect getFolderBoundingBox() {
        Rect boundingBox = new Rect(0, 0, mTaskbarDragLayer.getWidth(),
                mTaskbarDragLayer.getHeight() - mActivity.getDeviceProfile().taskbarSize);
        boundingBox.inset(mFolderMargin, mFolderMargin);
        return boundingBox;
    }

    public AnimatedFloat getTaskbarBackgroundAlpha() {
        return mBgTaskbar;
    }

    public AnimatedFloat getNavbarBackgroundAlpha() {
        return mBgNavbar;
    }

    public AnimatedFloat getKeyguardBgTaskbar() {
        return mKeyguardBgTaskbar;
    }

    public AnimatedFloat getOverrideBackgroundAlpha() {
        return mBgOverride;
    }

    public AnimatedFloat getTaskbarBackgroundOffset() {
        return mBgOffset;
    }

    private void updateBackgroundAlpha() {
        final float bgNavbar = mBgNavbar.value;
        final float bgTaskbar = mBgTaskbar.value * mKeyguardBgTaskbar.value;
        mTaskbarDragLayer.setTaskbarBackgroundAlpha(
                mBgOverride.value * Math.max(bgNavbar, bgTaskbar)
        );
    }

    private void updateBackgroundOffset() {
        mTaskbarDragLayer.setTaskbarBackgroundOffset(mBgOffset.value);
    }

    /**
     * Callbacks for {@link TaskbarDragLayer} to interact with its controller.
     */
    public class TaskbarDragLayerCallbacks {

        /**
         * Called to update the touchable insets.
         * @see InsetsInfo#setTouchableInsets(int)
         */
        public void updateInsetsTouchability(InsetsInfo insetsInfo) {
            insetsInfo.touchableRegion.setEmpty();
            if (mActivity.isThreeButtonNav()) {
                // Always have nav buttons be touchable
                mControllers.navbarButtonsViewController.addVisibleButtonsRegion(
                        mTaskbarDragLayer, insetsInfo.touchableRegion);
            }

            if (mTaskbarDragLayer.getAlpha() < AlphaUpdateListener.ALPHA_CUTOFF_THRESHOLD) {
                // Let touches pass through us.
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION);
            } else if (mControllers.navbarButtonsViewController.isImeVisible()) {
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_CONTENT);
            } else if (!mControllers.uiController.isTaskbarTouchable()) {
                // Let touches pass through us.
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION);
            } else if (mControllers.taskbarViewController.areIconsVisible()) {
                // Buttons are visible, take over the full taskbar area
                insetsInfo.setTouchableInsets(mActivity.isTaskbarWindowFullscreen()
                        ? TOUCHABLE_INSETS_FRAME : TOUCHABLE_INSETS_CONTENT);
            } else {
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION);
            }
        }

        /**
         * Called to update the {@link InsetsInfo#contentInsets}.
         */
        public void updateContentInsets(Rect outContentInsets) {
            mControllers.uiController.updateContentInsets(outContentInsets);
        }

        /**
         * Called when a child is removed from TaskbarDragLayer.
         */
        public void onDragLayerViewRemoved() {
            if (AbstractFloatingView.getOpenView(mActivity, TYPE_ALL) == null) {
                mActivity.setTaskbarWindowFullscreen(false);
            }
        }

        /**
         * Returns how tall the background should be drawn at the bottom of the screen.
         */
        public int getTaskbarBackgroundHeight() {
            return mActivity.getDeviceProfile().taskbarSize;
        }
    }
}
