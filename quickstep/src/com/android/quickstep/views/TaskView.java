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

package com.android.quickstep.views;

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.view.Gravity.END;
import static android.view.Gravity.START;
import static android.view.Gravity.TOP;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.widget.Toast.LENGTH_SHORT;

import static com.android.launcher3.QuickstepAppTransitionManagerImpl.RECENTS_LAUNCH_DURATION;
import static com.android.launcher3.Utilities.comp;
import static com.android.launcher3.Utilities.getDescendantCoordRelativeToAncestor;
import static com.android.launcher3.anim.Interpolators.ACCEL_DEACCEL;
import static com.android.launcher3.anim.Interpolators.EXAGGERATED_EASE;
import static com.android.launcher3.anim.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.anim.Interpolators.TOUCH_RESPONSE_INTERPOLATOR;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASK_ICON_TAP_OR_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASK_LAUNCH_TAP;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.quickstep.util.NavigationModeFeatureFlag.LIVE_TILE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.TestProtocol;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.TransformingTouchDelegate;
import com.android.launcher3.util.ViewPool.Reusable;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.RemoteAnimationTargets;
import com.android.quickstep.TaskIconCache;
import com.android.quickstep.TaskOverlayFactory;
import com.android.quickstep.TaskThumbnailCache;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.TaskViewUtils;
import com.android.quickstep.util.CancellableTask;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.quickstep.util.TaskCornerRadius;
import com.android.quickstep.views.RecentsView.PageCallbacks;
import com.android.quickstep.views.RecentsView.ScrollState;
import com.android.quickstep.views.TaskThumbnailView.PreviewPositionHelper;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.QuickStepContract;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A task in the Recents view.
 */
public class TaskView extends FrameLayout implements PageCallbacks, Reusable {

    private static final String TAG = TaskView.class.getSimpleName();

    /**
     * The alpha of a black scrim on a page in the carousel as it leaves the screen.
     * In the resting position of the carousel, the adjacent pages have about half this scrim.
     */
    public static final float MAX_PAGE_SCRIM_ALPHA = 0.4f;

    private static final float EDGE_SCALE_DOWN_FACTOR_CAROUSEL = 0.03f;
    private static final float EDGE_SCALE_DOWN_FACTOR_GRID = 0.00f;

    public static final long SCALE_ICON_DURATION = 120;
    private static final long DIM_ANIM_DURATION = 700;
    /**
     * This technically can be a vanilla {@link TouchDelegate} class, however that class requires
     * setting the touch bounds at construction, so we'd repeatedly be created many instances
     * unnecessarily as scrolling occurs, whereas {@link TransformingTouchDelegate} allows touch
     * delegated bounds only to be updated.
     */
    private TransformingTouchDelegate mIconTouchDelegate;
    private TransformingTouchDelegate mChipTouchDelegate;

    private static final List<Rect> SYSTEM_GESTURE_EXCLUSION_RECT =
            Collections.singletonList(new Rect());

