/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.launcher3.config.FeatureFlags.ENABLE_OVERVIEW_ACTIONS;
import static com.android.quickstep.SysUINavigationMode.removeShelfFromOverview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.util.MultiValueAlpha.AlphaProperty;
import com.android.quickstep.TaskOverlayFactory.OverlayUICallbacks;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * View for showing action buttons in Overview
 */
public class OverviewActionsView<T extends OverlayUICallbacks> extends FrameLayout
        implements OnClickListener {

    public static final long VISIBILITY_TRANSITION_DURATION_MS = 80;

    @IntDef(flag = true, value = {
            HIDDEN_UNSUPPORTED_NAVIGATION,
            HIDDEN_DISABLED_FEATURE,
            HIDDEN_NON_ZERO_ROTATION,
            HIDDEN_NO_TASKS,
            HIDDEN_GESTURE_RUNNING,
            HIDDEN_NO_RECENTS,
            HIDDEN_FULLESCREEN_PROGRESS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActionsHiddenFlags { }

    public static final int HIDDEN_UNSUPPORTED_NAVIGATION = 1 << 0;
    public static final int HIDDEN_DISABLED_FEATURE = 1 << 1;
    public static final int HIDDEN_NON_ZERO_ROTATION = 1 << 2;
    public static final int HIDDEN_NO_TASKS = 1 << 3;
    public static final int HIDDEN_GESTURE_RUNNING = 1 << 4;
    public static final int HIDDEN_NO_RECENTS = 1 << 5;
    public static final int HIDDEN_FULLESCREEN_PROGRESS = 1 << 6;

    private static final int INDEX_CONTENT_ALPHA = 0;
    private static final int INDEX_VISIBILITY_ALPHA = 1;
    private static final int INDEX_HIDDEN_FLAGS_ALPHA = 2;

    private final MultiValueAlpha mMultiValueAlpha;

    @ActionsHiddenFlags
    private int mHiddenFlags;

    protected T mCallbacks;

    public OverviewActionsView(Context context) {
        this(context, null);
    }

    public OverviewActionsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OverviewActionsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr, 0);
        mMultiValueAlpha = new MultiValueAlpha(this, 3);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        findViewById(R.id.action_share).setOnClickListener(this);
        findViewById(R.id.action_screenshot).setOnClickListener(this);
    }

    /**
     * Set listener for callbacks on action button taps.
     *
     * @param callbacks for callbacks, or {@code null} to clear the listener.
     */
    public void setCallbacks(T callbacks) {
        mCallbacks = callbacks;
    }

    @Override
    public void onClick(View view) {
        if (mCallbacks == null) {
            return;
        }
        int id = view.getId();
        if (id == R.id.action_share) {
            mCallbacks.onShare();
        } else if (id == R.id.action_screenshot) {
            mCallbacks.onScreenshot();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateHiddenFlags(HIDDEN_DISABLED_FEATURE, !ENABLE_OVERVIEW_ACTIONS.get());
        updateHiddenFlags(HIDDEN_UNSUPPORTED_NAVIGATION, !removeShelfFromOverview(getContext()));
    }

    public void updateHiddenFlags(@ActionsHiddenFlags int visibilityFlags, boolean enable) {
        if (enable) {
            mHiddenFlags |= visibilityFlags;
        } else {
            mHiddenFlags &= ~visibilityFlags;
        }
        boolean isHidden = mHiddenFlags != 0;
        mMultiValueAlpha.getProperty(INDEX_HIDDEN_FLAGS_ALPHA).setValue(isHidden ? 0 : 1);
        setVisibility(isHidden ? INVISIBLE : VISIBLE);
    }

    public AlphaProperty getContentAlpha() {
        return mMultiValueAlpha.getProperty(INDEX_CONTENT_ALPHA);
    }

    public AlphaProperty getVisibilityAlpha() {
        return mMultiValueAlpha.getProperty(INDEX_VISIBILITY_ALPHA);
    }
}
