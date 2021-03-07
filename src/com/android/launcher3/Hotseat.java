/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.launcher3.config.FeatureFlags;

import java.util.function.Consumer;

/**
 * View class that represents the bottom row of the home screen.
 */
public class Hotseat extends CellLayout implements Insettable {

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace mWorkspace;
    private boolean mSendTouchToWorkspace;
    @Nullable
    private Consumer<Boolean> mOnVisibilityAggregatedCallback;

    public Hotseat(Context context) {
        this(context, null);
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Returns orientation specific cell X given invariant order in the hotseat
     */
    public int getCellXFromOrder(int rank) {
        return mHasVerticalHotseat ? 0 : rank;
    }

    /**
     * Returns orientation specific cell Y given invariant order in the hotseat
     */
    public int getCellYFromOrder(int rank) {
        return mHasVerticalHotseat ? (getCountY() - (rank + 1)) : 0;
    }

    public void resetLayout(boolean hasVerticalHotseat) {
        removeAllViewsInLayout();
        mHasVerticalHotseat = hasVerticalHotseat;
        InvariantDeviceProfile idp = mActivity.getDeviceProfile().inv;
        if (hasVerticalHotseat) {
            setGridSize(1, idp.numHotseatIcons);
        } else {
            setGridSize(idp.numHotseatIcons, FeatureFlags.ENABLE_DEVICE_SEARCH.get() ? 2 : 1);
        }
        showInlineQsb();
    }

    @Override
    public void setInsets(Rect insets) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        DeviceProfile grid = mActivity.getDeviceProfile();

        if (grid.isVerticalBarLayout()) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (grid.isSeascape()) {
                lp.gravity = Gravity.LEFT;
                lp.width = grid.hotseatBarSizePx + insets.left;
            } else {
                lp.gravity = Gravity.RIGHT;
                lp.width = grid.hotseatBarSizePx + insets.right;
            }
        } else {
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = grid.isTaskbarPresent
                    ? grid.taskbarSize
                    : grid.hotseatBarSizePx + insets.bottom;
        }
        if (!grid.isTaskbarPresent) {
            // When taskbar is present, we set the padding separately to ensure a seamless visual
            // handoff between taskbar and hotseat during drag and drop.
            Rect padding = grid.getHotseatLayoutPadding();
            int paddingBottom = padding.bottom;
            if (FeatureFlags.ENABLE_DEVICE_SEARCH.get() && !grid.isVerticalBarLayout()) {
                paddingBottom -= grid.hotseatBarBottomPaddingPx;
            }
            setPadding(padding.left, padding.top, padding.right, paddingBottom);
        }

        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace w) {
        mWorkspace = w;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // We allow horizontal workspace scrolling from within the Hotseat. We do this by delegating
        // touch intercept the Workspace, and if it intercepts, delegating touch to the Workspace
        // for the remainder of the this input stream.
        int yThreshold = getMeasuredHeight() - getPaddingBottom();
        if (mWorkspace != null && ev.getY() <= yThreshold) {
            mSendTouchToWorkspace = mWorkspace.onInterceptTouchEvent(ev);
            return mSendTouchToWorkspace;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // See comment in #onInterceptTouchEvent
        if (mSendTouchToWorkspace) {
            final int action = event.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSendTouchToWorkspace = false;
            }
            return mWorkspace.onTouchEvent(event);
        }
        return event.getY() > getCellHeight();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);

        if (mOnVisibilityAggregatedCallback != null) {
            mOnVisibilityAggregatedCallback.accept(isVisible);
        }
    }

    /** Sets a callback to be called onVisibilityAggregated */
    public void setOnVisibilityAggregatedCallback(@Nullable Consumer<Boolean> callback) {
        mOnVisibilityAggregatedCallback = callback;
    }

    protected void showInlineQsb() {
        //Does nothing
    }

    /**
     * Returns the first View for which the given itemOperator returns true, or null.
     */
    public View getFirstItemMatch(Workspace.ItemOperator itemOperator) {
        return mWorkspace.getFirstMatch(new CellLayout[] { this }, itemOperator);
    }
}
