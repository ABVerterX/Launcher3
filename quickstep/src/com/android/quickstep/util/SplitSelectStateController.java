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

import static com.android.launcher3.Utilities.postAsyncCallback;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT;

import android.app.ActivityThread;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.view.RemoteAnimationAdapter;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import com.android.launcher3.Utilities;
import com.android.launcher3.util.SplitConfigurationOptions;
import com.android.launcher3.util.SplitConfigurationOptions.StagePosition;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskAnimationManager;
import com.android.quickstep.TaskViewUtils;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.RemoteTransitionCompat;
import com.android.systemui.shared.system.RemoteTransitionRunner;

import java.util.function.Consumer;

/**
 * Represent data needed for the transient state when user has selected one app for split screen
 * and is in the process of either a) selecting a second app or b) exiting intention to invoke split
 */
public class SplitSelectStateController {

    private final Handler mHandler;
    private final SystemUiProxy mSystemUiProxy;
    private @StagePosition int mStagePosition;
    private Task mInitialTask;
    private Task mSecondTask;
    private Rect mInitialBounds;

    public SplitSelectStateController(Handler handler, SystemUiProxy systemUiProxy) {
        mHandler = handler;
        mSystemUiProxy = systemUiProxy;
    }

    /**
     * To be called after first task selected
     */
    public void setInitialTaskSelect(Task taskView, @StagePosition int stagePosition,
            Rect initialBounds) {
        mInitialTask = taskView;
        mStagePosition = stagePosition;
        mInitialBounds = initialBounds;
    }

    /**
     * To be called after second task selected
     */
    public void setSecondTaskId(Task taskView, Consumer<Boolean> callback) {
        mSecondTask = taskView;
        launchTasks(mInitialTask, mSecondTask, mStagePosition, callback);
    }

    /**
     * @param stagePosition representing location of task1
     */
    public void launchTasks(Task task1, Task task2, @StagePosition int stagePosition,
            Consumer<Boolean> callback) {
        // Assume initial task is for top/left part of screen
        final int[] taskIds = stagePosition == STAGE_POSITION_TOP_OR_LEFT
                ? new int[]{task1.key.id, task2.key.id}
                : new int[]{task2.key.id, task1.key.id};
        if (TaskAnimationManager.ENABLE_SHELL_TRANSITIONS) {
            RemoteSplitLaunchTransitionRunner animationRunner =
                    new RemoteSplitLaunchTransitionRunner(task1, task2);
            mSystemUiProxy.startTasks(taskIds[0], null /* mainOptions */, taskIds[1],
                    null /* sideOptions */, STAGE_POSITION_BOTTOM_OR_RIGHT,
                    new RemoteTransitionCompat(animationRunner, MAIN_EXECUTOR));
        } else {
            RemoteSplitLaunchAnimationRunner animationRunner =
                    new RemoteSplitLaunchAnimationRunner(task1, task2, callback);
            final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                    RemoteAnimationAdapterCompat.wrapRemoteAnimationRunner(animationRunner),
                    300, 150,
                    ActivityThread.currentActivityThread().getApplicationThread());

            mSystemUiProxy.startTasksWithLegacyTransition(taskIds[0], null /* mainOptions */,
                    taskIds[1], null /* sideOptions */, STAGE_POSITION_BOTTOM_OR_RIGHT, adapter);
        }
    }

    public @StagePosition int getActiveSplitStagePosition() {
        return mStagePosition;
    }

    /**
     * Requires Shell Transitions
     */
    private class RemoteSplitLaunchTransitionRunner implements RemoteTransitionRunner {

        private final Task mInitialTask;
        private final Task mSecondTask;

        RemoteSplitLaunchTransitionRunner(Task initialTask, Task secondTask) {
            mInitialTask = initialTask;
            mSecondTask = secondTask;
        }

        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, Runnable finishCallback) {
            TaskViewUtils.composeRecentsSplitLaunchAnimator(mInitialTask,
                    mSecondTask, info, t, finishCallback);
            // After successful launch, call resetState
            resetState();
        }
    }

    /**
     * LEGACY
     * Remote animation runner for animation to launch an app.
     */
    private class RemoteSplitLaunchAnimationRunner implements RemoteAnimationRunnerCompat {

        private final Task mInitialTask;
        private final Task mSecondTask;
        private final Consumer<Boolean> mSuccessCallback;

        RemoteSplitLaunchAnimationRunner(Task initialTask, Task secondTask,
                Consumer<Boolean> successCallback) {
            mInitialTask = initialTask;
            mSecondTask = secondTask;
            mSuccessCallback = successCallback;
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTargetCompat[] apps,
                RemoteAnimationTargetCompat[] wallpapers, RemoteAnimationTargetCompat[] nonApps,
                Runnable finishedCallback) {
            postAsyncCallback(mHandler,
                    () -> TaskViewUtils.composeRecentsSplitLaunchAnimatorLegacy(mInitialTask,
                            mSecondTask, apps, wallpapers, nonApps, () -> {
                                finishedCallback.run();
                                if (mSuccessCallback != null) {
                                    mSuccessCallback.accept(true);
                                }
                                resetState();
                            }));
        }

        @Override
        public void onAnimationCancelled() {
            postAsyncCallback(mHandler, () -> {
                if (mSuccessCallback != null) {
                    mSuccessCallback.accept(false);
                }
                resetState();
            });
        }
    }

    /**
     * To be called if split select was cancelled
     */
    public void resetState() {
        mInitialTask = null;
        mSecondTask = null;
        mStagePosition = SplitConfigurationOptions.STAGE_POSITION_UNDEFINED;
        mInitialBounds = null;
    }

    /**
     * @return {@code true} if first task has been selected and waiting for the second task to be
     *         chosen
     */
    public boolean isSplitSelectActive() {
        return mInitialTask != null && mSecondTask == null;
    }

    public Rect getInitialBounds() {
        return mInitialBounds;
    }
}
