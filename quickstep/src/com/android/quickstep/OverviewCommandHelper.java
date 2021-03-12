/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.view.ViewConfiguration;

import androidx.annotation.BinderThread;

import com.android.launcher3.statemanager.StatefulActivity;
import com.android.quickstep.util.ActivityInitListener;
import com.android.quickstep.util.RemoteAnimationProvider;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.LatencyTrackerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

/**
 * Helper class to handle various atomic commands for switching between Overview.
 */
@TargetApi(Build.VERSION_CODES.P)
public class OverviewCommandHelper {

    private final Context mContext;
    private final RecentsAnimationDeviceState mDeviceState;
    private final OverviewComponentObserver mOverviewComponentObserver;

    private long mLastToggleTime;

    public OverviewCommandHelper(Context context, RecentsAnimationDeviceState deviceState,
            OverviewComponentObserver observer) {
        mContext = context;
        mDeviceState = deviceState;
        mOverviewComponentObserver = observer;
    }

    @BinderThread
    public void onOverviewToggle() {
        // If currently screen pinning, do not enter overview
        if (mDeviceState.isScreenPinningActive()) {
            return;
        }

        TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        MAIN_EXECUTOR.execute(new RecentsActivityCommand<>());
    }

    @BinderThread
    public void onOverviewShown(boolean triggeredFromAltTab) {
        if (triggeredFromAltTab) {
            TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        }
        MAIN_EXECUTOR.execute(new ShowRecentsCommand(triggeredFromAltTab));
    }

    @BinderThread
    public void onOverviewHidden() {
        MAIN_EXECUTOR.execute(new HideRecentsCommand());
    }

    private class ShowRecentsCommand extends RecentsActivityCommand {

        private final boolean mTriggeredFromAltTab;

        ShowRecentsCommand(boolean triggeredFromAltTab) {
            mTriggeredFromAltTab = triggeredFromAltTab;
        }

        @Override
        protected boolean handleCommand(long elapsedTime) {
            // TODO: Go to the next page if started from alt-tab.
            return mActivityInterface.getVisibleRecentsView() != null;
        }

        @Override
        protected void onTransitionComplete() {
            // TODO(b/138729100) This doesn't execute first time launcher is run
            if (mTriggeredFromAltTab) {
                RecentsView rv =  mActivityInterface.getVisibleRecentsView();
                if (rv == null) {
                    return;
                }

                // Ensure that recents view has focus so that it receives the followup key inputs
                TaskView taskView = rv.getNextTaskView();
                if (taskView == null) {
                    if (rv.getTaskViewCount() > 0) {
                        taskView = rv.getTaskViewAt(0);
                        taskView.requestFocus();
                    } else {
                        rv.requestFocus();
                    }
                } else {
                    taskView.requestFocus();
                }
            }
        }
    }

    private class HideRecentsCommand extends RecentsActivityCommand {

        @Override
        protected boolean handleCommand(long elapsedTime) {
            RecentsView recents = mActivityInterface.getVisibleRecentsView();
            if (recents == null) {
                return false;
            }
            int currentPage = recents.getNextPage();
            if (currentPage >= 0 && currentPage < recents.getTaskViewCount()) {
                ((TaskView) recents.getPageAt(currentPage)).launchTaskAnimated();
            } else {
                recents.startHome();
            }
            return true;
        }
    }

    private class RecentsActivityCommand<T extends StatefulActivity<?>> implements Runnable {

        private static final String TRANSITION_NAME = "Transition:toOverview";
        protected final BaseActivityInterface<?, T> mActivityInterface;
        private final long mCreateTime;
        private final AppToOverviewAnimationProvider<T> mAnimationProvider;

        private final long mToggleClickedTime = SystemClock.uptimeMillis();
        private ActivityInitListener mListener;

        public RecentsActivityCommand() {
            mActivityInterface = mOverviewComponentObserver.getActivityInterface();
            mCreateTime = SystemClock.elapsedRealtime();
            mAnimationProvider = new AppToOverviewAnimationProvider<>(mActivityInterface,
                    ActivityManagerWrapper.getInstance().getRunningTask(), mDeviceState);

            // Preload the plan
            RecentsModel.INSTANCE.get(mContext).getTasks(null);
        }

        @Override
        public void run() {
            long elapsedTime = mCreateTime - mLastToggleTime;
            mLastToggleTime = mCreateTime;

            if (handleCommand(elapsedTime)) {
                // Command already handled.
                return;
            }

            if (mActivityInterface.switchToRecentsIfVisible(this::onTransitionComplete)) {
                // If successfully switched, then return
                return;
            }

            final T activity = mActivityInterface.getCreatedActivity();
            if (activity != null) {
                InteractionJankMonitorWrapper.begin(
                        activity.getRootView(),
                        InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH);
            }

            // Otherwise, start overview.
            mListener = mActivityInterface.createActivityInitListener(this::onActivityReady);
            mListener.registerAndStartActivity(mOverviewComponentObserver.getOverviewIntent(),
                    new RemoteAnimationProvider() {
                        @Override
                        public AnimatorSet createWindowAnimation(
                                RemoteAnimationTargetCompat[] appTargets,
                                RemoteAnimationTargetCompat[] wallpaperTargets) {
                            return RecentsActivityCommand.this.createWindowAnimation(appTargets,
                                    wallpaperTargets);
                        }
                    }, mContext, MAIN_EXECUTOR.getHandler(),
                    mAnimationProvider.getRecentsLaunchDuration());
        }

        protected boolean handleCommand(long elapsedTime) {
            // TODO: We need to fix this case with PIP, when an activity first enters PIP, it shows
            //       the menu activity which takes window focus, preventing the right condition from
            //       being run below
            RecentsView recents = mActivityInterface.getVisibleRecentsView();
            if (recents != null) {
                // Launch the next task
                recents.showNextTask();
                return true;
            } else if (elapsedTime < ViewConfiguration.getDoubleTapTimeout()) {
                // The user tried to launch back into overview too quickly, either after
                // launching an app, or before overview has actually shown, just ignore for now
                return true;
            }
            return false;
        }

        private boolean onActivityReady(Boolean wasVisible) {
            final T activity = mActivityInterface.getCreatedActivity();
            return mAnimationProvider.onActivityReady(activity, wasVisible);
        }

        private AnimatorSet createWindowAnimation(RemoteAnimationTargetCompat[] appTargets,
                RemoteAnimationTargetCompat[] wallpaperTargets) {
            LatencyTrackerCompat.logToggleRecents(
                    mContext, (int) (SystemClock.uptimeMillis() - mToggleClickedTime));

            mListener.unregister();

            AnimatorSet animatorSet = mAnimationProvider.createWindowAnimation(appTargets,
                    wallpaperTargets);
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    Trace.beginAsyncSection(TRANSITION_NAME, 0);
                    super.onAnimationStart(animation);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    onTransitionComplete();
                    Trace.endAsyncSection(TRANSITION_NAME, 0);
                }
            });
            return animatorSet;
        }

        protected void onTransitionComplete() { }
    }
}
