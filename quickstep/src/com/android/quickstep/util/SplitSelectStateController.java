/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.quickstep.util;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT;

import android.app.ActivityThread;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.RemoteAnimationAdapter;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.R;
import com.android.launcher3.util.SplitConfigurationOptions.SplitPositionOption;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskAnimationManager;
import com.android.quickstep.TaskViewUtils;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.RemoteTransitionCompat;
import com.android.systemui.shared.system.RemoteTransitionRunner;

/**
 * Represent data needed for the transient state when user has selected one app for split screen
 * and is in the process of either a) selecting a second app or b) exiting intention to invoke split
 */
public class SplitSelectStateController {

    private final SystemUiProxy mSystemUiProxy;
    private TaskView mInitialTaskView;
    private TaskView mSecondTaskView;
    private SplitPositionOption mInitialPosition;
    private Rect mInitialBounds;
    private final Handler mHandler;

    public SplitSelectStateController(Handler handler, SystemUiProxy systemUiProxy) {
        mSystemUiProxy = systemUiProxy;
        mHandler = handler;
    }

    /**
     * To be called after first task selected
     */
    public void setInitialTaskSelect(TaskView taskView, SplitPositionOption positionOption,
            Rect initialBounds) {
        mInitialTaskView = taskView;
        mInitialPosition = positionOption;
        mInitialBounds = initialBounds;
    }

    /**
     * To be called after second task selected
     */
    public void setSecondTaskId(TaskView taskView) {
        mSecondTaskView = taskView;
        // Assume initial task is for top/left part of screen

        final int[] taskIds = mInitialPosition.stagePosition == STAGE_POSITION_TOP_OR_LEFT
                ? new int[]{mInitialTaskView.getTask().key.id, taskView.getTask().key.id}
                : new int[]{taskView.getTask().key.id, mInitialTaskView.getTask().key.id};
        if (TaskAnimationManager.ENABLE_SHELL_TRANSITIONS) {
            RemoteSplitLaunchTransitionRunner animationRunner =
                    new RemoteSplitLaunchTransitionRunner(mInitialTaskView, taskView);
            mSystemUiProxy.startTasks(taskIds[0], null /* mainOptions */, taskIds[1],
                    null /* sideOptions */, STAGE_POSITION_BOTTOM_OR_RIGHT,
                    new RemoteTransitionCompat(animationRunner, MAIN_EXECUTOR));
        } else {
            RemoteSplitLaunchAnimationRunner animationRunner =
                    new RemoteSplitLaunchAnimationRunner(mInitialTaskView, taskView);
            final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                    RemoteAnimationAdapterCompat.wrapRemoteAnimationRunner(animationRunner),
                    300, 150,
                    ActivityThread.currentActivityThread().getApplicationThread());

            mSystemUiProxy.startTasksWithLegacyTransition(taskIds[0], null /* mainOptions */,
                    taskIds[1], null /* sideOptions */, STAGE_POSITION_BOTTOM_OR_RIGHT, adapter);
        }
    }

    /**
     * @return {@link InsettableFrameLayout.LayoutParams} to correctly position the
     * split placeholder view
     */
    public InsettableFrameLayout.LayoutParams getLayoutParamsForActivePosition(Resources resources,
            DeviceProfile deviceProfile) {
        InsettableFrameLayout.LayoutParams params =
                new InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        boolean topLeftPosition = mInitialPosition.stagePosition == STAGE_POSITION_TOP_OR_LEFT;
        if (deviceProfile.isLandscape) {
            params.width = (int) resources.getDimension(R.dimen.split_placeholder_size);
            params.gravity = topLeftPosition ? Gravity.START : Gravity.END;
        } else {
            params.height = (int) resources.getDimension(R.dimen.split_placeholder_size);
            params.gravity = Gravity.TOP;
        }

        return params;
    }

    @Nullable
    public SplitPositionOption getActiveSplitPositionOption() {
        return mInitialPosition;
    }

    /**
     * Requires Shell Transitions
     */
    private class RemoteSplitLaunchTransitionRunner implements RemoteTransitionRunner {

        private final TaskView mInitialTaskView;
        private final TaskView mTaskView;

        RemoteSplitLaunchTransitionRunner(TaskView initialTaskView, TaskView taskView) {
            mInitialTaskView = initialTaskView;
            mTaskView = taskView;
        }

        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, Runnable finishCallback) {
            TaskViewUtils.composeRecentsSplitLaunchAnimator(mInitialTaskView, mTaskView,
                    info, t, finishCallback);
            // After successful launch, call resetState
            resetState();
        }
    }

    /**
     * LEGACY
     * Remote animation runner for animation to launch an app.
     */
    private class RemoteSplitLaunchAnimationRunner implements RemoteAnimationRunnerCompat {

        private final TaskView mInitialTaskView;
        private final TaskView mTaskView;

        RemoteSplitLaunchAnimationRunner(TaskView initialTaskView, TaskView taskView) {
            mInitialTaskView = initialTaskView;
            mTaskView = taskView;
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTargetCompat[] apps,
                RemoteAnimationTargetCompat[] wallpapers, RemoteAnimationTargetCompat[] nonApps,
                Runnable finishedCallback) {
            TaskViewUtils.composeRecentsSplitLaunchAnimatorLegacy(mInitialTaskView, mTaskView, apps,
                    wallpapers, nonApps, finishedCallback);
            // After successful launch, call resetState
            resetState();
        }

        @Override
        public void onAnimationCancelled() {
            resetState();
        }
    }

    /**
     * To be called if split select was cancelled
     */
    public void resetState() {
        mInitialTaskView = null;
        mSecondTaskView = null;
        mInitialPosition = null;
        mInitialBounds = null;
    }

    /**
     * @return {@code true} if first task has been selected and waiting for the second task to be
     *         chosen
     */
    public boolean isSplitSelectActive() {
        return mInitialTaskView != null && mSecondTaskView == null;
    }

    public Rect getInitialBounds() {
        return mInitialBounds;
    }
}
