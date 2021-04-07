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

import static com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo.TOUCHABLE_INSETS_FRAME;
import static com.android.systemui.shared.system.ViewTreeObserverWrapper.InsetsInfo.TOUCHABLE_INSETS_REGION;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.views.BaseDragLayer;
import com.android.systemui.shared.system.ViewTreeObserverWrapper;

/**
 * Top-level ViewGroup that hosts the TaskbarView as well as Views created by it such as Folder.
 */
public class TaskbarContainerView extends BaseDragLayer<TaskbarActivityContext> {

    private final int[] mTempLoc = new int[2];
    private final int mFolderMargin;

    // Initialized in TaskbarController constructor.
    private TaskbarController.TaskbarContainerViewCallbacks mControllerCallbacks;

    // Initialized in init.
    private TaskbarView mTaskbarView;
    private ViewTreeObserverWrapper.OnComputeInsetsListener mTaskbarInsetsComputer;

    public TaskbarContainerView(@NonNull Context context) {
        this(context, null);
    }

    public TaskbarContainerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskbarContainerView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskbarContainerView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, 1 /* alphaChannelCount */);
        mFolderMargin = getResources().getDimensionPixelSize(R.dimen.taskbar_folder_margin);
    }

    protected void construct(TaskbarController.TaskbarContainerViewCallbacks callbacks) {
        mControllerCallbacks = callbacks;
    }

    protected void init(TaskbarView taskbarView) {
        mTaskbarView = taskbarView;
        mTaskbarInsetsComputer = createTaskbarInsetsComputer();
        recreateControllers();
    }

    @Override
    public void recreateControllers() {
        mControllers = new TouchController[0];
    }

    private ViewTreeObserverWrapper.OnComputeInsetsListener createTaskbarInsetsComputer() {
        return insetsInfo -> {
            if (mControllerCallbacks.isTaskbarTouchable()) {
                 // Accept touches anywhere in our bounds.
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_FRAME);
            } else {
                // Let touches pass through us.
                insetsInfo.touchableRegion.setEmpty();
                insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION);
            }

            // TaskbarContainerView provides insets to other apps based on contentInsets. These
            // insets should stay consistent even if we expand TaskbarContainerView's bounds, e.g.
            // to show a floating view like Folder. Thus, we set the contentInsets to be where
            // mTaskbarView is, since its position never changes and insets rather than overlays.
            int[] loc = mTempLoc;
            float scale = mTaskbarView.getScaleX();
            mTaskbarView.setScaleX(1);
            mTaskbarView.setScaleY(1);
            mTaskbarView.getLocationInWindow(loc);
            mTaskbarView.setScaleX(scale);
            mTaskbarView.setScaleY(scale);
            insetsInfo.contentInsets.left = loc[0];
            insetsInfo.contentInsets.top = loc[1];
            insetsInfo.contentInsets.right = getWidth() - (loc[0] + mTaskbarView.getWidth());
            insetsInfo.contentInsets.bottom = getHeight() - (loc[1] + mTaskbarView.getHeight());
        };
    }

    protected void cleanup() {
        ViewTreeObserverWrapper.removeOnComputeInsetsListener(mTaskbarInsetsComputer);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        ViewTreeObserverWrapper.addOnComputeInsetsListener(getViewTreeObserver(),
                mTaskbarInsetsComputer);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        cleanup();
    }

    @Override
    protected boolean canFindActiveController() {
        // Unlike super class, we want to be able to find controllers when touches occur in the
        // gesture area. For example, this allows Folder to close itself when touching the Taskbar.
        return true;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        mControllerCallbacks.onViewRemoved();
    }

    /**
     * @return Bounds (in our coordinates) where an opened Folder can display.
     */
    protected Rect getFolderBoundingBox() {
        Rect boundingBox = new Rect(0, 0, getWidth(), getHeight() - mTaskbarView.getHeight());
        boundingBox.inset(mFolderMargin, mFolderMargin);
        return boundingBox;
    }

    protected TaskbarActivityContext getTaskbarActivityContext() {
        return mActivity;
    }
}
