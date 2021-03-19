/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TableLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener;

import com.android.launcher3.BaseRecyclerView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.model.WidgetsListBaseEntry;
import com.android.launcher3.widget.model.WidgetsListContentEntry;
import com.android.launcher3.widget.model.WidgetsListHeaderEntry;
import com.android.launcher3.widget.model.WidgetsListSearchHeaderEntry;

/**
 * The widgets recycler view.
 */
public class WidgetsRecyclerView extends BaseRecyclerView implements OnItemTouchListener {

    private WidgetsListAdapter mAdapter;

    private final int mScrollbarTop;

    private final Point mFastScrollerOffset = new Point();
    private final int mEstimatedWidgetListHeaderHeight;
    private boolean mTouchDownOnScroller;
    private HeaderViewDimensionsProvider mHeaderViewDimensionsProvider;
    private int mLastVisibleWidgetContentTableHeight = 0;

    public WidgetsRecyclerView(Context context) {
        this(context, null);
    }

    public WidgetsRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WidgetsRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        // API 21 and below only support 3 parameter ctor.
        super(context, attrs, defStyleAttr);
        mScrollbarTop = getResources().getDimensionPixelSize(R.dimen.dynamic_grid_edge_margin);
        addOnItemTouchListener(this);

        ActivityContext activity = ActivityContext.lookupContext(getContext());
        DeviceProfile grid = activity.getDeviceProfile();
        mEstimatedWidgetListHeaderHeight = grid.iconSizePx
                + 2 * context.getResources().getDimensionPixelSize(
                        R.dimen.widget_list_header_view_vertical_padding);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // create a layout manager with Launcher's context so that scroll position
        // can be preserved during screen rotation.
        setLayoutManager(new LinearLayoutManager(getContext()));
    }

    @Override
    public void setAdapter(Adapter adapter) {
        super.setAdapter(adapter);
        mAdapter = (WidgetsListAdapter) adapter;
    }

    /**
     * Maps the touch (from 0..1) to the adapter position that should be visible.
     */
    @Override
    public String scrollToPositionAtProgress(float touchFraction) {
        // Skip early if widgets are not bound.
        if (isModelNotReady()) {
            return "";
        }

        // Stop the scroller if it is scrolling
        stopScroll();

        int rowCount = mAdapter.getItemCount();
        float pos = rowCount * touchFraction;
        int availableScrollHeight = getAvailableScrollHeight();
        LinearLayoutManager layoutManager = ((LinearLayoutManager) getLayoutManager());
        layoutManager.scrollToPositionWithOffset(0, (int) -(availableScrollHeight * touchFraction));

        int posInt = (int) ((touchFraction == 1) ? pos - 1 : pos);
        return mAdapter.getSectionName(posInt);
    }

    /**
     * Updates the bounds for the scrollbar.
     */
    @Override
    public void onUpdateScrollbar(int dy) {
        // Skip early if widgets are not bound.
        if (isModelNotReady()) {
            return;
        }

        // Skip early if, there no child laid out in the container.
        int scrollY = getCurrentScrollY();
        if (scrollY < 0) {
            mScrollbar.setThumbOffsetY(-1);
            return;
        }

        synchronizeScrollBarThumbOffsetToViewScroll(scrollY, getAvailableScrollHeight());
    }

    @Override
    public int getCurrentScrollY() {
        // Skip early if widgets are not bound.
        if (isModelNotReady() || getChildCount() == 0) {
            return -1;
        }

        View child = getChildAt(0);
        int rowIndex = getChildPosition(child);
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof TableLayout) {
                // This assumes there is ever only one content shown in this recycler view.
                mLastVisibleWidgetContentTableHeight = view.getMeasuredHeight();
            }
        }

        int scrollPosition = getItemsHeight(rowIndex);
        int offset = getLayoutManager().getDecoratedTop(child);

        return getPaddingTop() + scrollPosition - offset;
    }

    /**
     * Returns the available scroll height, in pixel.
     *
     * <p>If the recycler view can't be scrolled, returns 0.
     */
    @Override
    protected int getAvailableScrollHeight() {
        // AvailableScrollHeight = Total height of the all items - first page height
        int firstPageHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        int totalHeightOfAllItems = getItemsHeight(/* untilIndex= */ mAdapter.getItemCount());
        int availableScrollHeight = totalHeightOfAllItems - firstPageHeight;
        return Math.max(0, availableScrollHeight);
    }

    private boolean isModelNotReady() {
        return mAdapter.getItemCount() == 0;
    }

    @Override
    public int getScrollBarTop() {
        return mHeaderViewDimensionsProvider == null
                ? mScrollbarTop
                : mHeaderViewDimensionsProvider.getHeaderViewHeight() + mScrollbarTop;
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            mTouchDownOnScroller =
                    mScrollbar.isHitInParent(e.getX(), e.getY(), mFastScrollerOffset);
        }
        if (mTouchDownOnScroller) {
            final boolean result = mScrollbar.handleTouchEvent(e, mFastScrollerOffset);
            return result;
        }
        return false;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        if (mTouchDownOnScroller) {
            mScrollbar.handleTouchEvent(e, mFastScrollerOffset);
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    }

    public void setHeaderViewDimensionsProvider(
            HeaderViewDimensionsProvider headerViewDimensionsProvider) {
        mHeaderViewDimensionsProvider = headerViewDimensionsProvider;
    }

    /**
     * Returns the sum of the height, in pixels, of this list adapter's items from index 0 until
     * {@code untilIndex}.
     *
     * <p>If the untilIndex is larger than the total number of items in this adapter, returns the
     * sum of all items' height.
     */
    private int getItemsHeight(int untilIndex) {
        if (untilIndex > mAdapter.getItems().size()) {
            untilIndex = mAdapter.getItems().size();
        }
        int totalItemsHeight = 0;
        for (int i = 0; i < untilIndex; i++) {
            WidgetsListBaseEntry entry = mAdapter.getItems().get(i);
            if (entry instanceof WidgetsListHeaderEntry
                    || entry instanceof WidgetsListSearchHeaderEntry) {
                totalItemsHeight += mEstimatedWidgetListHeaderHeight;
            } else if (entry instanceof WidgetsListContentEntry) {
                totalItemsHeight += mLastVisibleWidgetContentTableHeight;
            } else {
                throw new UnsupportedOperationException("Can't estimate height for " + entry);
            }
        }
        return totalItemsHeight;
    }

    /**
     * Provides dimensions of the header view that is shown at the top of a
     * {@link WidgetsRecyclerView}.
     */
    public interface HeaderViewDimensionsProvider {
        /**
         * Returns the height, in pixels, of the header view that is shown at the top of a
         * {@link WidgetsRecyclerView}.
         */
        int getHeaderViewHeight();
    }
}
