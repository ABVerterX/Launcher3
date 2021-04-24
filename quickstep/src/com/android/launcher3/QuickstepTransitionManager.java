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

package com.android.launcher3;

import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;

import static com.android.launcher3.BaseActivity.INVISIBLE_ALL;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_APP_TRANSITIONS;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_PENDING_FLAGS;
import static com.android.launcher3.BaseActivity.PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.Utilities.postAsyncCallback;
import static com.android.launcher3.anim.Interpolators.AGGRESSIVE_EASE;
import static com.android.launcher3.anim.Interpolators.DEACCEL_1_7;
import static com.android.launcher3.anim.Interpolators.EXAGGERATED_EASE;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.config.FeatureFlags.KEYGUARD_ANIMATION;
import static com.android.launcher3.config.FeatureFlags.SEPARATE_RECENTS_ACTIVITY;
import static com.android.launcher3.dragndrop.DragLayer.ALPHA_INDEX_TRANSITIONS;
import static com.android.launcher3.statehandlers.DepthController.DEPTH;
import static com.android.quickstep.TaskUtils.taskIsATargetWithMode;
import static com.android.quickstep.TaskViewUtils.findTaskViewToLaunch;
import static com.android.systemui.shared.system.QuickStepContract.getWindowCornerRadius;
import static com.android.systemui.shared.system.QuickStepContract.supportsRoundedCornersOnWindows;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_OPENING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Pair;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.shortcuts.DeepShortcutView;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.util.ActivityOptionsWrapper;
import com.android.launcher3.util.DynamicResource;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.util.MultiValueAlpha.AlphaProperty;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.views.FloatingIconView;
import com.android.quickstep.RemoteAnimationTargets;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskViewUtils;
import com.android.quickstep.util.MultiValueUpdateListener;
import com.android.quickstep.util.RemoteAnimationProvider;
import com.android.quickstep.util.StaggeredWorkspaceAnim;
import com.android.quickstep.util.SurfaceTransactionApplier;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.system.ActivityCompat;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.android.systemui.shared.system.RemoteAnimationDefinitionCompat;
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.RemoteTransitionCompat;
import com.android.systemui.shared.system.SyncRtSurfaceTransactionApplierCompat.SurfaceParams;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.wm.shell.startingsurface.IStartingWindowListener;

import java.util.LinkedHashMap;

/**
 * Manages the opening and closing app transitions from Launcher
 */
public class QuickstepTransitionManager implements OnDeviceProfileChangeListener {

    private static final String TAG = "QuickstepTransition";

    private static final boolean ENABLE_SHELL_STARTING_SURFACE =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", false);

    /** Duration of status bar animations. */
    public static final int STATUS_BAR_TRANSITION_DURATION = 120;

    /**
     * Since our animations decelerate heavily when finishing, we want to start status bar
     * animations x ms before the ending.
     */
    public static final int STATUS_BAR_TRANSITION_PRE_DELAY = 96;

    private static final String CONTROL_REMOTE_APP_TRANSITION_PERMISSION =
            "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS";

    private static final long APP_LAUNCH_DURATION = 450;
    // Use a shorter duration for x or y translation to create a curve effect
    private static final long APP_LAUNCH_CURVED_DURATION = 250;
    private static final long APP_LAUNCH_ALPHA_DURATION = 50;
    private static final long APP_LAUNCH_ALPHA_START_DELAY = 25;

    // We scale the durations for the downward app launch animations (minus the scale animation).
    private static final float APP_LAUNCH_DOWN_DUR_SCALE_FACTOR = 0.8f;
    private static final long APP_LAUNCH_DOWN_DURATION =
            (long) (APP_LAUNCH_DURATION * APP_LAUNCH_DOWN_DUR_SCALE_FACTOR);
    private static final long APP_LAUNCH_DOWN_CURVED_DURATION =
            (long) (APP_LAUNCH_CURVED_DURATION * APP_LAUNCH_DOWN_DUR_SCALE_FACTOR);
    private static final long APP_LAUNCH_ALPHA_DOWN_DURATION =
            (long) (APP_LAUNCH_ALPHA_DURATION * APP_LAUNCH_DOWN_DUR_SCALE_FACTOR);

