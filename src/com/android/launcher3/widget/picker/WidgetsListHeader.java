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
package com.android.launcher3.widget.picker;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.R;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.icons.IconCache.ItemInfoUpdateReceiver;
import com.android.launcher3.icons.PlaceHolderIconDrawable;
import com.android.launcher3.icons.cache.HandlerRunnable;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.PackageItemInfo;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.model.WidgetsListHeaderEntry;
import com.android.launcher3.widget.model.WidgetsListSearchHeaderEntry;

import java.util.stream.Collectors;

/**
 * A UI represents a header of an app shown in the full widgets tray.
 *
 * It is a {@link LinearLayout} which contains an app icon, an app name, a subtitle and a checkbox
 * which indicates if the widgets content view underneath this header should be shown.
 */
public final class WidgetsListHeader extends LinearLayout implements ItemInfoUpdateReceiver {

    private boolean mEnableIconUpdateAnimation = false;

    @Nullable private HandlerRunnable mIconLoadRequest;
    @Nullable private Drawable mIconDrawable;
    private final int mIconSize;
    private final int mBottomMarginSize;

    private ImageView mAppIcon;
    private TextView mTitle;
    private TextView mSubtitle;

    private CheckBox mExpandToggle;
    private boolean mIsExpanded = false;

    public WidgetsListHeader(Context context) {
        this(context, /* attrs= */ null);
    }