    private static final FloatProperty<TaskView> FOCUS_TRANSITION =
            new FloatProperty<TaskView>("focusTransition") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setIconAndDimTransitionProgress(v, false /* invert */);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mFocusTransitionProgress;
                }
            };

    private static final FloatProperty<TaskView> DISMISS_TRANSLATION_X =
            new FloatProperty<TaskView>("dismissTranslationX") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setDismissTranslationX(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mDismissTranslationX;
                }
            };

    private static final FloatProperty<TaskView> DISMISS_TRANSLATION_Y =
            new FloatProperty<TaskView>("dismissTranslationY") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setDismissTranslationY(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mDismissTranslationY;
                }
            };

    private static final FloatProperty<TaskView> TASK_OFFSET_TRANSLATION_X =
            new FloatProperty<TaskView>("taskOffsetTranslationX") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setTaskOffsetTranslationX(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mTaskOffsetTranslationX;
                }
            };

    private static final FloatProperty<TaskView> TASK_OFFSET_TRANSLATION_Y =
            new FloatProperty<TaskView>("taskOffsetTranslationY") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setTaskOffsetTranslationY(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mTaskOffsetTranslationY;
                }
            };

    private static final FloatProperty<TaskView> TASK_RESISTANCE_TRANSLATION_X =
            new FloatProperty<TaskView>("taskResistanceTranslationX") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setTaskResistanceTranslationX(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mTaskResistanceTranslationX;
                }
            };

    private static final FloatProperty<TaskView> TASK_RESISTANCE_TRANSLATION_Y =
            new FloatProperty<TaskView>("taskResistanceTranslationY") {
                @Override
                public void setValue(TaskView taskView, float v) {
                    taskView.setTaskResistanceTranslationY(v);
                }

                @Override
                public Float get(TaskView taskView) {
                    return taskView.mTaskResistanceTranslationY;
                }
            };

    private final OnAttachStateChangeListener mTaskMenuStateListener =
            new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    if (mMenuView != null) {
                        mMenuView.removeOnAttachStateChangeListener(this);
                        mMenuView = null;
                    }
                }
            };

    private final TaskOutlineProvider mOutlineProvider;

    private Task mTask;
    private TaskThumbnailView mSnapshotView;
    private TaskMenuView mMenuView;
    private IconView mIconView;
    private final DigitalWellBeingToast mDigitalWellBeingToast;
    private float mFullscreenProgress;
    private float mGridProgress;
    private float mFullscreenScale = 1;
    private float mGridScale = 1;
    private final FullscreenDrawParams mCurrentFullscreenParams;
    private final StatefulActivity mActivity;

    // Various causes of changing primary translation, which we aggregate to setTranslationX/Y().
    private float mDismissTranslationX;
    private float mDismissTranslationY;
    private float mTaskOffsetTranslationX;
    private float mTaskOffsetTranslationY;
    private float mTaskResistanceTranslationX;
    private float mTaskResistanceTranslationY;
    // The following translation variables should only be used in the same orientation as Launcher.
    private float mFullscreenTranslationX;
    private float mAccumulatedFullscreenTranslationX;
    private float mBoxTranslationY;
    // The following grid translations scales with mGridProgress.
    private float mGridTranslationX;
    private float mGridTranslationY;
    // Offset translation does not affect scroll calculation.
    private float mGridOffsetTranslationX;
    private float mNonRtlVisibleOffset;

    private ObjectAnimator mIconAndDimAnimator;
    private float mIconScaleAnimStartProgress = 0;
    private float mFocusTransitionProgress = 1;
    private float mModalness = 0;
    private float mStableAlpha = 1;

    private boolean mShowScreenshot;

    // The current background requests to load the task thumbnail and icon
    private CancellableTask mThumbnailLoadRequest;
    private CancellableTask mIconLoadRequest;

    private boolean mEndQuickswitchCuj;

    private View mContextualChipWrapper;
    private View mContextualChip;
    private final float[] mIconCenterCoords = new float[2];
    private final float[] mChipCenterCoords = new float[2];

    private boolean mIsClickableAsLiveTile = true;

    public TaskView(Context context) {
        this(context, null);
    }

    public TaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mActivity = StatefulActivity.fromContext(context);
        setOnClickListener((view) -> {
            if (getTask() == null) {
                return;
            }
            if (LIVE_TILE.get() && isRunningTask()) {
                if (!mIsClickableAsLiveTile) {
                    return;
                }

                mIsClickableAsLiveTile = false;
                RecentsView recentsView = getRecentsView();
                RemoteAnimationTargets targets = recentsView.getLiveTileParams().getTargetSet();
                recentsView.getLiveTileTaskViewSimulator().setDrawsBelowRecents(false);

                AnimatorSet anim = new AnimatorSet();
                TaskViewUtils.composeRecentsLaunchAnimator(
                        anim, this, targets.apps,
                        targets.wallpapers, true /* launcherClosing */,
                        mActivity.getStateManager(), recentsView,
                        recentsView.getDepthController());
                anim.addListener(new AnimatorListenerAdapter() {

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        recentsView.getLiveTileTaskViewSimulator().setDrawsBelowRecents(true);
                        recentsView.finishRecentsAnimation(false, null);
                        mIsClickableAsLiveTile = true;
                    }
                });
                anim.start();
            } else {
                launchTask(true /* animate */);
            }
            mActivity.getStatsLogManager().logger().withItemInfo(getItemInfo())
                    .log(LAUNCHER_TASK_LAUNCH_TAP);
        });

        mCurrentFullscreenParams = new FullscreenDrawParams(context);
        mDigitalWellBeingToast = new DigitalWellBeingToast(mActivity, this);

        mOutlineProvider = new TaskOutlineProvider(getContext(), mCurrentFullscreenParams);
        setOutlineProvider(mOutlineProvider);
    }

    /**
     * Builds proto for logging
     */
    public WorkspaceItemInfo getItemInfo() {
        final Task task = getTask();
        ComponentKey componentKey = TaskUtils.getLaunchComponentKeyForTask(task.key);
        WorkspaceItemInfo stubInfo = new WorkspaceItemInfo();
        stubInfo.itemType = LauncherSettings.Favorites.ITEM_TYPE_TASK;
        stubInfo.container = LauncherSettings.Favorites.CONTAINER_TASKSWITCHER;
        stubInfo.user = componentKey.user;
        stubInfo.intent = new Intent().setComponent(componentKey.componentName);
        stubInfo.title = task.title;
        stubInfo.screenId = getRecentsView().indexOfChild(this);
        return stubInfo;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSnapshotView = findViewById(R.id.snapshot);
        mIconView = findViewById(R.id.icon);
        mIconTouchDelegate = new TransformingTouchDelegate(mIconView);
    }

    /**
     * Whether the taskview should take the touch event from parent. Events passed to children
     * that might require special handling.
     */
    public boolean offerTouchToChildren(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            computeAndSetIconTouchDelegate();
            computeAndSetChipTouchDelegate();
        }
        if (mIconTouchDelegate != null && mIconTouchDelegate.onTouchEvent(event)) {
            return true;
        }
        if (mChipTouchDelegate != null && mChipTouchDelegate.onTouchEvent(event)) {
            return true;
        }
        return false;
    }

    private void computeAndSetIconTouchDelegate() {
        float iconHalfSize = mIconView.getWidth() / 2f;
        mIconCenterCoords[0] = mIconCenterCoords[1] = iconHalfSize;
        getDescendantCoordRelativeToAncestor(mIconView, mActivity.getDragLayer(), mIconCenterCoords,
                false);
        mIconTouchDelegate.setBounds(
                (int) (mIconCenterCoords[0] - iconHalfSize),
                (int) (mIconCenterCoords[1] - iconHalfSize),
                (int) (mIconCenterCoords[0] + iconHalfSize),
                (int) (mIconCenterCoords[1] + iconHalfSize));
    }

    private void computeAndSetChipTouchDelegate() {
        if (mContextualChipWrapper != null) {
            float chipHalfWidth = mContextualChipWrapper.getWidth() / 2f;
            float chipHalfHeight = mContextualChipWrapper.getHeight() / 2f;
            mChipCenterCoords[0] = chipHalfWidth;
            mChipCenterCoords[1] = chipHalfHeight;
            getDescendantCoordRelativeToAncestor(mContextualChipWrapper, mActivity.getDragLayer(),
                    mChipCenterCoords,
                    false);
            mChipTouchDelegate.setBounds(
                    (int) (mChipCenterCoords[0] - chipHalfWidth),
                    (int) (mChipCenterCoords[1] - chipHalfHeight),
                    (int) (mChipCenterCoords[0] + chipHalfWidth),
                    (int) (mChipCenterCoords[1] + chipHalfHeight));
        }
    }

    /**
     * The modalness of this view is how it should be displayed when it is shown on its own in the
     * modal state of overview.
     *
     * @param modalness [0, 1] 0 being in context with other tasks, 1 being shown on its own.
     */
    public void setModalness(float modalness) {
        if (mModalness == modalness) {
            return;
        }
        mModalness = modalness;
        mIconView.setAlpha(comp(modalness));
        if (mContextualChip != null) {
            mContextualChip.setScaleX(comp(modalness));
            mContextualChip.setScaleY(comp(modalness));
        }
        mDigitalWellBeingToast.updateBannerOffset(modalness,
                mCurrentFullscreenParams.mCurrentDrawnInsets.top
                        + mCurrentFullscreenParams.mCurrentDrawnInsets.bottom);
    }

    public TaskMenuView getMenuView() {
        return mMenuView;
    }

    public DigitalWellBeingToast getDigitalWellBeingToast() {
        return mDigitalWellBeingToast;
    }

    /**
     * Updates this task view to the given {@param task}.
     *
     * TODO(b/142282126) Re-evaluate if we need to pass in isMultiWindowMode after
     *   that issue is fixed
     */
    public void bind(Task task, RecentsOrientedState orientedState) {
        cancelPendingLoadTasks();
        mTask = task;
        mSnapshotView.bind(task);
        setOrientationState(orientedState);
    }

    public Task getTask() {
        return mTask;
    }

    public boolean hasTaskId(int taskId) {
        return mTask != null && mTask.key != null && mTask.key.id == taskId;
    }

    public TaskThumbnailView getThumbnail() {
        return mSnapshotView;
    }

    public IconView getIconView() {
        return mIconView;
    }

    public AnimatorPlaybackController createLaunchAnimationForRunningTask() {
        return getRecentsView().createTaskLaunchAnimation(
                this, RECENTS_LAUNCH_DURATION, TOUCH_RESPONSE_INTERPOLATOR)
                .createPlaybackController();
    }

    public void launchTask(boolean animate) {
        launchTask(animate, false /* freezeTaskList */);
    }

    public void launchTask(boolean animate, boolean freezeTaskList) {
        launchTask(animate, freezeTaskList, (result) -> {
            if (!result) {
                notifyTaskLaunchFailed(TAG);
            }
        }, getHandler());
    }

    public void launchTask(boolean animate, Consumer<Boolean> resultCallback,
            Handler resultCallbackHandler) {
        launchTask(animate, false /* freezeTaskList */, resultCallback, resultCallbackHandler);
    }

    public void launchTask(boolean animate, boolean freezeTaskList, Consumer<Boolean> resultCallback,
            Handler resultCallbackHandler) {
        if (mTask != null) {
            final ActivityOptions opts;
            TestLogging.recordEvent(
                    TestProtocol.SEQUENCE_MAIN, "startActivityFromRecentsAsync", mTask);
            if (animate) {
                opts = mActivity.getActivityLaunchOptions(this);
                if (freezeTaskList) {
                    ActivityOptionsCompat.setFreezeRecentTasksList(opts);
                }
                ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(mTask.key,
                        opts, resultCallback, resultCallbackHandler);
            } else {
                opts = ActivityOptionsCompat.makeCustomAnimation(getContext(), 0, 0, () -> {
                    if (resultCallback != null) {
                        // Only post the animation start after the system has indicated that the
                        // transition has started
                        resultCallbackHandler.post(() -> resultCallback.accept(true));
                    }
                }, resultCallbackHandler);
                if (freezeTaskList) {
                    ActivityOptionsCompat.setFreezeRecentTasksList(opts);
                }
                UI_HELPER_EXECUTOR.execute(
                        () -> ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(
                                mTask.key,
                                opts,
                                (success) -> {
                                    if (resultCallback != null && !success) {
                                        // If the call to start activity failed, then post the
                                        // result
                                        // immediately, otherwise, wait for the animation start
                                        // callback
                                        // from the activity options above
                                        resultCallbackHandler.post(
                                                () -> resultCallback.accept(false));
                                    }
                                }, resultCallbackHandler));
            }
        }
    }

    public void onTaskListVisibilityChanged(boolean visible) {
        if (mTask == null) {
            return;
        }
        cancelPendingLoadTasks();
        if (visible) {
            // These calls are no-ops if the data is already loaded, try and load the high
            // resolution thumbnail if the state permits
            RecentsModel model = RecentsModel.INSTANCE.get(getContext());
            TaskThumbnailCache thumbnailCache = model.getThumbnailCache();
            TaskIconCache iconCache = model.getIconCache();
            mThumbnailLoadRequest = thumbnailCache.updateThumbnailInBackground(
                    mTask, thumbnail -> mSnapshotView.setThumbnail(mTask, thumbnail));
            mIconLoadRequest = iconCache.updateIconInBackground(mTask,
                    (task) -> {
                        setIcon(task.icon);
                        mDigitalWellBeingToast.initialize(mTask);
                    });
        } else {
            mSnapshotView.setThumbnail(null, null);
            setIcon(null);
            // Reset the task thumbnail reference as well (it will be fetched from the cache or
            // reloaded next time we need it)
            mTask.thumbnail = null;
        }
    }

    private void cancelPendingLoadTasks() {
        if (mThumbnailLoadRequest != null) {
            mThumbnailLoadRequest.cancel();
            mThumbnailLoadRequest = null;
        }
        if (mIconLoadRequest != null) {
            mIconLoadRequest.cancel();
            mIconLoadRequest = null;
        }
    }

    private boolean showTaskMenu() {
        if (!getRecentsView().isClearAllHidden()) {
            getRecentsView().snapToPage(getRecentsView().indexOfChild(this));
        } else {
            mMenuView = TaskMenuView.showForTask(this);
            mActivity.getStatsLogManager().logger().withItemInfo(getItemInfo())
                    .log(LAUNCHER_TASK_ICON_TAP_OR_LONGPRESS);
            if (mMenuView != null) {
                mMenuView.addOnAttachStateChangeListener(mTaskMenuStateListener);
            }
        }
        return mMenuView != null;
    }

    private void setIcon(Drawable icon) {
        if (icon != null) {
            mIconView.setDrawable(icon);
            mIconView.setOnClickListener(v -> showTaskMenu());
            mIconView.setOnLongClickListener(v -> {
                requestDisallowInterceptTouchEvent(true);
                return showTaskMenu();
            });
        } else {
            mIconView.setDrawable(null);
            mIconView.setOnClickListener(null);
            mIconView.setOnLongClickListener(null);
        }
    }

    public void setOrientationState(RecentsOrientedState orientationState) {
        PagedOrientationHandler orientationHandler = orientationState.getOrientationHandler();
        boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        LayoutParams snapshotParams = (LayoutParams) mSnapshotView.getLayoutParams();
        int thumbnailPadding = (int) getResources().getDimension(R.dimen.task_thumbnail_top_margin);
        int taskIconMargin = (int) getResources().getDimension(R.dimen.task_icon_top_margin);
        int taskIconHeight = (int) getResources().getDimension(R.dimen.task_thumbnail_icon_size);
        int iconTopMargin = taskIconMargin - taskIconHeight + thumbnailPadding;
        LayoutParams iconParams = (LayoutParams) mIconView.getLayoutParams();
        switch (orientationHandler.getRotation()) {
            case ROTATION_90:
                iconParams.gravity = (isRtl ? START : END) | CENTER_VERTICAL;
                iconParams.rightMargin = -thumbnailPadding;
                iconParams.leftMargin = 0;
                iconParams.topMargin = snapshotParams.topMargin / 2;
                break;
            case ROTATION_180:
                iconParams.gravity = BOTTOM | CENTER_HORIZONTAL;
                iconParams.bottomMargin = -thumbnailPadding;
                iconParams.leftMargin = iconParams.rightMargin = 0;
                iconParams.topMargin = iconTopMargin;
                break;
            case ROTATION_270:
                iconParams.gravity = (isRtl ? END : START) | CENTER_VERTICAL;
                iconParams.leftMargin = -thumbnailPadding;
                iconParams.rightMargin = 0;
                iconParams.topMargin = snapshotParams.topMargin / 2;
                break;
            case Surface.ROTATION_0:
            default:
                iconParams.gravity = TOP | CENTER_HORIZONTAL;
                iconParams.leftMargin = iconParams.rightMargin = 0;
                iconParams.topMargin = iconTopMargin;
                break;
        }
        mIconView.setLayoutParams(iconParams);
        mIconView.setRotation(orientationHandler.getDegreesRotated());

        if (mMenuView != null) {
            mMenuView.onRotationChanged();
        }
    }

    private void setIconAndDimTransitionProgress(float progress, boolean invert) {
        if (invert) {
            progress = 1 - progress;
        }
        mFocusTransitionProgress = progress;
        mSnapshotView.setDimAlphaMultipler(0);
        float iconScalePercentage = (float) SCALE_ICON_DURATION / DIM_ANIM_DURATION;
        float lowerClamp = invert ? 1f - iconScalePercentage : 0;
        float upperClamp = invert ? 1 : iconScalePercentage;
        float scale = Interpolators.clampToProgress(FAST_OUT_SLOW_IN, lowerClamp, upperClamp)
                .getInterpolation(progress);
        mIconView.setScaleX(scale);
        mIconView.setScaleY(scale);
        if (mContextualChip != null && mContextualChipWrapper != null) {
            mContextualChipWrapper.setAlpha(scale);
            mContextualChip.setScaleX(scale);
            mContextualChip.setScaleY(scale);
        }
        mDigitalWellBeingToast.updateBannerOffset(1f - scale,
                mCurrentFullscreenParams.mCurrentDrawnInsets.top
                        + mCurrentFullscreenParams.mCurrentDrawnInsets.bottom);
    }

    public void setIconScaleAnimStartProgress(float startProgress) {
        mIconScaleAnimStartProgress = startProgress;
    }

    public void animateIconScaleAndDimIntoView() {
        if (mIconAndDimAnimator != null) {
            mIconAndDimAnimator.cancel();
        }
        mIconAndDimAnimator = ObjectAnimator.ofFloat(this, FOCUS_TRANSITION, 1);
        mIconAndDimAnimator.setCurrentFraction(mIconScaleAnimStartProgress);
        mIconAndDimAnimator.setDuration(DIM_ANIM_DURATION).setInterpolator(LINEAR);
        mIconAndDimAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mIconAndDimAnimator = null;
            }
        });
        mIconAndDimAnimator.start();
    }

    protected void setIconScaleAndDim(float iconScale) {
        setIconScaleAndDim(iconScale, false);
    }

    private void setIconScaleAndDim(float iconScale, boolean invert) {
        if (mIconAndDimAnimator != null) {
            mIconAndDimAnimator.cancel();
        }
        setIconAndDimTransitionProgress(iconScale, invert);
    }

    protected void resetViewTransforms() {
        // fullscreenTranslation and accumulatedTranslation should not be reset, as
        // resetViewTransforms is called during Quickswitch scrolling.
        mDismissTranslationX = mTaskOffsetTranslationX = mTaskResistanceTranslationX = 0f;
        mDismissTranslationY = mTaskOffsetTranslationY = mTaskResistanceTranslationY = 0f;
        applyTranslationX();
        applyTranslationY();
        setTranslationZ(0);
        setAlpha(mStableAlpha);
        setIconScaleAndDim(1);
    }

    public void setStableAlpha(float parentAlpha) {
        mStableAlpha = parentAlpha;
        setAlpha(mStableAlpha);
    }

    @Override
    public void onRecycle() {
        mFullscreenTranslationX = mAccumulatedFullscreenTranslationX = mGridTranslationX =
                mGridTranslationY =
                        mGridOffsetTranslationX = mBoxTranslationY = mNonRtlVisibleOffset = 0f;
        resetViewTransforms();
        // Clear any references to the thumbnail (it will be re-read either from the cache or the
        // system on next bind)
        mSnapshotView.setThumbnail(mTask, null);
        setOverlayEnabled(false);
        onTaskListVisibilityChanged(false);
    }

    @Override
    public void onPageScroll(ScrollState scrollState) {
        // Don't do anything if it's modal.
        if (mModalness > 0) {
            return;
        }

        float dwbBannerAlpha = Utilities.boundToRange(1.0f - 2 * scrollState.linearInterpolation,
                0f, 1f);
        mDigitalWellBeingToast.updateBannerAlpha(dwbBannerAlpha);

        if (mMenuView != null) {
            PagedOrientationHandler pagedOrientationHandler = getPagedOrientationHandler();
            RecentsView recentsView = getRecentsView();
            mMenuView.setPosition(getX() - recentsView.getScrollX(),
                    getY() - recentsView.getScrollY(), pagedOrientationHandler);
            mMenuView.setScaleX(getScaleX());
            mMenuView.setScaleY(getScaleY());
        }
    }

    /**
     * Sets the contextual chip.
     *
     * @param view Wrapper view containing contextual chip.
     */
    public void setContextualChip(View view) {
        if (mContextualChipWrapper != null) {
            removeView(mContextualChipWrapper);
        }
        if (view != null) {
            mContextualChipWrapper = view;
            LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            layoutParams.gravity = BOTTOM | CENTER_HORIZONTAL;
            int expectedChipHeight = getExpectedViewHeight(view);
            float chipOffset = getResources().getDimension(R.dimen.chip_hint_vertical_offset);
            layoutParams.bottomMargin = -expectedChipHeight - (int) chipOffset;
            mContextualChip = ((FrameLayout) mContextualChipWrapper).getChildAt(0);
            mContextualChip.setScaleX(0f);
            mContextualChip.setScaleY(0f);
            addView(view, getChildCount(), layoutParams);
            if (mContextualChip != null) {
                mContextualChip.animate().scaleX(1f).scaleY(1f).setDuration(50);
            }
            if (mContextualChipWrapper != null) {
                mChipTouchDelegate = new TransformingTouchDelegate(mContextualChipWrapper);
            }
        }
    }

    public float getTaskCornerRadius() {
        return TaskCornerRadius.get(mActivity);
    }

    /**
     * Clears the contextual chip from TaskView.
     *
     * @return The contextual chip wrapper view to be recycled.
     */
    public View clearContextualChip() {
        if (mContextualChipWrapper != null) {
            removeView(mContextualChipWrapper);
        }
        View oldContextualChipWrapper = mContextualChipWrapper;
        mContextualChipWrapper = null;
        mContextualChip = null;
        mChipTouchDelegate = null;
        return oldContextualChipWrapper;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mActivity.getDeviceProfile().isTablet && FeatureFlags.ENABLE_OVERVIEW_GRID.get()) {
            setPivotX(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? (right - left) : 0);
            setPivotY(mSnapshotView.getTop());
        } else {
            setPivotX((right - left) * 0.5f);
            setPivotY(mSnapshotView.getTop() + mSnapshotView.getHeight() * 0.5f);
        }
        if (Utilities.ATLEAST_Q) {
            SYSTEM_GESTURE_EXCLUSION_RECT.get(0).set(0, 0, getWidth(), getHeight());
            setSystemGestureExclusionRects(SYSTEM_GESTURE_EXCLUSION_RECT);
        }
    }

    /**
     * How much to scale down pages near the edge of the screen.
     */
    public static float getEdgeScaleDownFactor(DeviceProfile deviceProfile) {
        if (deviceProfile.isTablet && FeatureFlags.ENABLE_OVERVIEW_GRID.get()) {
            return EDGE_SCALE_DOWN_FACTOR_GRID;
        } else {
            return EDGE_SCALE_DOWN_FACTOR_CAROUSEL;
        }
    }

    private void setFullscreenScale(float fullscreenScale) {
        mFullscreenScale = fullscreenScale;
        applyScale();
    }

    public void setGridScale(float gridScale) {
        mGridScale = gridScale;
        applyScale();
    }

    /**
     * Moves TaskView between carousel and 2 row grid.
     *
     * @param gridProgress 0 = carousel; 1 = 2 row grid.
     */
    public void setGridProgress(float gridProgress) {
        mGridProgress = gridProgress;
        applyTranslationX();
        applyTranslationY();
        applyScale();
    }

    private void applyScale() {
        float scale = 1;
        float fullScreenProgress = EXAGGERATED_EASE.getInterpolation(mFullscreenProgress);
        scale *= Utilities.mapRange(fullScreenProgress, 1f, mFullscreenScale);
        float gridProgress = ACCEL_DEACCEL.getInterpolation(mGridProgress);
        scale *= Utilities.mapRange(gridProgress, 1f, mGridScale);
        setScaleX(scale);
        setScaleY(scale);
    }

    private void setDismissTranslationX(float x) {
        mDismissTranslationX = x;
        applyTranslationX();
    }

    private void setDismissTranslationY(float y) {
        mDismissTranslationY = y;
        applyTranslationY();
    }

    private void setTaskOffsetTranslationX(float x) {
        mTaskOffsetTranslationX = x;
        applyTranslationX();
    }

    private void setTaskOffsetTranslationY(float y) {
        mTaskOffsetTranslationY = y;
        applyTranslationY();
    }

    private void setTaskResistanceTranslationX(float x) {
        mTaskResistanceTranslationX = x;
        applyTranslationX();
    }

    private void setTaskResistanceTranslationY(float y) {
        mTaskResistanceTranslationY = y;
        applyTranslationY();
    }

    private void setFullscreenTranslationX(float fullscreenTranslationX) {
        mFullscreenTranslationX = fullscreenTranslationX;
        applyTranslationX();
    }

    public float getFullscreenTranslationX() {
        return mFullscreenTranslationX;
    }

    public void setAccumulatedFullscreenTranslationX(float accumulatedFullscreenTranslationX) {
        mAccumulatedFullscreenTranslationX = accumulatedFullscreenTranslationX;
        applyTranslationX();
    }

    public void setGridTranslationX(float gridTranslationX) {
        mGridTranslationX = gridTranslationX;
        applyTranslationX();
    }

    public float getGridTranslationX() {
        return mGridTranslationX;
    }

    public void setGridTranslationY(float gridTranslationY) {
        mGridTranslationY = gridTranslationY;
        applyTranslationY();
    }

    public float getGridTranslationY() {
        return mGridTranslationY;
    }

    public void setGridOffsetTranslationX(float gridOffsetTranslationX) {
        mGridOffsetTranslationX = gridOffsetTranslationX;
        applyTranslationX();
    }

    public void setNonRtlVisibleOffset(float nonRtlVisibleOffset) {
        mNonRtlVisibleOffset = nonRtlVisibleOffset;
    }

    public float getScrollAdjustment(boolean fullscreenEnabled, boolean gridEnabled) {
        float scrollAdjustment = 0;
        if (fullscreenEnabled) {
            scrollAdjustment += mFullscreenTranslationX + mAccumulatedFullscreenTranslationX;
        }
        if (gridEnabled) {
            scrollAdjustment += mGridTranslationX;
        }
        return scrollAdjustment;
    }

    public float getOffsetAdjustment(boolean fullscreenEnabled, boolean gridEnabled) {
        float offsetAdjustment = getScrollAdjustment(fullscreenEnabled, gridEnabled);
        if (gridEnabled) {
            offsetAdjustment += mGridOffsetTranslationX + mNonRtlVisibleOffset;
        }
        return offsetAdjustment;
    }

    public float getSizeAdjustment(boolean fullscreenEnabled, boolean gridEnabled) {
        float sizeAdjustment = 1;
        if (fullscreenEnabled) {
            sizeAdjustment *= mFullscreenScale;
        }
        if (gridEnabled) {
            sizeAdjustment *= mGridScale;
        }
        return sizeAdjustment;
    }

    private void setBoxTranslationY(float boxTranslationY) {
        mBoxTranslationY = boxTranslationY;
        applyTranslationY();
    }

    private void applyTranslationX() {
        setTranslationX(mDismissTranslationX + mTaskOffsetTranslationX + mTaskResistanceTranslationX
                + getFullscreenTrans(mFullscreenTranslationX + mAccumulatedFullscreenTranslationX)
                + getGridTrans(mGridTranslationX + mGridOffsetTranslationX));
    }

    private void applyTranslationY() {
        setTranslationY(
                mDismissTranslationY + mTaskOffsetTranslationY + mTaskResistanceTranslationY
                        + getGridTrans(mGridTranslationY) + mBoxTranslationY);
    }

    private float getGridTrans(float endTranslation) {
        float progress = ACCEL_DEACCEL.getInterpolation(mGridProgress);
        return Utilities.mapRange(progress, 0, endTranslation);
    }

    public FloatProperty<TaskView> getFillDismissGapTranslationProperty() {
        return getPagedOrientationHandler().getPrimaryValue(
                DISMISS_TRANSLATION_X, DISMISS_TRANSLATION_Y);
    }

    public FloatProperty<TaskView> getDismissTaskTranslationProperty() {
        return getPagedOrientationHandler().getSecondaryValue(
                DISMISS_TRANSLATION_X, DISMISS_TRANSLATION_Y);
    }

    public FloatProperty<TaskView> getPrimaryTaskOffsetTranslationProperty() {
        return getPagedOrientationHandler().getPrimaryValue(
                TASK_OFFSET_TRANSLATION_X, TASK_OFFSET_TRANSLATION_Y);
    }

    public FloatProperty<TaskView> getTaskResistanceTranslationProperty() {
        return getPagedOrientationHandler().getSecondaryValue(
                TASK_RESISTANCE_TRANSLATION_X, TASK_RESISTANCE_TRANSLATION_Y);
    }

    @Override
    public boolean hasOverlappingRendering() {
        // TODO: Clip-out the icon region from the thumbnail, since they are overlapping.
        return false;
    }

    public boolean isEndQuickswitchCuj() {
        return mEndQuickswitchCuj;
    }

    public void setEndQuickswitchCuj(boolean endQuickswitchCuj) {
        mEndQuickswitchCuj = endQuickswitchCuj;
    }

    private static final class TaskOutlineProvider extends ViewOutlineProvider {

        private final int mMarginTop;
        private FullscreenDrawParams mFullscreenParams;

        TaskOutlineProvider(Context context, FullscreenDrawParams fullscreenParams) {
            mMarginTop = context.getResources().getDimensionPixelSize(
                    R.dimen.task_thumbnail_top_margin);
            mFullscreenParams = fullscreenParams;
        }

        public void setFullscreenParams(FullscreenDrawParams params) {
            mFullscreenParams = params;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            RectF insets = mFullscreenParams.mCurrentDrawnInsets;
            float scale = mFullscreenParams.mScale;
            outline.setRoundRect(0,
                    (int) (mMarginTop * scale),
                    (int) ((insets.left + view.getWidth() + insets.right) * scale),
                    (int) ((insets.top + view.getHeight() + insets.bottom) * scale),
                    mFullscreenParams.mCurrentDrawnCornerRadius);
        }
    }

    private int getExpectedViewHeight(View view) {
        int expectedHeight;
        int h = view.getLayoutParams().height;
        if (h > 0) {
            expectedHeight = h;
        } else {
            int m = MeasureSpec.makeMeasureSpec(MeasureSpec.EXACTLY - 1, MeasureSpec.AT_MOST);
            view.measure(m, m);
            expectedHeight = view.getMeasuredHeight();
        }
        return expectedHeight;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(R.string.accessibility_close,
                        getContext().getText(R.string.accessibility_close)));

        final Context context = getContext();
        for (SystemShortcut s : TaskOverlayFactory.getEnabledShortcuts(this)) {
            info.addAction(s.createAccessibilityAction(context));
        }

        if (mDigitalWellBeingToast.hasLimit()) {
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            R.string.accessibility_app_usage_settings,
                            getContext().getText(R.string.accessibility_app_usage_settings)));
        }

        final RecentsView recentsView = getRecentsView();
        final AccessibilityNodeInfo.CollectionItemInfo itemInfo =
                AccessibilityNodeInfo.CollectionItemInfo.obtain(
                        0, 1, recentsView.getTaskViewCount() - recentsView.indexOfChild(this) - 1,
                        1, false);
        info.setCollectionItemInfo(itemInfo);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.string.accessibility_close) {
            getRecentsView().dismissTask(this, true /*animateTaskView*/,
                    true /*removeTask*/);
            return true;
        }

        if (action == R.string.accessibility_app_usage_settings) {
            mDigitalWellBeingToast.openAppUsageSettings(this);
            return true;
        }

        for (SystemShortcut s : TaskOverlayFactory.getEnabledShortcuts(this)) {
            if (s.hasHandlerForAction(action)) {
                s.onClick(this);
                return true;
            }
        }

        return super.performAccessibilityAction(action, arguments);
    }

    public RecentsView getRecentsView() {
        return (RecentsView) getParent();
    }

    PagedOrientationHandler getPagedOrientationHandler() {
        return getRecentsView().mOrientationState.getOrientationHandler();
    }

    public void notifyTaskLaunchFailed(String tag) {
        String msg = "Failed to launch task";
        if (mTask != null) {
            msg += " (task=" + mTask.key.baseIntent + " userId=" + mTask.key.userId + ")";
        }
        Log.w(tag, msg);
        Toast.makeText(getContext(), R.string.activity_not_available, LENGTH_SHORT).show();
    }

    /**
     * Hides the icon and shows insets when this TaskView is about to be shown fullscreen.
     *
     * @param progress: 0 = show icon and no insets; 1 = don't show icon and show full insets.
     */
    public void setFullscreenProgress(float progress) {
        progress = Utilities.boundToRange(progress, 0, 1);
        mFullscreenProgress = progress;
        mIconView.setVisibility(progress < 1 ? VISIBLE : INVISIBLE);
        getThumbnail().getTaskOverlay().setFullscreenProgress(progress);

        applyTranslationX();
        applyTranslationY();
        applyScale();

        TaskThumbnailView thumbnail = getThumbnail();
        updateCurrentFullscreenParams(thumbnail.getPreviewPositionHelper());

        if (!getRecentsView().isTaskIconScaledDown(this)) {
            // Some of the items in here are dependent on the current fullscreen params, but don't
            // update them if the icon is supposed to be scaled down.
            setIconScaleAndDim(progress, true /* invert */);
        }

        thumbnail.setFullscreenParams(mCurrentFullscreenParams);
        mOutlineProvider.setFullscreenParams(mCurrentFullscreenParams);
        invalidateOutline();
    }

    void updateCurrentFullscreenParams(PreviewPositionHelper previewPositionHelper) {
        if (getRecentsView() == null) {
            return;
        }
        mCurrentFullscreenParams.setProgress(
                mFullscreenProgress,
                getRecentsView().getScaleX(),
                getWidth(), mActivity.getDeviceProfile(),
                previewPositionHelper);
    }

    /**
     * Updates TaskView scaling and translation required to support variable width if enabled, while
     * ensuring TaskView fits into screen in fullscreen.
     */
    void updateTaskSize() {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (mActivity.getDeviceProfile().isTablet && FeatureFlags.ENABLE_OVERVIEW_GRID.get()) {
            final int thumbnailPadding = (int) getResources().getDimension(
                    R.dimen.task_thumbnail_top_margin);

            Rect lastComputedTaskSize = getRecentsView().getLastComputedTaskSize();
            int taskWidth = lastComputedTaskSize.width();
            int taskHeight = lastComputedTaskSize.height();

            int expectedWidth;
            int expectedHeight;
            float thumbnailRatio = mTask != null ? mTask.getVisibleThumbnailRatio() : 0f;
            if (isRunningTask() || thumbnailRatio == 0f) {
                expectedWidth = taskWidth;
                expectedHeight = taskHeight + thumbnailPadding;
            } else {
                int boxLength = Math.max(taskWidth, taskHeight);
                if (thumbnailRatio > 1) {
                    expectedWidth = boxLength;
                    expectedHeight = (int) (boxLength / thumbnailRatio) + thumbnailPadding;
                } else {
                    expectedWidth = (int) (boxLength * thumbnailRatio);
                    expectedHeight = boxLength + thumbnailPadding;
                }
            }

            float heightDiff = (expectedHeight - thumbnailPadding - taskHeight) / 2.0f;
            setBoxTranslationY(heightDiff);

            float fullscreenScale = 1f;
            if (expectedWidth > taskWidth) {
                // In full screen, expectedWidth should not be larger than taskWidth.
                fullscreenScale = taskWidth / (float) expectedWidth;
            } else if (expectedHeight - thumbnailPadding > taskHeight) {
                // In full screen, expectedHeight should not be larger than taskHeight.
                fullscreenScale = taskHeight / (float) (expectedHeight - thumbnailPadding);
            }
            setFullscreenScale(fullscreenScale);

            float widthDiff = params.width * (1 - mFullscreenScale);
            setFullscreenTranslationX(
                    getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -widthDiff : widthDiff);

            if (params.width != expectedWidth || params.height != expectedHeight) {
                params.width = expectedWidth;
                params.height = expectedHeight;
                setLayoutParams(params);
            }
        } else {
            setBoxTranslationY(0);
            setFullscreenTranslationX(0);
            setFullscreenScale(1);
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                setLayoutParams(params);
            }
        }
    }


    private float getFullscreenTrans(float endTranslation) {
        float progress = ACCEL_DEACCEL.getInterpolation(mFullscreenProgress);
        return Utilities.mapRange(progress, 0, endTranslation);
    }

    public boolean isRunningTask() {
        if (getRecentsView() == null) {
            return false;
        }
        return this == getRecentsView().getRunningTaskView();
    }

    public void setShowScreenshot(boolean showScreenshot) {
        mShowScreenshot = showScreenshot;
    }

    public boolean showScreenshot() {
        if (!isRunningTask()) {
            return true;
        }
        return mShowScreenshot;
    }

    public void setOverlayEnabled(boolean overlayEnabled) {
        mSnapshotView.setOverlayEnabled(overlayEnabled);
    }

    /**
     * We update and subsequently draw these in {@link #setFullscreenProgress(float)}.
     */
    public static class FullscreenDrawParams {

        private final float mCornerRadius;
        private final float mWindowCornerRadius;

        public RectF mCurrentDrawnInsets = new RectF();
        public float mCurrentDrawnCornerRadius;
        /** The current scale we apply to the thumbnail to adjust for new left/right insets. */
        public float mScale = 1;

        public FullscreenDrawParams(Context context) {
            mCornerRadius = TaskCornerRadius.get(context);
            mWindowCornerRadius = QuickStepContract.getWindowCornerRadius(context.getResources());

            mCurrentDrawnCornerRadius = mCornerRadius;
        }

        /**
         * Sets the progress in range [0, 1]
         */
        public void setProgress(float fullscreenProgress, float parentScale, int previewWidth,
                DeviceProfile dp, PreviewPositionHelper pph) {
            RectF insets = pph.getInsetsToDrawInFullscreen();

            float currentInsetsLeft = insets.left * fullscreenProgress;
            float currentInsetsRight = insets.right * fullscreenProgress;
            mCurrentDrawnInsets.set(currentInsetsLeft, insets.top * fullscreenProgress,
                    currentInsetsRight, insets.bottom * fullscreenProgress);
            float fullscreenCornerRadius = dp.isMultiWindowMode ? 0 : mWindowCornerRadius;

            mCurrentDrawnCornerRadius =
                    Utilities.mapRange(fullscreenProgress, mCornerRadius, fullscreenCornerRadius)
                            / parentScale;

            // We scaled the thumbnail to fit the content (excluding insets) within task view width.
            // Now that we are drawing left/right insets again, we need to scale down to fit them.
            if (previewWidth > 0) {
                mScale = previewWidth / (previewWidth + currentInsetsLeft + currentInsetsRight);
            }
        }

    }
}
