/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_KEYBOARD_CLOSED;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.views.WorkEduView;

/**
 * AllAppsContainerView with launcher specific callbacks
 */
public class LauncherAllAppsContainerView extends AllAppsContainerView {

    private final Launcher mLauncher;

    private StateListener<LauncherState> mWorkTabListener;

    public LauncherAllAppsContainerView(Context context) {
        this(context, null);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mLauncher = Launcher.getLauncher(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The AllAppsContainerView houses the QSB and is hence visible from the Workspace
        // Overview states. We shouldn't intercept for the scrubber in these cases.
        if (!mLauncher.isInState(LauncherState.ALL_APPS)) {
            mTouchHandler = null;
            return false;
        }

        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mLauncher.isInState(LauncherState.ALL_APPS)) {
            return false;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void setInsets(Rect insets) {
        super.setInsets(insets);
        mLauncher.getAllAppsController()
                .setScrollRangeDelta(mSearchUiManager.getScrollRangeDelta(insets));
    }

    @Override
    public void setupHeader() {
        super.setupHeader();
        if (mWorkTabListener != null && !mUsingTabs) {
            mLauncher.getStateManager().removeStateListener(mWorkTabListener);
        }
    }

    @Override
    public void onActivePageChanged(int currentActivePage) {
        super.onActivePageChanged(currentActivePage);
        if (mUsingTabs) {
            if (currentActivePage == AdapterHolder.WORK) {
                WorkEduView.showWorkEduIfNeeded(mLauncher);
            } else {
                mWorkTabListener = WorkEduView.showEduFlowIfNeeded(mLauncher, mWorkTabListener);
            }
        }
    }

    @Override
    protected void hideIme() {
        super.hideIme();
        mLauncher.getStatsLogManager().logger().log(LAUNCHER_ALLAPPS_KEYBOARD_CLOSED);
    }
}