    public WidgetsListHeader(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, /* defStyle= */ 0);
    }

    public WidgetsListHeader(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        ActivityContext activity = ActivityContext.lookupContext(context);
        DeviceProfile grid = activity.getDeviceProfile();
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.WidgetsListRowHeader, defStyleAttr, /* defStyleRes= */ 0);
        mIconSize = a.getDimensionPixelSize(R.styleable.WidgetsListRowHeader_appIconSize,
                grid.iconSizePx);
        mBottomMarginSize =
                getResources().getDimensionPixelSize(R.dimen.widget_list_entry_bottom_margin);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAppIcon = findViewById(R.id.app_icon);
        mTitle = findViewById(R.id.app_title);
        mSubtitle = findViewById(R.id.app_subtitle);
        mExpandToggle = findViewById(R.id.toggle);
    }

    /**
     * Sets a {@link OnExpansionChangeListener} to get a callback when this app widgets section
     * expands / collapses.
     */
    @UiThread
    public void setOnExpandChangeListener(
            @Nullable OnExpansionChangeListener onExpandChangeListener) {
        // Use the entire touch area of this view to expand / collapse an app widgets section.
        setOnClickListener(view -> {
            setExpanded(!mIsExpanded);
            onExpandChangeListener.onExpansionChange(mIsExpanded);
        });
    }

    /** Sets the expand toggle to expand / collapse. */
    @UiThread
    public void setExpanded(boolean isExpanded) {
        this.mIsExpanded = isExpanded;
        mExpandToggle.setChecked(isExpanded);
        if (getLayoutParams() instanceof RecyclerView.LayoutParams) {
            int bottomMargin = isExpanded ? 0 : mBottomMarginSize;
            RecyclerView.LayoutParams layoutParams =
                    ((RecyclerView.LayoutParams) getLayoutParams());
            layoutParams.bottomMargin = bottomMargin;
            setLayoutParams(layoutParams);
        }
    }

    /** Apply app icon, labels and tag using a generic {@link WidgetsListHeaderEntry}. */
    @UiThread
    public void applyFromItemInfoWithIcon(WidgetsListHeaderEntry entry) {
        applyIconAndLabel(entry);
    }

    @UiThread
    private void applyIconAndLabel(WidgetsListHeaderEntry entry) {
        PackageItemInfo info = entry.mPkgItem;
        setIcon(info);
        setTitles(entry);
        setExpanded(entry.isWidgetListShown());

        super.setTag(info);

        verifyHighRes();
    }

    private void setIcon(PackageItemInfo info) {
        FastBitmapDrawable icon = info.newIcon(getContext());
        applyDrawables(icon);
        mIconDrawable = icon;
        if (mIconDrawable != null) {
            mIconDrawable.setVisible(
                    /* visible= */ getWindowVisibility() == VISIBLE && isShown(),
                    /* restart= */ false);
        }
    }

    private void applyDrawables(Drawable icon) {
        icon.setBounds(0, 0, mIconSize, mIconSize);

        mAppIcon.setImageDrawable(icon);

        // If the current icon is a placeholder color, animate its update.
        if (mIconDrawable != null
                && mIconDrawable instanceof PlaceHolderIconDrawable
                && mEnableIconUpdateAnimation) {
            ((PlaceHolderIconDrawable) mIconDrawable).animateIconUpdate(icon);
        }
    }

    private void setTitles(WidgetsListHeaderEntry entry) {
        mTitle.setText(entry.mPkgItem.title);

        Resources resources = getContext().getResources();
        if (entry.widgetsCount == 0 && entry.shortcutsCount == 0) {
            mSubtitle.setVisibility(GONE);
            return;
        }

        String subtitle;
        if (entry.widgetsCount > 0 && entry.shortcutsCount > 0) {
            String widgetsCount = resources.getQuantityString(R.plurals.widgets_count,
                    entry.widgetsCount, entry.widgetsCount);
            String shortcutsCount = resources.getQuantityString(R.plurals.shortcuts_count,
                    entry.shortcutsCount, entry.shortcutsCount);
            subtitle = resources.getString(R.string.widgets_and_shortcuts_count, widgetsCount,
                    shortcutsCount);
        } else if (entry.widgetsCount > 0) {
            subtitle = resources.getQuantityString(R.plurals.widgets_count,
                    entry.widgetsCount, entry.widgetsCount);
        } else {
            subtitle = resources.getQuantityString(R.plurals.shortcuts_count,
                    entry.shortcutsCount, entry.shortcutsCount);
        }
        mSubtitle.setText(subtitle);
        mSubtitle.setVisibility(VISIBLE);
    }

    /** Apply app icon, labels and tag using a generic {@link WidgetsListSearchHeaderEntry}. */
    @UiThread
    public void applyFromItemInfoWithIcon(WidgetsListSearchHeaderEntry entry) {
        applyIconAndLabel(entry);
    }

    @UiThread
    private void applyIconAndLabel(WidgetsListSearchHeaderEntry entry) {
        PackageItemInfo info = entry.mPkgItem;
        setIcon(info);
        setTitles(entry);
        setExpanded(entry.isWidgetListShown());

        super.setTag(info);

        verifyHighRes();
    }

    private void setTitles(WidgetsListSearchHeaderEntry entry) {
        mTitle.setText(entry.mPkgItem.title);

        mSubtitle.setText(entry.mWidgets.stream()
                .map(item -> item.label).sorted().collect(Collectors.joining(", ")));
        mSubtitle.setVisibility(VISIBLE);
    }

    @Override
    public void reapplyItemInfo(ItemInfoWithIcon info) {
        if (getTag() == info) {
            mIconLoadRequest = null;
            mEnableIconUpdateAnimation = true;

            // Optimization: Starting in N, pre-uploads the bitmap to RenderThread.
            info.bitmap.icon.prepareToDraw();

            setIcon((PackageItemInfo) info);

            mEnableIconUpdateAnimation = false;
        }
    }

    /** Verifies that the current icon is high-res otherwise posts a request to load the icon. */
    public void verifyHighRes() {
        if (mIconLoadRequest != null) {
            mIconLoadRequest.cancel();
            mIconLoadRequest = null;
        }
        if (getTag() instanceof ItemInfoWithIcon) {
            ItemInfoWithIcon info = (ItemInfoWithIcon) getTag();
            if (info.usingLowResIcon()) {
                mIconLoadRequest = LauncherAppState.getInstance(getContext()).getIconCache()
                        .updateIconInBackground(this, info);
            }
        }
    }

    /** A listener for the widget section expansion / collapse events. */
    public interface OnExpansionChangeListener {
        /** Notifies that the widget section is expanded or collapsed. */
        void onExpansionChange(boolean isExpanded);
    }
}
