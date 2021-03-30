/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.launcher3;

import static com.android.launcher3.AbstractFloatingView.TYPE_ALL;
import static com.android.launcher3.AbstractFloatingView.TYPE_HIDE_BACK_BUTTON;
import static com.android.launcher3.LauncherState.FLAG_HIDE_BACK_BUTTON;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.util.DisplayController.DisplayHolder.CHANGE_SIZE;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.quickstep.SysUINavigationMode.Mode.TWO_BUTTONS;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.model.WellbeingModel;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.proxy.ProxyActivityStarter;
import com.android.launcher3.proxy.StartActivityParams;
import com.android.launcher3.statehandlers.BackButtonAlphaHandler;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.taskbar.TaskbarActivityContext;
import com.android.launcher3.taskbar.TaskbarController;
import com.android.launcher3.taskbar.TaskbarStateHandler;
import com.android.launcher3.taskbar.TaskbarView;
import com.android.launcher3.uioverrides.RecentsViewStateController;
import com.android.launcher3.util.ActivityOptionsWrapper;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.UiThreadHelper;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.quickstep.SysUINavigationMode.NavigationModeChangeListener;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.util.RemoteAnimationProvider;
import com.android.quickstep.util.RemoteFadeOutAnimationListener;
import com.android.quickstep.util.SplitSelectStateController;
import com.android.quickstep.views.OverviewActionsView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.SplitPlaceholderView;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

import java.util.List;
import java.util.stream.Stream;

/**
 * Extension of Launcher activity to provide quickstep specific functionality
 */