    public static final int ANIMATION_NAV_FADE_IN_DURATION = 266;
    public static final int ANIMATION_NAV_FADE_OUT_DURATION = 133;
    public static final long ANIMATION_DELAY_NAV_FADE_IN =
            APP_LAUNCH_DURATION - ANIMATION_NAV_FADE_IN_DURATION;
    public static final Interpolator NAV_FADE_IN_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0f, 1f);
    public static final Interpolator NAV_FADE_OUT_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 1f, 1f);

    private static final long CROP_DURATION = 375;
    private static final long RADIUS_DURATION = 375;

    public static final int RECENTS_LAUNCH_DURATION = 336;
    private static final int LAUNCHER_RESUME_START_DELAY = 100;
    private static final int CLOSING_TRANSITION_DURATION_MS = 250;

    public static final int CONTENT_ALPHA_DURATION = 217;
    protected static final int CONTENT_TRANSLATION_DURATION = 350;

    private static final int MAX_NUM_TASKS = 5;

    protected final BaseQuickstepLauncher mLauncher;

    private final DragLayer mDragLayer;
    private final AlphaProperty mDragLayerAlpha;

    final Handler mHandler;
    private final boolean mIsRtl;

    private final float mContentTransY;
    private final float mClosingWindowTransY;
    private final float mMaxShadowRadius;

    private final StartingWindowListener mStartingWindowListener = new StartingWindowListener();

    private DeviceProfile mDeviceProfile;

    private RemoteAnimationProvider mRemoteAnimationProvider;
    // Strong refs to runners which are cleared when the launcher activity is destroyed
    private WrappedAnimationRunnerImpl mWallpaperOpenRunner;
    private WrappedAnimationRunnerImpl mAppLaunchRunner;
    private WrappedAnimationRunnerImpl mKeyguardGoingAwayRunner;

    private WrappedAnimationRunnerImpl mWallpaperOpenTransitionRunner;
    private RemoteTransitionCompat mLauncherOpenTransition;

    private final AnimatorListenerAdapter mForceInvisibleListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            mLauncher.addForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mLauncher.clearForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }
    };

    // Will never be larger than MAX_NUM_TASKS
    private LinkedHashMap<Integer, Integer> mTypeForTaskId;

    public QuickstepTransitionManager(Context context) {
        mLauncher = Launcher.cast(Launcher.getLauncher(context));
        mDragLayer = mLauncher.getDragLayer();
        mDragLayerAlpha = mDragLayer.getAlphaProperty(ALPHA_INDEX_TRANSITIONS);
        mHandler = new Handler(Looper.getMainLooper());
        mIsRtl = Utilities.isRtl(mLauncher.getResources());
        mDeviceProfile = mLauncher.getDeviceProfile();

        Resources res = mLauncher.getResources();
        mContentTransY = res.getDimensionPixelSize(R.dimen.content_trans_y);
        mClosingWindowTransY = res.getDimensionPixelSize(R.dimen.closing_window_trans_y);
        mMaxShadowRadius = res.getDimensionPixelSize(R.dimen.max_shadow_radius);

        mLauncher.addOnDeviceProfileChangeListener(this);

        if (supportsSSplashScreen()) {
            mTypeForTaskId = new LinkedHashMap<Integer, Integer>(MAX_NUM_TASKS) {
                @Override
                protected boolean removeEldestEntry(Entry<Integer, Integer> entry) {
                    return size() > MAX_NUM_TASKS;
                }
            };

            mStartingWindowListener.setTransitionManager(this);
            SystemUiProxy.INSTANCE.get(mLauncher).setStartingWindowListener(
                    mStartingWindowListener);
        }
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        mDeviceProfile = dp;
    }

    /**
     * @return ActivityOptions with remote animations that controls how the window of the opening
     *         targets are displayed.
     */
    public ActivityOptionsWrapper getActivityLaunchOptions(Launcher launcher, View v) {
        boolean fromRecents = isLaunchingFromRecents(v, null /* targets */);
        RunnableList onEndCallback = new RunnableList();
        mAppLaunchRunner = new AppLaunchAnimationRunner(mHandler, v, onEndCallback);
        RemoteAnimationRunnerCompat runner = new WrappedLauncherAnimationRunner<>(
                mHandler, mAppLaunchRunner, true /* startAtFrontOfQueue */);

        // Note that this duration is a guess as we do not know if the animation will be a
        // recents launch or not for sure until we know the opening app targets.
        long duration = fromRecents
                ? RECENTS_LAUNCH_DURATION
                : APP_LAUNCH_DURATION;

        long statusBarTransitionDelay = duration - STATUS_BAR_TRANSITION_DURATION
                - STATUS_BAR_TRANSITION_PRE_DELAY;
        RemoteAnimationAdapterCompat adapterCompat =
                new RemoteAnimationAdapterCompat(runner, duration, statusBarTransitionDelay);
        return new ActivityOptionsWrapper(
                ActivityOptionsCompat.makeRemoteAnimation(adapterCompat), onEndCallback);
    }

    /**
     * Whether the launch is a recents app transition and we should do a launch animation
     * from the recents view. Note that if the remote animation targets are not provided, this
     * may not always be correct as we may resolve the opening app to a task when the animation
     * starts.
     *
     * @param v the view to launch from
     * @param targets apps that are opening/closing
     * @return true if the app is launching from recents, false if it most likely is not
     */
    protected boolean isLaunchingFromRecents(@NonNull View v,
            @Nullable RemoteAnimationTargetCompat[] targets) {
        return mLauncher.getStateManager().getState().overviewUi
                && findTaskViewToLaunch(mLauncher.getOverviewPanel(), v, targets) != null;
    }

    /**
     * Composes the animations for a launch from the recents list.
     *
     * @param anim the animator set to add to
     * @param v the launching view
     * @param appTargets the apps that are opening/closing
     * @param launcherClosing true if the launcher app is closing
     */
    protected void composeRecentsLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTargetCompat[] appTargets,
            @NonNull RemoteAnimationTargetCompat[] wallpaperTargets,
            @NonNull RemoteAnimationTargetCompat[] nonAppTargets, boolean launcherClosing) {
        TaskViewUtils.composeRecentsLaunchAnimator(anim, v, appTargets, wallpaperTargets,
                nonAppTargets, launcherClosing, mLauncher.getStateManager(),
                mLauncher.getOverviewPanel(), mLauncher.getDepthController());
    }

    private boolean areAllTargetsTranslucent(@NonNull RemoteAnimationTargetCompat[] targets) {
        boolean isAllOpeningTargetTrs = true;
        for (int i = 0; i < targets.length; i++) {
            RemoteAnimationTargetCompat target = targets[i];
            if (target.mode == MODE_OPENING) {
                isAllOpeningTargetTrs &= target.isTranslucent;
            }
            if (!isAllOpeningTargetTrs) break;
        }
        return isAllOpeningTargetTrs;
    }

    /**
     * Compose the animations for a launch from the app icon.
     *
     * @param anim the animation to add to
     * @param v the launching view with the icon
     * @param appTargets the list of opening/closing apps
     * @param launcherClosing true if launcher is closing
     */
    private void composeIconLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTargetCompat[] appTargets,
            @NonNull RemoteAnimationTargetCompat[] wallpaperTargets,
            @NonNull RemoteAnimationTargetCompat[] nonAppTargets,
            boolean launcherClosing) {
        // Set the state animation first so that any state listeners are called
        // before our internal listeners.
        mLauncher.getStateManager().setCurrentAnimation(anim);

        final int rotationChange = getRotationChange(appTargets);
        // Note: the targetBounds are relative to the launcher
        Rect windowTargetBounds = getWindowTargetBounds(appTargets, rotationChange);
        anim.play(getOpeningWindowAnimators(v, appTargets, wallpaperTargets, nonAppTargets,
                windowTargetBounds, areAllTargetsTranslucent(appTargets), rotationChange));
        if (launcherClosing) {
            Pair<AnimatorSet, Runnable> launcherContentAnimator =
                    getLauncherContentAnimator(true /* isAppOpening */,
                            new float[] {0, -mContentTransY});
            anim.play(launcherContentAnimator.first);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    launcherContentAnimator.second.run();
                }
            });
        } else {
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mLauncher.addOnResumeCallback(() ->
                            ObjectAnimator.ofFloat(mLauncher.getDepthController(), DEPTH,
                            mLauncher.getStateManager().getState().getDepth(mLauncher)).start());
                }
            });
        }
    }

    /**
     * Return the window bounds of the opening target.
     * In multiwindow mode, we need to get the final size of the opening app window target to help
     * figure out where the floating view should animate to.
     */
    private Rect getWindowTargetBounds(@NonNull RemoteAnimationTargetCompat[] appTargets,
            int rotationChange) {
        RemoteAnimationTargetCompat target = null;
        for (RemoteAnimationTargetCompat t : appTargets) {
            if (t.mode != MODE_OPENING) continue;
            target = t;
            break;
        }
        if (target == null) return new Rect(0, 0, mDeviceProfile.widthPx, mDeviceProfile.heightPx);
        final Rect bounds = new Rect(target.screenSpaceBounds);
        if (target.localBounds != null) {
            bounds.set(target.localBounds);
        } else {
            bounds.offsetTo(target.position.x, target.position.y);
        }
        if (rotationChange != 0) {
            if ((rotationChange % 2) == 1) {
                // undoing rotation, so our "original" parent size is actually flipped
                Utilities.rotateBounds(bounds, mDeviceProfile.heightPx, mDeviceProfile.widthPx,
                        4 - rotationChange);
            } else {
                Utilities.rotateBounds(bounds, mDeviceProfile.widthPx, mDeviceProfile.heightPx,
                        4 - rotationChange);
            }
        }
        return bounds;
    }

    private int getOpeningTaskId(RemoteAnimationTargetCompat[] appTargets) {
        for (RemoteAnimationTargetCompat target : appTargets) {
            if (target.mode == MODE_OPENING) {
                return target.taskId;
            }
        }
        return -1;
    }

    public void setRemoteAnimationProvider(final RemoteAnimationProvider animationProvider,
            CancellationSignal cancellationSignal) {
        mRemoteAnimationProvider = animationProvider;
        cancellationSignal.setOnCancelListener(() -> {
            if (animationProvider == mRemoteAnimationProvider) {
                mRemoteAnimationProvider = null;
            }
        });
    }

    /**
     * Content is everything on screen except the background and the floating view (if any).
     *
     * @param isAppOpening True when this is called when an app is opening.
     *                     False when this is called when an app is closing.
     * @param trans Array that contains the start and end translation values for the content.
     */
    private Pair<AnimatorSet, Runnable> getLauncherContentAnimator(boolean isAppOpening,
            float[] trans) {
        AnimatorSet launcherAnimator = new AnimatorSet();
        Runnable endListener;

        float[] alphas = isAppOpening
                ? new float[] {1, 0}
                : new float[] {0, 1};

        if (mLauncher.isInState(ALL_APPS)) {
            // All Apps in portrait mode is full screen, so we only animate AllAppsContainerView.
            final View appsView = mLauncher.getAppsView();
            final float startAlpha = appsView.getAlpha();
            final float startY = appsView.getTranslationY();
            appsView.setAlpha(alphas[0]);
            appsView.setTranslationY(trans[0]);

            ObjectAnimator alpha = ObjectAnimator.ofFloat(appsView, View.ALPHA, alphas);
            alpha.setDuration(CONTENT_ALPHA_DURATION);
            alpha.setInterpolator(LINEAR);
            appsView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            alpha.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    appsView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            });
            ObjectAnimator transY = ObjectAnimator.ofFloat(appsView, View.TRANSLATION_Y, trans);
            transY.setInterpolator(AGGRESSIVE_EASE);
            transY.setDuration(CONTENT_TRANSLATION_DURATION);

            launcherAnimator.play(alpha);
            launcherAnimator.play(transY);

            endListener = () -> {
                appsView.setAlpha(startAlpha);
                appsView.setTranslationY(startY);
                appsView.setLayerType(View.LAYER_TYPE_NONE, null);
            };
        } else if (mLauncher.isInState(OVERVIEW)) {
            endListener = composeViewContentAnimator(launcherAnimator, alphas, trans);
        } else {
            mDragLayerAlpha.setValue(alphas[0]);
            ObjectAnimator alpha =
                    ObjectAnimator.ofFloat(mDragLayerAlpha, MultiValueAlpha.VALUE, alphas);
            alpha.setDuration(CONTENT_ALPHA_DURATION);
            alpha.setInterpolator(LINEAR);
            launcherAnimator.play(alpha);

            Workspace workspace = mLauncher.getWorkspace();
            View currentPage = ((CellLayout) workspace.getChildAt(workspace.getCurrentPage()))
                    .getShortcutsAndWidgets();
            View hotseat = mLauncher.getHotseat();
            View qsb = mLauncher.findViewById(R.id.search_container_all_apps);

            currentPage.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            hotseat.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            qsb.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            launcherAnimator.play(ObjectAnimator.ofFloat(currentPage, View.TRANSLATION_Y, trans));
            launcherAnimator.play(ObjectAnimator.ofFloat(hotseat, View.TRANSLATION_Y, trans));
            launcherAnimator.play(ObjectAnimator.ofFloat(qsb, View.TRANSLATION_Y, trans));

            // Pause page indicator animations as they lead to layer trashing.
            mLauncher.getWorkspace().getPageIndicator().pauseAnimations();

            endListener = () -> {
                currentPage.setTranslationY(0);
                hotseat.setTranslationY(0);
                qsb.setTranslationY(0);

                currentPage.setLayerType(View.LAYER_TYPE_NONE, null);
                hotseat.setLayerType(View.LAYER_TYPE_NONE, null);
                qsb.setLayerType(View.LAYER_TYPE_NONE, null);

                mDragLayerAlpha.setValue(1f);
                mLauncher.getWorkspace().getPageIndicator().skipAnimationsToEnd();
            };
        }
        return new Pair<>(launcherAnimator, endListener);
    }

    /**
     * Compose recents view alpha and translation Y animation when launcher opens/closes apps.
     *
     * @param anim the animator set to add to
     * @param alphas the alphas to animate to over time
     * @param trans the translation Y values to animator to over time
     * @return listener to run when the animation ends
     */
    protected Runnable composeViewContentAnimator(@NonNull AnimatorSet anim,
            float[] alphas, float[] trans) {
        RecentsView overview = mLauncher.getOverviewPanel();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(overview,
                RecentsView.CONTENT_ALPHA, alphas);
        alpha.setDuration(CONTENT_ALPHA_DURATION);
        alpha.setInterpolator(LINEAR);
        anim.play(alpha);
        overview.setFreezeViewVisibility(true);

        ObjectAnimator transY = ObjectAnimator.ofFloat(overview, View.TRANSLATION_Y, trans);
        transY.setInterpolator(AGGRESSIVE_EASE);
        transY.setDuration(CONTENT_TRANSLATION_DURATION);
        anim.play(transY);

        return () -> {
            overview.setFreezeViewVisibility(false);
            overview.setTranslationY(0);
            mLauncher.getStateManager().reapplyState();
        };
    }

    /**
     * @return Animator that controls the window of the opening targets from app icons.
     */
    private Animator getOpeningWindowAnimators(View v,
            RemoteAnimationTargetCompat[] appTargets,
            RemoteAnimationTargetCompat[] wallpaperTargets,
            RemoteAnimationTargetCompat[] nonAppTargets,
            Rect windowTargetBounds, boolean appTargetsAreTranslucent, int rotationChange) {
        RectF launcherIconBounds = new RectF();
        FloatingIconView floatingView = FloatingIconView.getFloatingIconView(mLauncher, v,
                !appTargetsAreTranslucent, launcherIconBounds, true /* isOpening */);
        Rect crop = new Rect();
        Matrix matrix = new Matrix();

        RemoteAnimationTargets openingTargets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);
        SurfaceTransactionApplier surfaceApplier =
                new SurfaceTransactionApplier(floatingView);
        openingTargets.addReleaseCheck(surfaceApplier);
        RemoteAnimationTargetCompat navBarTarget = openingTargets.getNavBarRemoteAnimationTarget();

        int[] dragLayerBounds = new int[2];
        mDragLayer.getLocationOnScreen(dragLayerBounds);

        final boolean hasSplashScreen;
        if (supportsSSplashScreen()) {
            int taskId = getOpeningTaskId(appTargets);
            int type = mTypeForTaskId.getOrDefault(taskId, STARTING_WINDOW_TYPE_NONE);
            mTypeForTaskId.remove(taskId);
            hasSplashScreen = type == STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else {
            hasSplashScreen = false;
        }

        AnimOpenProperties prop = new AnimOpenProperties(mLauncher.getResources(), mDeviceProfile,
                windowTargetBounds, launcherIconBounds, v, dragLayerBounds[0], dragLayerBounds[1],
                hasSplashScreen);
        int left = (int) (prop.cropCenterXStart - prop.cropWidthStart / 2);
        int top = (int) (prop.cropCenterYStart - prop.cropHeightStart / 2);
        int right = (int) (left + prop.cropWidthStart);
        int bottom = (int) (top + prop.cropHeightStart);
        // Set the crop here so we can calculate the corner radius below.
        crop.set(left, top, right, bottom);

        RectF floatingIconBounds = new RectF();
        RectF tmpRectF = new RectF();
        Point tmpPos = new Point();

        AnimatorSet animatorSet = new AnimatorSet();
        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(APP_LAUNCH_DURATION);
        appAnimator.setInterpolator(LINEAR);
        appAnimator.addListener(floatingView);
        appAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (v instanceof BubbleTextView) {
                    ((BubbleTextView) v).setStayPressed(false);
                }
                openingTargets.release();
            }
        });

        final float initialWindowRadius = supportsRoundedCornersOnWindows(mLauncher.getResources())
                ? Math.max(crop.width(), crop.height()) / 2f
                : 0f;
        final float finalWindowRadius = mDeviceProfile.isMultiWindowMode
                ? 0 : getWindowCornerRadius(mLauncher.getResources());
        final float finalShadowRadius = appTargetsAreTranslucent ? 0 : mMaxShadowRadius;

        appAnimator.addUpdateListener(new MultiValueUpdateListener() {
            FloatProp mDx = new FloatProp(0, prop.dX, 0, prop.xDuration, AGGRESSIVE_EASE);
            FloatProp mDy = new FloatProp(0, prop.dY, 0, prop.yDuration, AGGRESSIVE_EASE);

            FloatProp mIconScaleToFitScreen = new FloatProp(prop.initialAppIconScale,
                    prop.finalAppIconScale, 0, APP_LAUNCH_DURATION, EXAGGERATED_EASE);
            FloatProp mIconAlpha = new FloatProp(prop.iconAlphaStart, 0f,
                    APP_LAUNCH_ALPHA_START_DELAY, prop.alphaDuration, LINEAR);

            FloatProp mWindowRadius = new FloatProp(initialWindowRadius, finalWindowRadius, 0,
                    RADIUS_DURATION, EXAGGERATED_EASE);
            FloatProp mShadowRadius = new FloatProp(0, finalShadowRadius, 0,
                    APP_LAUNCH_DURATION, EXAGGERATED_EASE);

            FloatProp mCropRectCenterX = new FloatProp(prop.cropCenterXStart, prop.cropCenterXEnd,
                    0, CROP_DURATION, EXAGGERATED_EASE);
            FloatProp mCropRectCenterY = new FloatProp(prop.cropCenterYStart, prop.cropCenterYEnd,
                    0, CROP_DURATION, EXAGGERATED_EASE);
            FloatProp mCropRectWidth = new FloatProp(prop.cropWidthStart, prop.cropWidthEnd, 0,
                    CROP_DURATION, EXAGGERATED_EASE);
            FloatProp mCropRectHeight = new FloatProp(prop.cropHeightStart, prop.cropHeightEnd, 0,
                    CROP_DURATION, EXAGGERATED_EASE);

            FloatProp mNavFadeOut = new FloatProp(1f, 0f, 0, ANIMATION_NAV_FADE_OUT_DURATION,
                    NAV_FADE_OUT_INTERPOLATOR);
            FloatProp mNavFadeIn = new FloatProp(0f, 1f, ANIMATION_DELAY_NAV_FADE_IN,
                    ANIMATION_NAV_FADE_IN_DURATION, NAV_FADE_IN_INTERPOLATOR);

            @Override
            public void onUpdate(float percent) {
                // Calculate the size of the scaled icon.
                float iconWidth = launcherIconBounds.width() * mIconScaleToFitScreen.value;
                float iconHeight = launcherIconBounds.height() * mIconScaleToFitScreen.value;

                int left = (int) (mCropRectCenterX.value - mCropRectWidth.value / 2);
                int top = (int) (mCropRectCenterY.value - mCropRectHeight.value / 2);
                int right = (int) (left + mCropRectWidth.value);
                int bottom = (int) (top + mCropRectHeight.value);
                crop.set(left, top, right, bottom);

                final int windowCropWidth = crop.width();
                final int windowCropHeight = crop.height();
                if (rotationChange != 0) {
                    Utilities.rotateBounds(crop, mDeviceProfile.widthPx,
                            mDeviceProfile.heightPx, rotationChange);
                }

                // Scale the size of the icon to match the size of the window crop.
                float scaleX = iconWidth / windowCropWidth;
                float scaleY = iconHeight / windowCropHeight;
                float scale = Math.min(1f, Math.max(scaleX, scaleY));

                float scaledCropWidth = windowCropWidth * scale;
                float scaledCropHeight = windowCropHeight * scale;
                float offsetX  = (scaledCropWidth - iconWidth) / 2;
                float offsetY = (scaledCropHeight - iconHeight) / 2;

                // Calculate the window position to match the icon position.
                tmpRectF.set(launcherIconBounds);
                tmpRectF.offset(dragLayerBounds[0], dragLayerBounds[1]);
                tmpRectF.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(tmpRectF, mIconScaleToFitScreen.value);
                float windowTransX0 = tmpRectF.left - offsetX - crop.left * scale;
                float windowTransY0 = tmpRectF.top - offsetY - crop.top * scale;

                // Calculate the icon position.
                floatingIconBounds.set(launcherIconBounds);
                floatingIconBounds.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(floatingIconBounds, mIconScaleToFitScreen.value);
                floatingIconBounds.left -= offsetX;
                floatingIconBounds.top -= offsetY;
                floatingIconBounds.right += offsetX;
                floatingIconBounds.bottom += offsetY;

                SurfaceParams[] params = new SurfaceParams[appTargets.length];
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTargetCompat target = appTargets[i];
                    SurfaceParams.Builder builder = new SurfaceParams.Builder(target.leash);

                    if (target.mode == MODE_OPENING) {
                        matrix.setScale(scale, scale);
                        if (rotationChange == 1) {
                            matrix.postTranslate(windowTransY0,
                                    mDeviceProfile.widthPx - (windowTransX0 + scaledCropWidth));
                        } else if (rotationChange == 2) {
                            matrix.postTranslate(
                                    mDeviceProfile.widthPx - (windowTransX0 + scaledCropWidth),
                                    mDeviceProfile.heightPx - (windowTransY0 + scaledCropHeight));
                        } else if (rotationChange == 3) {
                            matrix.postTranslate(
                                    mDeviceProfile.heightPx - (windowTransY0 + scaledCropHeight),
                                    windowTransX0);
                        } else {
                            matrix.postTranslate(windowTransX0, windowTransY0);
                        }

                        floatingView.update(mIconAlpha.value, 255, floatingIconBounds, percent, 0f,
                                mWindowRadius.value * scale, true /* isOpening */);
                        builder.withMatrix(matrix)
                                .withWindowCrop(crop)
                                .withAlpha(1f - mIconAlpha.value)
                                .withCornerRadius(mWindowRadius.value)
                                .withShadowRadius(mShadowRadius.value);
                    } else if (target.mode == MODE_CLOSING) {
                        if (target.localBounds != null) {
                            final Rect localBounds = target.localBounds;
                            tmpPos.set(target.localBounds.left, target.localBounds.top);
                        } else {
                            tmpPos.set(target.position.x, target.position.y);
                        }
                        final Rect crop = new Rect(target.screenSpaceBounds);
                        crop.offsetTo(0, 0);

                        if ((rotationChange % 2) == 1) {
                            int tmp = crop.right;
                            crop.right = crop.bottom;
                            crop.bottom = tmp;
                            tmp = tmpPos.x;
                            tmpPos.x = tmpPos.y;
                            tmpPos.y = tmp;
                        }
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.withMatrix(matrix)
                                .withWindowCrop(crop)
                                .withAlpha(1f);
                    }
                    params[i] = builder.build();
                }
                surfaceApplier.scheduleApply(params);

                if (navBarTarget != null) {
                    final SurfaceParams.Builder navBuilder =
                            new SurfaceParams.Builder(navBarTarget.leash);
                    if (mNavFadeIn.value > mNavFadeIn.getStartValue()) {
                        matrix.setScale(scale, scale);
                        matrix.postTranslate(windowTransX0, windowTransY0);
                        navBuilder.withMatrix(matrix)
                                .withWindowCrop(crop)
                                .withAlpha(mNavFadeIn.value);
                    } else {
                        navBuilder.withAlpha(mNavFadeOut.value);
                    }
                    surfaceApplier.scheduleApply(navBuilder.build());
                }
            }
        });

        // When launching an app from overview that doesn't map to a task, we still want to just
        // blur the wallpaper instead of the launcher surface as well
        boolean allowBlurringLauncher = mLauncher.getStateManager().getState() != OVERVIEW;
        DepthController depthController = mLauncher.getDepthController();
        ObjectAnimator backgroundRadiusAnim = ObjectAnimator.ofFloat(depthController, DEPTH,
                BACKGROUND_APP.getDepth(mLauncher))
                .setDuration(APP_LAUNCH_DURATION);
        if (allowBlurringLauncher) {
            depthController.setSurfaceToApp(RemoteAnimationProvider.findLowestOpaqueLayerTarget(
                    appTargets, MODE_OPENING));
            backgroundRadiusAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    depthController.setSurfaceToApp(null);
                }
            });
        }

        animatorSet.playTogether(appAnimator, backgroundRadiusAnim);
        return animatorSet;
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        if (hasControlRemoteAppTransitionPermission()) {
            mWallpaperOpenRunner = createWallpaperOpenRunner(false /* fromUnlock */);

            RemoteAnimationDefinitionCompat definition = new RemoteAnimationDefinitionCompat();
            definition.addRemoteAnimation(WindowManagerWrapper.TRANSIT_WALLPAPER_OPEN,
                    WindowManagerWrapper.ACTIVITY_TYPE_STANDARD,
                    new RemoteAnimationAdapterCompat(
                            new WrappedLauncherAnimationRunner<>(mHandler, mWallpaperOpenRunner,
                                    false /* startAtFrontOfQueue */),
                            CLOSING_TRANSITION_DURATION_MS, 0 /* statusBarTransitionDelay */));

            if (KEYGUARD_ANIMATION.get()) {
                mKeyguardGoingAwayRunner = createWallpaperOpenRunner(true /* fromUnlock */);
                definition.addRemoteAnimation(
                        WindowManagerWrapper.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                        new RemoteAnimationAdapterCompat(
                                new WrappedLauncherAnimationRunner<>(
                                        mHandler, mKeyguardGoingAwayRunner,
                                        true /* startAtFrontOfQueue */),
                                CLOSING_TRANSITION_DURATION_MS, 0 /* statusBarTransitionDelay */));
            }

            new ActivityCompat(mLauncher).registerRemoteAnimations(definition);
        }
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteTransitions() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        if (hasControlRemoteAppTransitionPermission()) {
            mWallpaperOpenTransitionRunner = createWallpaperOpenRunner(false /* fromUnlock */);
            mLauncherOpenTransition = RemoteAnimationAdapterCompat.buildRemoteTransition(
                    new WrappedLauncherAnimationRunner<>(mHandler, mWallpaperOpenTransitionRunner,
                            false /* startAtFrontOfQueue */));
            mLauncherOpenTransition.addHomeOpenCheck();
            SystemUiProxy.INSTANCE.getNoCreate().registerRemoteTransition(mLauncherOpenTransition);
        }
    }

    public void onActivityDestroyed() {
        unregisterRemoteAnimations();
        unregisterRemoteTransitions();
        mStartingWindowListener.setTransitionManager(null);
        SystemUiProxy.INSTANCE.getNoCreate().setStartingWindowListener(null);
    }

    private void unregisterRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        if (hasControlRemoteAppTransitionPermission()) {
            new ActivityCompat(mLauncher).unregisterRemoteAnimations();

            // Also clear strong references to the runners registered with the remote animation
            // definition so we don't have to wait for the system gc
            mWallpaperOpenRunner = null;
            mAppLaunchRunner = null;
            mKeyguardGoingAwayRunner = null;
        }
    }

    private void unregisterRemoteTransitions() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        if (hasControlRemoteAppTransitionPermission()) {
            if (mLauncherOpenTransition == null) return;
            SystemUiProxy.INSTANCE.getNoCreate().unregisterRemoteTransition(
                    mLauncherOpenTransition);
            mLauncherOpenTransition = null;
            mWallpaperOpenTransitionRunner = null;
        }
    }

    private boolean launcherIsATargetWithMode(RemoteAnimationTargetCompat[] targets, int mode) {
        return taskIsATargetWithMode(targets, mLauncher.getTaskId(), mode);
    }

    /**
     * @return Runner that plays when user goes to Launcher
     *         ie. pressing home, swiping up from nav bar.
     */
    WrappedAnimationRunnerImpl createWallpaperOpenRunner(boolean fromUnlock) {
        return new WallpaperOpenLauncherAnimationRunner(mHandler, fromUnlock);
    }

    /**
     * Animator that controls the transformations of the windows when unlocking the device.
     */
    private Animator getUnlockWindowAnimator(RemoteAnimationTargetCompat[] appTargets,
            RemoteAnimationTargetCompat[] wallpaperTargets) {
        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(mDragLayer);
        ValueAnimator unlockAnimator = ValueAnimator.ofFloat(0, 1);
        unlockAnimator.setDuration(CLOSING_TRANSITION_DURATION_MS);
        float cornerRadius = mDeviceProfile.isMultiWindowMode ? 0 :
                QuickStepContract.getWindowCornerRadius(mLauncher.getResources());
        unlockAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                SurfaceParams[] params = new SurfaceParams[appTargets.length];
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTargetCompat target = appTargets[i];
                    params[i] = new SurfaceParams.Builder(target.leash)
                            .withAlpha(1f)
                            .withWindowCrop(target.screenSpaceBounds)
                            .withCornerRadius(cornerRadius)
                            .build();
                }
                surfaceApplier.scheduleApply(params);
            }
        });
        return unlockAnimator;
    }

    private static int getRotationChange(RemoteAnimationTargetCompat[] appTargets) {
        int rotationChange = 0;
        for (RemoteAnimationTargetCompat target : appTargets) {
            if (Math.abs(target.rotationChange) > Math.abs(rotationChange)) {
                rotationChange = target.rotationChange;
            }
        }
        return rotationChange;
    }

    /**
     * Animator that controls the transformations of the windows the targets that are closing.
     */
    private Animator getClosingWindowAnimators(RemoteAnimationTargetCompat[] appTargets,
            RemoteAnimationTargetCompat[] wallpaperTargets) {
        final int rotationChange = getRotationChange(appTargets);
        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(mDragLayer);
        Matrix matrix = new Matrix();
        Point tmpPos = new Point();
        Rect tmpRect = new Rect();
        ValueAnimator closingAnimator = ValueAnimator.ofFloat(0, 1);
        int duration = CLOSING_TRANSITION_DURATION_MS;
        float windowCornerRadius = mDeviceProfile.isMultiWindowMode
                ? 0 : getWindowCornerRadius(mLauncher.getResources());
        float startShadowRadius = areAllTargetsTranslucent(appTargets) ? 0 : mMaxShadowRadius;
        closingAnimator.setDuration(duration);
        closingAnimator.addUpdateListener(new MultiValueUpdateListener() {
            FloatProp mDy = new FloatProp(0, mClosingWindowTransY, 0, duration, DEACCEL_1_7);
            FloatProp mScale = new FloatProp(1f, 1f, 0, duration, DEACCEL_1_7);
            FloatProp mAlpha = new FloatProp(1f, 0f, 25, 125, LINEAR);
            FloatProp mShadowRadius = new FloatProp(startShadowRadius, 0, 0, duration,
                    DEACCEL_1_7);

            @Override
            public void onUpdate(float percent) {
                SurfaceParams[] params = new SurfaceParams[appTargets.length];
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTargetCompat target = appTargets[i];
                    SurfaceParams.Builder builder = new SurfaceParams.Builder(target.leash);

                    if (target.localBounds != null) {
                        tmpPos.set(target.localBounds.left, target.localBounds.top);
                    } else {
                        tmpPos.set(target.position.x, target.position.y);
                    }

                    final Rect crop = new Rect(target.screenSpaceBounds);
                    crop.offsetTo(0, 0);
                    if (target.mode == MODE_CLOSING) {
                        tmpRect.set(target.screenSpaceBounds);
                        if ((rotationChange % 2) != 0) {
                            final int right = crop.right;
                            crop.right = crop.bottom;
                            crop.bottom = right;
                        }
                        matrix.setScale(mScale.value, mScale.value,
                                tmpRect.centerX(),
                                tmpRect.centerY());
                        matrix.postTranslate(0, mDy.value);
                        matrix.postTranslate(tmpPos.x, tmpPos.y);
                        builder.withMatrix(matrix)
                                .withWindowCrop(crop)
                                .withAlpha(mAlpha.value)
                                .withCornerRadius(windowCornerRadius)
                                .withShadowRadius(mShadowRadius.value);
                    } else if (target.mode == MODE_OPENING) {
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.withMatrix(matrix)
                                .withWindowCrop(crop)
                                .withAlpha(1f);
                    }
                    params[i] = builder.build();
                }
                surfaceApplier.scheduleApply(params);
            }
        });

        return closingAnimator;
    }

    private boolean supportsSSplashScreen() {
        return hasControlRemoteAppTransitionPermission()
                && Utilities.ATLEAST_S
                && ENABLE_SHELL_STARTING_SURFACE;
    }

    /**
     * Returns true if we have permission to control remote app transisions
     */
    public boolean hasControlRemoteAppTransitionPermission() {
        return mLauncher.checkSelfPermission(CONTROL_REMOTE_APP_TRANSITION_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void addCujInstrumentation(Animator anim, int cuj) {
        anim.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                InteractionJankMonitorWrapper.begin(mDragLayer, cuj);
                super.onAnimationStart(animation);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                InteractionJankMonitorWrapper.cancel(cuj);
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                InteractionJankMonitorWrapper.end(cuj);
            }
        });
    }

    /**
     * Remote animation runner for animation from the app to Launcher, including recents.
     */
    protected class WallpaperOpenLauncherAnimationRunner implements WrappedAnimationRunnerImpl {

        private final Handler mHandler;
        private final boolean mFromUnlock;

        public WallpaperOpenLauncherAnimationRunner(Handler handler, boolean fromUnlock) {
            mHandler = handler;
            mFromUnlock = fromUnlock;
        }

        @Override
        public void onCreateAnimation(int transit,
                RemoteAnimationTargetCompat[] appTargets,
                RemoteAnimationTargetCompat[] wallpaperTargets,
                RemoteAnimationTargetCompat[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            if (mLauncher.isDestroyed()) {
                AnimatorSet anim = new AnimatorSet();
                anim.play(getClosingWindowAnimators(appTargets, wallpaperTargets));
                result.setAnimation(anim, mLauncher.getApplicationContext());
                return;
            }

            if (!mLauncher.hasBeenResumed()) {
                // If launcher is not resumed, wait until new async-frame after resume
                mLauncher.addOnResumeCallback(() ->
                        postAsyncCallback(mHandler, () ->
                                onCreateAnimation(transit, appTargets, wallpaperTargets,
                                        nonAppTargets, result)));
                return;
            }

            if (mLauncher.hasSomeInvisibleFlag(PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION)) {
                mLauncher.addForceInvisibleFlag(INVISIBLE_BY_PENDING_FLAGS);
                mLauncher.getStateManager().moveToRestState();
            }

            AnimatorSet anim = null;
            RemoteAnimationProvider provider = mRemoteAnimationProvider;
            if (provider != null) {
                anim = provider.createWindowAnimation(appTargets, wallpaperTargets);
            }

            if (anim == null) {
                anim = new AnimatorSet();
                anim.play(mFromUnlock
                        ? getUnlockWindowAnimator(appTargets, wallpaperTargets)
                        : getClosingWindowAnimators(appTargets, wallpaperTargets));

                // Normally, we run the launcher content animation when we are transitioning
                // home, but if home is already visible, then we don't want to animate the
                // contents of launcher unless we know that we are animating home as a result
                // of the home button press with quickstep, which will result in launcher being
                // started on touch down, prior to the animation home (and won't be in the
                // targets list because it is already visible). In that case, we force
                // invisibility on touch down, and only reset it after the animation to home
                // is initialized.
                if (launcherIsATargetWithMode(appTargets, MODE_OPENING)
                        || mLauncher.isForceInvisible()) {
                    addCujInstrumentation(
                            anim, InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);
                    // Only register the content animation for cancellation when state changes
                    mLauncher.getStateManager().setCurrentAnimation(anim);

                    if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                        Pair<AnimatorSet, Runnable> contentAnimator =
                                getLauncherContentAnimator(false /* isAppOpening */,
                                        new float[] {-mContentTransY, 0});
                        contentAnimator.first.setStartDelay(LAUNCHER_RESUME_START_DELAY);
                        anim.play(contentAnimator.first);
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                contentAnimator.second.run();
                            }
                        });
                    } else {
                        float velocityPxPerS = DynamicResource.provider(mLauncher)
                                .getDimension(R.dimen.unlock_staggered_velocity_dp_per_s);
                        anim.play(new StaggeredWorkspaceAnim(mLauncher, velocityPxPerS, false)
                                .getAnimators());
                    }
                }
            }

            mLauncher.clearForceInvisibleFlag(INVISIBLE_ALL);
            result.setAnimation(anim, mLauncher);
        }
    }

    /**
     * Remote animation runner for animation to launch an app.
     */
    private class AppLaunchAnimationRunner implements WrappedAnimationRunnerImpl {

        private static final String TRANSITION_LAUNCH_FROM_RECENTS = "transition:LaunchFromRecents";
        private static final String TRANSITION_LAUNCH_FROM_ICON = "transition:LaunchFromIcon";

        private final Handler mHandler;
        private final View mV;
        private final RunnableList mOnEndCallback;

        AppLaunchAnimationRunner(Handler handler, View v, RunnableList onEndCallback) {
            mHandler = handler;
            mV = v;
            mOnEndCallback = onEndCallback;
        }

        @Override
        public void onCreateAnimation(int transit,
                RemoteAnimationTargetCompat[] appTargets,
                RemoteAnimationTargetCompat[] wallpaperTargets,
                RemoteAnimationTargetCompat[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            AnimatorSet anim = new AnimatorSet();
            boolean launcherClosing =
                    launcherIsATargetWithMode(appTargets, MODE_CLOSING);

            final boolean launchingFromRecents = isLaunchingFromRecents(mV, appTargets);
            final boolean launchingFromTaskbar = mLauncher.isViewInTaskbar(mV);
            if (launchingFromRecents) {
                composeRecentsLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing);
            } else if (launchingFromTaskbar) {
                // TODO
            } else {
                composeIconLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing);
            }

            addCujInstrumentation(anim,
                    launchingFromRecents
                            ? InteractionJankMonitorWrapper.CUJ_APP_LAUNCH_FROM_RECENTS
                            : InteractionJankMonitorWrapper.CUJ_APP_LAUNCH_FROM_ICON);

            if (launcherClosing) {
                anim.addListener(mForceInvisibleListener);
            }

            result.setAnimation(anim, mLauncher, mOnEndCallback::executeAllAndDestroy);
        }
    }

    /**
     * Class that holds all the variables for the app open animation.
     */
    static class AnimOpenProperties {

        public final int cropCenterXStart;
        public final int cropCenterYStart;
        public final int cropWidthStart;
        public final int cropHeightStart;

        public final int cropCenterXEnd;
        public final int cropCenterYEnd;
        public final int cropWidthEnd;
        public final int cropHeightEnd;

        public final float dX;
        public final float dY;

        public final long xDuration;
        public final long yDuration;
        public final long alphaDuration;

        public final float initialAppIconScale;
        public final float finalAppIconScale;

        public final float iconAlphaStart;

        AnimOpenProperties(Resources r, DeviceProfile dp, Rect windowTargetBounds,
                RectF launcherIconBounds, View view, int dragLayerLeft, int dragLayerTop,
                boolean hasSplashScreen) {
            // Scale the app icon to take up the entire screen. This simplifies the math when
            // animating the app window position / scale.
            float smallestSize = Math.min(windowTargetBounds.height(), windowTargetBounds.width());
            float maxScaleX = smallestSize / launcherIconBounds.width();
            float maxScaleY = smallestSize / launcherIconBounds.height();
            float iconStartScale = 1f;
            if (view instanceof BubbleTextView && !(view.getParent() instanceof DeepShortcutView)) {
                Drawable dr = ((BubbleTextView) view).getIcon();
                if (dr instanceof FastBitmapDrawable) {
                    iconStartScale = ((FastBitmapDrawable) dr).getAnimatedScale();
                }
            }

            initialAppIconScale = iconStartScale;
            finalAppIconScale = Math.max(maxScaleX, maxScaleY);

            // Animate the app icon to the center of the window bounds in screen coordinates.
            float centerX = windowTargetBounds.centerX() - dragLayerLeft;
            float centerY = windowTargetBounds.centerY() - dragLayerTop;

            dX = centerX - launcherIconBounds.centerX();
            dY = centerY - launcherIconBounds.centerY();

            boolean useUpwardAnimation = launcherIconBounds.top > centerY
                    || Math.abs(dY) < dp.cellHeightPx;
            xDuration = useUpwardAnimation ? APP_LAUNCH_CURVED_DURATION
                    : APP_LAUNCH_DOWN_DURATION;
            yDuration = useUpwardAnimation ? APP_LAUNCH_DURATION
                    : APP_LAUNCH_DOWN_CURVED_DURATION;
            alphaDuration = useUpwardAnimation ? APP_LAUNCH_ALPHA_DURATION
                    : APP_LAUNCH_ALPHA_DOWN_DURATION;

            iconAlphaStart = hasSplashScreen ? 0 : 1f;

            // TOOD: Share value from shell when available.
            final float windowIconSize = Utilities.pxFromSp(108, r.getDisplayMetrics());

            cropCenterXStart = windowTargetBounds.centerX();
            cropCenterYStart = windowTargetBounds.centerY();

            cropWidthStart = (int) windowIconSize;
            cropHeightStart = (int) windowIconSize;

            cropWidthEnd = windowTargetBounds.width();
            cropHeightEnd = windowTargetBounds.height();

            cropCenterXEnd = windowTargetBounds.centerX();
            cropCenterYEnd = windowTargetBounds.centerY();
        }
    }

    private static class StartingWindowListener extends IStartingWindowListener.Stub {
        private QuickstepTransitionManager mTransitionManager;

        public void setTransitionManager(QuickstepTransitionManager transitionManager) {
            mTransitionManager = transitionManager;
        }

        @Override
        public void onTaskLaunching(int taskId, int supportedType) {
            mTransitionManager.mTypeForTaskId.put(taskId, supportedType);
        }
    }
}
