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
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;

import com.android.launcher3.R;
import com.android.launcher3.WidgetPreviewLoader;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.recyclerview.ViewHolderBinder;
import com.android.launcher3.widget.WidgetCell;
import com.android.launcher3.widget.model.WidgetsListContentEntry;
import com.android.launcher3.widget.util.WidgetsTableUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds data from {@link WidgetsListContentEntry} to UI elements in {@link WidgetsRowViewHolder}.
 */
public final class WidgetsListTableViewHolderBinder
        implements ViewHolderBinder<WidgetsListContentEntry, WidgetsRowViewHolder> {
    private static final boolean DEBUG = false;
    private static final String TAG = "WidgetsListRowViewHolderBinder";

    private int mMaxSpansPerRow = 4;
    private final LayoutInflater mLayoutInflater;
    private final int mIndent;
    private final OnClickListener mIconClickListener;
    private final OnLongClickListener mIconLongClickListener;
    private final WidgetPreviewLoader mWidgetPreviewLoader;
    private final WidgetsListAdapter mWidgetsListAdapter;
    private boolean mApplyBitmapDeferred = false;

    public WidgetsListTableViewHolderBinder(
            Context context,
            LayoutInflater layoutInflater,
            OnClickListener iconClickListener,
            OnLongClickListener iconLongClickListener,
            WidgetPreviewLoader widgetPreviewLoader,
            WidgetsListAdapter listAdapter) {
        mLayoutInflater = layoutInflater;
        mIndent = context.getResources().getDimensionPixelSize(R.dimen.widget_section_indent);
        mIconClickListener = iconClickListener;
        mIconLongClickListener = iconLongClickListener;
        mWidgetPreviewLoader = widgetPreviewLoader;
        mWidgetsListAdapter = listAdapter;
    }

    /**
     * Defers applying bitmap on all the {@link WidgetCell} at
     * {@link #bindViewHolder(WidgetsRowViewHolder, WidgetsListContentEntry)} if
     * {@code applyBitmapDeferred} is {@code true}.
     */
    public void setApplyBitmapDeferred(boolean applyBitmapDeferred) {
        mApplyBitmapDeferred = applyBitmapDeferred;
    }

    public void setMaxSpansPerRow(int maxSpansPerRow) {
        mMaxSpansPerRow = maxSpansPerRow;
    }

    @Override
    public WidgetsRowViewHolder newViewHolder(ViewGroup parent) {
        if (DEBUG) {
            Log.v(TAG, "\nonCreateViewHolder");
        }

        ViewGroup container = (ViewGroup) mLayoutInflater.inflate(
                R.layout.widgets_table_container, parent, false);

        // if the end padding is 0, then container view (horizontal scroll view) doesn't respect
        // the end of the linear layout width + the start padding and doesn't allow scrolling.
        container.findViewById(R.id.widgets_table).setPaddingRelative(mIndent, 0, 1, 0);

        return new WidgetsRowViewHolder(container);
    }

    @Override
    public void bindViewHolder(WidgetsRowViewHolder holder, WidgetsListContentEntry entry,
            int position) {
        TableLayout table = holder.mTableContainer;
        if (DEBUG) {
            Log.d(TAG, String.format("onBindViewHolder [widget#=%d, table.getChildCount=%d]",
                    entry.mWidgets.size(), table.getChildCount()));
        }

        if (position == mWidgetsListAdapter.getItemCount() - 1) {
            table.setBackgroundResource(R.drawable.widgets_list_bottom_ripple);
        } else {
            // WidgetsListContentEntry is never shown in position 0. There must be a header above
            // it.
            table.setBackgroundResource(R.drawable.widgets_list_middle_ripple);
        }

        List<ArrayList<WidgetItem>> widgetItemsTable =
                WidgetsTableUtils.groupWidgetItemsIntoTable(entry.mWidgets, mMaxSpansPerRow);
        recycleTableBeforeBinding(table, widgetItemsTable);
        // Bind the widget items.
        for (int i = 0; i < widgetItemsTable.size(); i++) {
            List<WidgetItem> widgetItemsPerRow = widgetItemsTable.get(i);
            for (int j = 0; j < widgetItemsPerRow.size(); j++) {
                TableRow row = (TableRow) table.getChildAt(i);
                row.setVisibility(View.VISIBLE);
                WidgetCell widget = (WidgetCell) row.getChildAt(j);
                widget.clear();
                WidgetItem widgetItem = widgetItemsPerRow.get(j);
                widget.setPreviewSize(widgetItem.spanX, widgetItem.spanY);
                widget.applyFromCellItem(widgetItem, mWidgetPreviewLoader);
                widget.setApplyBitmapDeferred(mApplyBitmapDeferred);
                widget.ensurePreview();
                widget.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Adds and hides table rows and columns from {@code table} to ensure there is sufficient room
     * to display {@code widgetItemsTable}.
     *
     * <p>Instead of recreating all UI elements in {@code table}, this function recycles all
     * existing UI elements. Instead of deleting excessive elements, it hides them.
     */
    private void recycleTableBeforeBinding(TableLayout table,
            List<ArrayList<WidgetItem>> widgetItemsTable) {
        // Hide extra table rows.
        for (int i = widgetItemsTable.size(); i < table.getChildCount(); i++) {
            table.getChildAt(i).setVisibility(View.GONE);
        }

        for (int i = 0; i < widgetItemsTable.size(); i++) {
            List<WidgetItem> widgetItems = widgetItemsTable.get(i);
            TableRow tableRow;
            if (i < table.getChildCount()) {
                tableRow = (TableRow) table.getChildAt(i);
            } else {
                tableRow = new TableRow(table.getContext());
                tableRow.setGravity(Gravity.TOP);
                table.addView(tableRow);
            }
            if (tableRow.getChildCount() > widgetItems.size()) {
                for (int j = widgetItems.size(); j < tableRow.getChildCount(); j++) {
                    tableRow.getChildAt(j).setVisibility(View.GONE);
                }
            } else {
                for (int j = tableRow.getChildCount(); j < widgetItems.size(); j++) {
                    WidgetCell widget = (WidgetCell) mLayoutInflater.inflate(
                            R.layout.widget_cell, tableRow, false);
                    // set up touch.
                    View preview = widget.findViewById(R.id.widget_preview_container);
                    preview.setOnClickListener(mIconClickListener);
                    preview.setOnLongClickListener(mIconLongClickListener);
                    tableRow.addView(widget);
                }
            }
        }
    }

    @Override
    public void unbindViewHolder(WidgetsRowViewHolder holder) {
        int numOfRows = holder.mTableContainer.getChildCount();
        for (int i = 0; i < numOfRows; i++) {
            TableRow tableRow = (TableRow) holder.mTableContainer.getChildAt(i);
            int numOfCols = tableRow.getChildCount();
            for (int j = 0; j < numOfCols; j++) {
                WidgetCell widget = (WidgetCell) tableRow.getChildAt(j);
                widget.clear();
            }
        }
    }
}