public abstract class BaseQuickstepLauncher extends Launcher
        implements NavigationModeChangeListener {

    private DepthController mDepthController = new DepthController(this);
    private QuickstepTransitionManager mAppTransitionManager;

    /**
     * Reusable command for applying the back button alpha on the background thread.
     */
    public static final UiThreadHelper.AsyncCommand SET_BACK_BUTTON_ALPHA =
            (context, arg1, arg2) -> SystemUiProxy.INSTANCE.get(context).setNavBarButtonAlpha(
                    Float.intBitsToFloat(arg1), arg2 != 0);

    private OverviewActionsView mActionsView;

    private @Nullable TaskbarController mTaskbarController;
    private final TaskbarStateHandler mTaskbarStateHandler = new TaskbarStateHandler(this);
    // Will be updated when dragging from taskbar.
    private @Nullable DragOptions mNextWorkspaceDragOptions = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SysUINavigationMode.INSTANCE.get(this).addModeChangeListener(this);
        addMultiWindowModeChangedListener(mDepthController);
    }

    @Override
    public void onDestroy() {
        mAppTransitionManager.onActivityDestroyed();

        SysUINavigationMode.INSTANCE.get(this).removeModeChangeListener(this);
        if (mTaskbarController != null) {
            mTaskbarController.cleanup();
        }

        super.onDestroy();
    }

    public QuickstepTransitionManager getAppTransitionManager() {
        return mAppTransitionManager;
    }

    @Override
    public void onNavigationModeChanged(Mode newMode) {
        getDragLayer().recreateControllers();
        if (mActionsView != null) {
            mActionsView.updateVerticalMargin(newMode);
        }
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        // After the transition to home, enable the high-res thumbnail loader if it wasn't enabled
        // as a part of quickstep, so that high-res thumbnails can load the next time we enter
        // overview
        RecentsModel.INSTANCE.get(this).getThumbnailCache()
                .getHighResLoadingState().setVisible(true);
    }

    @Override
    protected void handleGestureContract(Intent intent) {
        if (FeatureFlags.SEPARATE_RECENTS_ACTIVITY.get()) {
            super.handleGestureContract(intent);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        RecentsModel.INSTANCE.get(this).onTrimMemory(level);
    }

    @Override
    protected void onUiChangedWhileSleeping() {
        // Remove the snapshot because the content view may have obvious changes.
        UI_HELPER_EXECUTOR.execute(
                () -> ActivityManagerWrapper.getInstance().invalidateHomeTaskSnapshot(this));
    }

    @Override
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, Bundle options) {
        if (requestCode != -1) {
            mPendingActivityRequestCode = requestCode;
            StartActivityParams params = new StartActivityParams(this, requestCode);
            params.intentSender = intent;
            params.fillInIntent = fillInIntent;
            params.flagsMask = flagsMask;
            params.flagsValues = flagsValues;
            params.extraFlags = extraFlags;
            params.options = options;
            startActivity(ProxyActivityStarter.getLaunchIntent(this, params));
        } else {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (requestCode != -1) {
            mPendingActivityRequestCode = requestCode;
            StartActivityParams params = new StartActivityParams(this, requestCode);
            params.intent = intent;
            params.options = options;
            startActivity(ProxyActivityStarter.getLaunchIntent(this, params));
        } else {
            super.startActivityForResult(intent, requestCode, options);
        }
    }

    @Override
    protected void onDeferredResumed() {
        super.onDeferredResumed();
        handlePendingActivityRequest();
    }

    @Override
    public void onStateSetEnd(LauncherState state) {
        super.onStateSetEnd(state);
        handlePendingActivityRequest();
    }

    private void handlePendingActivityRequest() {
        if (mPendingActivityRequestCode != -1 && isInState(NORMAL)
                && ((getActivityFlags() & ACTIVITY_STATE_DEFERRED_RESUMED) != 0)) {
            // Remove any active ProxyActivityStarter task and send RESULT_CANCELED to Launcher.
            onActivityResult(mPendingActivityRequestCode, RESULT_CANCELED, null);
            // ProxyActivityStarter is started with clear task to reset the task after which it
            // removes the task itself.
            startActivity(ProxyActivityStarter.getLaunchIntent(this, null));
        }
    }

    @Override
    protected void setupViews() {
        super.setupViews();

        SysUINavigationMode.INSTANCE.get(this).updateMode();
        mActionsView = findViewById(R.id.overview_actions_view);
        SplitPlaceholderView splitPlaceholderView = findViewById(R.id.split_placeholder);
        RecentsView overviewPanel = (RecentsView) getOverviewPanel();
        splitPlaceholderView.init(
                new SplitSelectStateController(SystemUiProxy.INSTANCE.get(this))
        );
        overviewPanel.init(mActionsView, splitPlaceholderView);
        mActionsView.updateVerticalMargin(SysUINavigationMode.getMode(this));

        mAppTransitionManager = new QuickstepTransitionManager(this);
        mAppTransitionManager.registerRemoteAnimations();

        addTaskbarIfNecessary();
        addOnDeviceProfileChangeListener(newDp -> addTaskbarIfNecessary());
    }

    @Override
    public void onDisplayInfoChanged(DisplayController.Info info, int flags) {
        super.onDisplayInfoChanged(info, flags);
        if ((flags & CHANGE_SIZE) != 0) {
            addTaskbarIfNecessary();
        }
    }

    private void addTaskbarIfNecessary() {
        if (mTaskbarController != null) {
            mTaskbarController.cleanup();
            mTaskbarController = null;
        }
        if (mDeviceProfile.isTaskbarPresent) {
            TaskbarView taskbarViewOnHome = (TaskbarView) mHotseat.getTaskbarView();
            TaskbarActivityContext taskbarActivityContext = new TaskbarActivityContext(this);
            mTaskbarController = new TaskbarController(this,
                    taskbarActivityContext.getTaskbarContainerView(), taskbarViewOnHome);
            mTaskbarController.init();
        }
    }

    public <T extends OverviewActionsView> T getActionsView() {
        return (T) mActionsView;
    }

    @Override
    protected void closeOpenViews(boolean animate) {
        super.closeOpenViews(animate);
        TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY);
    }

    @Override
    protected void collectStateHandlers(List<StateHandler> out) {
        super.collectStateHandlers(out);
        out.add(getDepthController());
        out.add(new RecentsViewStateController(this));
        out.add(new BackButtonAlphaHandler(this));
        out.add(getTaskbarStateHandler());
    }

    public DepthController getDepthController() {
        return mDepthController;
    }

    public @Nullable TaskbarController getTaskbarController() {
        return mTaskbarController;
    }

    public TaskbarStateHandler getTaskbarStateHandler() {
        return mTaskbarStateHandler;
    }

    @Override
    public boolean isViewInTaskbar(View v) {
        return mTaskbarController != null && mTaskbarController.isViewInTaskbar(v);
    }

    public boolean supportsAdaptiveIconAnimation(View clickedView) {
        return mAppTransitionManager.hasControlRemoteAppTransitionPermission()
                && FeatureFlags.ADAPTIVE_ICON_WINDOW_ANIM.get()
                && !isViewInTaskbar(clickedView);
    }

    @Override
    public DragOptions getDefaultWorkspaceDragOptions() {
        if (mNextWorkspaceDragOptions != null) {
            DragOptions options = mNextWorkspaceDragOptions;
            mNextWorkspaceDragOptions = null;
            return options;
        }
        return super.getDefaultWorkspaceDragOptions();
    }

    public void setNextWorkspaceDragOptions(DragOptions dragOptions) {
        mNextWorkspaceDragOptions = dragOptions;
    }

    @Override
    public void useFadeOutAnimationForLauncherStart(CancellationSignal signal) {
        QuickstepTransitionManager appTransitionManager = getAppTransitionManager();
        appTransitionManager.setRemoteAnimationProvider(new RemoteAnimationProvider() {
            @Override
            public AnimatorSet createWindowAnimation(RemoteAnimationTargetCompat[] appTargets,
                    RemoteAnimationTargetCompat[] wallpaperTargets) {

                // On the first call clear the reference.
                signal.cancel();

                ValueAnimator fadeAnimation = ValueAnimator.ofFloat(1, 0);
                fadeAnimation.addUpdateListener(new RemoteFadeOutAnimationListener(appTargets,
                        wallpaperTargets));
                AnimatorSet anim = new AnimatorSet();
                anim.play(fadeAnimation);
                return anim;
            }
        }, signal);
    }

    @Override
    public float[] getNormalOverviewScaleAndOffset() {
        return SysUINavigationMode.getMode(this).hasGestures
                ? new float[] {1, 1} : new float[] {1.1f, 0};
    }

    @Override
    public float getNormalTaskbarScale() {
        if (mTaskbarController != null) {
            return mTaskbarController.getTaskbarScaleOnHome();
        }
        return super.getNormalTaskbarScale();
    }

    @Override
    public void onDragLayerHierarchyChanged() {
        onLauncherStateOrFocusChanged();
    }

    @Override
    protected void onActivityFlagsChanged(int changeBits) {
        if ((changeBits
                & (ACTIVITY_STATE_WINDOW_FOCUSED | ACTIVITY_STATE_TRANSITION_ACTIVE)) != 0) {
            onLauncherStateOrFocusChanged();
        }

        if ((changeBits & ACTIVITY_STATE_STARTED) != 0) {
            mDepthController.setActivityStarted(isStarted());
        }

        if ((changeBits & ACTIVITY_STATE_RESUMED) != 0) {
            if (mTaskbarController != null) {
                mTaskbarController.onLauncherResumedOrPaused(hasBeenResumed());
            }
        }

        super.onActivityFlagsChanged(changeBits);
    }

    public boolean shouldBackButtonBeHidden(LauncherState toState) {
        Mode mode = SysUINavigationMode.getMode(this);
        boolean shouldBackButtonBeHidden = mode.hasGestures
                && toState.hasFlag(FLAG_HIDE_BACK_BUTTON)
                && hasWindowFocus()
                && (getActivityFlags() & ACTIVITY_STATE_TRANSITION_ACTIVE) == 0;
        if (shouldBackButtonBeHidden) {
            // Show the back button if there is a floating view visible.
            shouldBackButtonBeHidden = AbstractFloatingView.getTopOpenViewWithType(this,
                    TYPE_ALL & ~TYPE_HIDE_BACK_BUTTON) == null;
        }
        return shouldBackButtonBeHidden;
    }

    /**
     * Sets the back button visibility based on the current state/window focus.
     */
    private void onLauncherStateOrFocusChanged() {
        boolean shouldBackButtonBeHidden = shouldBackButtonBeHidden(getStateManager().getState());
        if (SysUINavigationMode.getMode(this) == TWO_BUTTONS) {
            UiThreadHelper.setBackButtonAlphaAsync(this, SET_BACK_BUTTON_ALPHA,
                    shouldBackButtonBeHidden ? 0f : 1f, true /* animate */);
        }
        if (getDragLayer() != null) {
            getRootView().setDisallowBackGesture(shouldBackButtonBeHidden);
        }
    }

    @Override
    public void finishBindingItems(int pageBoundFirst) {
        super.finishBindingItems(pageBoundFirst);
        // Instantiate and initialize WellbeingModel now that its loading won't interfere with
        // populating workspace.
        // TODO: Find a better place for this
        WellbeingModel.INSTANCE.get(this);
    }

    @Override
    public Stream<SystemShortcut.Factory> getSupportedShortcuts() {
        return Stream.concat(super.getSupportedShortcuts(),
                Stream.of(WellbeingModel.SHORTCUT_FACTORY));
    }

    @Override
    public ActivityOptionsWrapper getActivityLaunchOptions(View v) {
        ActivityOptionsWrapper activityOptions =
                mAppTransitionManager.hasControlRemoteAppTransitionPermission()
                        ? mAppTransitionManager.getActivityLaunchOptions(this, v)
                        : super.getActivityLaunchOptions(v);
        if (mLastTouchUpTime > 0) {
            ActivityOptionsCompat.setLauncherSourceInfo(
                    activityOptions.options, mLastTouchUpTime);
        }
        return activityOptions;
    }

    public void setHintUserWillBeActive() {
        addActivityFlags(ACTIVITY_STATE_USER_WILL_BE_ACTIVE);
    }
}
