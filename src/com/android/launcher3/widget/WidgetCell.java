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

package com.android.launcher3.widget;

import static com.android.launcher3.Utilities.ATLEAST_S;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.CancellationSignal;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.BaseActivity;
import com.android.launcher3.CheckLongPressHelper;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.WidgetPreviewLoader;
import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.BitmapRenderer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.icons.RoundDrawableWrapper;
import com.android.launcher3.model.WidgetItem;

/**
 * Represents the individual cell of the widget inside the widget tray. The preview is drawn
 * horizontally centered, and scaled down if needed.
 *
 * This view does not support padding. Since the image is scaled down to fit the view, padding will
 * further decrease the scaling factor. Drag-n-drop uses the view bounds for showing a smooth
 * transition from the view to drag view, so when adding padding support, DnD would need to
 * consider the appropriate scaling factor.
 */
public class WidgetCell extends LinearLayout implements OnLayoutChangeListener {

    private static final String TAG = "WidgetCell";
    private static final boolean DEBUG = false;

    private static final int FADE_IN_DURATION_MS = 90;

    /** Widget cell width is calculated by multiplying this factor to grid cell width. */
    private static final float WIDTH_SCALE = 3f;

    /** Widget preview width is calculated by multiplying this factor to the widget cell width. */
    private static final float PREVIEW_SCALE = 0.8f;

    protected int mPreviewWidth;
    protected int mPreviewHeight;
    protected int mPresetPreviewSize;
    private int mCellSize;
    private float mPreviewScale = 1f;

    private FrameLayout mWidgetImageContainer;
    private WidgetImageView mWidgetImage;
    private ImageView mWidgetBadge;
    private TextView mWidgetName;
    private TextView mWidgetDims;
    private TextView mWidgetDescription;

    protected WidgetItem mItem;

    private WidgetPreviewLoader mWidgetPreviewLoader;

    protected CancellationSignal mActiveRequest;
    private boolean mAnimatePreview = true;

    private boolean mApplyBitmapDeferred = false;
    private Drawable mDeferredDrawable;

    protected final BaseActivity mActivity;
    protected final DeviceProfile mDeviceProfile;
    private final CheckLongPressHelper mLongPressHelper;
    private final float mEnforcedCornerRadius;

    private RemoteViews mPreview;
    private LauncherAppWidgetHostView mAppWidgetHostViewPreview;

    public WidgetCell(Context context) {
        this(context, null);
    }

    public WidgetCell(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WidgetCell(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mActivity = BaseActivity.fromContext(context);
        mDeviceProfile = mActivity.getDeviceProfile();
        mLongPressHelper = new CheckLongPressHelper(this);

        mLongPressHelper.setLongPressTimeoutFactor(1);
        setContainerWidth();
        setWillNotDraw(false);
        setClipToPadding(false);
        setAccessibilityDelegate(mActivity.getAccessibilityDelegate());
        mEnforcedCornerRadius = RoundedCornerEnforcement.computeEnforcedRadius(context);
    }

    private void setContainerWidth() {
        mCellSize = (int) (mDeviceProfile.allAppsIconSizePx * WIDTH_SCALE);
        mPresetPreviewSize = (int) (mCellSize * PREVIEW_SCALE);
        mPreviewWidth = mPreviewHeight = mPresetPreviewSize;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mWidgetImageContainer = findViewById(R.id.widget_preview_container);
        mWidgetImage = findViewById(R.id.widget_preview);
        mWidgetBadge = findViewById(R.id.widget_badge);
        mWidgetName = findViewById(R.id.widget_name);
        mWidgetDims = findViewById(R.id.widget_dims);
        mWidgetDescription = findViewById(R.id.widget_description);
    }

    public void setPreview(RemoteViews view) {
        mPreview = view;
    }

    public RemoteViews getPreview() {
        return mPreview;
    }

    /**
     * Called to clear the view and free attached resources. (e.g., {@link Bitmap}
     */
    public void clear() {
        if (DEBUG) {
            Log.d(TAG, "reset called on:" + mWidgetName.getText());
        }
        mWidgetImage.animate().cancel();
        mWidgetImage.setDrawable(null);
        mWidgetImage.setVisibility(View.VISIBLE);
        mWidgetBadge.setImageDrawable(null);
        mWidgetName.setText(null);
        mWidgetDims.setText(null);
        mWidgetDescription.setText(null);
        mWidgetDescription.setVisibility(GONE);
        mPreviewWidth = mPreviewHeight = mPresetPreviewSize;

        if (mActiveRequest != null) {
            mActiveRequest.cancel();
            mActiveRequest = null;
        }
        mPreview = null;
        if (mAppWidgetHostViewPreview != null) {
            mWidgetImageContainer.removeView(mAppWidgetHostViewPreview);
        }
        mAppWidgetHostViewPreview = null;
    }

    public void applyFromCellItem(WidgetItem item, WidgetPreviewLoader loader) {
        applyPreviewLayout(item);

        mItem = item;
        mWidgetName.setText(mItem.label);
        mWidgetDims.setText(getContext().getString(R.string.widget_dims_format,
                mItem.spanX, mItem.spanY));
        mWidgetDims.setContentDescription(getContext().getString(
                R.string.widget_accessible_dims_format, mItem.spanX, mItem.spanY));
        if (ATLEAST_S && mItem.widgetInfo != null) {
            CharSequence description = mItem.widgetInfo.loadDescription(getContext());
            if (description != null && description.length() > 0) {
                mWidgetDescription.setText(description);
                mWidgetDescription.setVisibility(VISIBLE);
            } else {
                mWidgetDescription.setVisibility(GONE);
            }
        }

        mWidgetPreviewLoader = loader;
        if (item.activityInfo != null) {
            setTag(new PendingAddShortcutInfo(item.activityInfo));
        } else {
            setTag(new PendingAddWidgetInfo(item.widgetInfo));
        }
    }

    private void applyPreviewLayout(WidgetItem item) {
        if (ATLEAST_S
                && mPreview == null
                && item.widgetInfo != null
                && item.widgetInfo.previewLayout != Resources.ID_NULL) {
            mAppWidgetHostViewPreview = new LauncherAppWidgetHostView(getContext());
            LauncherAppWidgetProviderInfo launcherAppWidgetProviderInfo =
                    LauncherAppWidgetProviderInfo.fromProviderInfo(getContext(),
                            item.widgetInfo.clone());
            // A hack to force the initial layout to be the preview layout since there is no API for
            // rendering a preview layout for work profile apps yet. For non-work profile layout, a
            // proper solution is to use RemoteViews(PackageName, LayoutId).
            launcherAppWidgetProviderInfo.initialLayout = item.widgetInfo.previewLayout;
            mAppWidgetHostViewPreview.setAppWidget(/* appWidgetId= */ -1,
                    launcherAppWidgetProviderInfo);
            mAppWidgetHostViewPreview.setPadding(/* left= */ 0, /* top= */0, /* right= */
                    0, /* bottom= */ 0);
            mAppWidgetHostViewPreview.updateAppWidget(/* remoteViews= */ null);
            // Gravity 77 = "fill"
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, /* gravity= */ 77);
            mAppWidgetHostViewPreview.setLayoutParams(params);
            mWidgetImageContainer.addView(mAppWidgetHostViewPreview, /* index= */ 0);
            mWidgetImage.setVisibility(View.GONE);
        }
    }

    public WidgetImageView getWidgetView() {
        return mWidgetImage;
    }

    @Nullable
    public LauncherAppWidgetHostView getAppWidgetHostViewPreview() {
        return mAppWidgetHostViewPreview;
    }

    /**
     * Sets if applying bitmap preview should be deferred. The UI will still load the bitmap, but
     * will not cause invalidate, so that when deferring is disabled later, all the bitmaps are
     * ready.
     * This prevents invalidates while the animation is running.
     */
    public void setApplyBitmapDeferred(boolean isDeferred) {
        if (mApplyBitmapDeferred != isDeferred) {
            mApplyBitmapDeferred = isDeferred;
            if (!mApplyBitmapDeferred && mDeferredDrawable != null) {
                applyPreview(mDeferredDrawable);
                mDeferredDrawable = null;
            }
        }
    }

    public void setAnimatePreview(boolean shouldAnimate) {
        mAnimatePreview = shouldAnimate;
    }

    public void applyPreview(Bitmap bitmap) {
        FastBitmapDrawable drawable = new FastBitmapDrawable(bitmap);
        applyPreview(new RoundDrawableWrapper(drawable, mEnforcedCornerRadius));
    }

    private void applyPreview(Drawable drawable) {
        if (mApplyBitmapDeferred) {
            mDeferredDrawable = drawable;
            return;
        }
        if (drawable != null) {
            setContainerSize(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            mWidgetImage.setDrawable(drawable);
            mWidgetImage.setVisibility(View.VISIBLE);
            if (mAppWidgetHostViewPreview != null) {
                removeView(mAppWidgetHostViewPreview);
                mAppWidgetHostViewPreview = null;
            }
        }
        Drawable badge = mWidgetPreviewLoader.getBadgeForUser(mItem.user,
                BaseIconFactory.getBadgeSizeForIconSize(mDeviceProfile.allAppsIconSizePx));
        if (badge == null) {
            mWidgetBadge.setVisibility(View.GONE);
        } else {
            mWidgetBadge.setVisibility(View.VISIBLE);
            mWidgetBadge.setImageDrawable(badge);
        }
        if (mAnimatePreview) {
            mWidgetImageContainer.setAlpha(0f);
            ViewPropertyAnimator anim = mWidgetImageContainer.animate();
            anim.alpha(1.0f).setDuration(FADE_IN_DURATION_MS);
        } else {
            mWidgetImageContainer.setAlpha(1f);
        }
    }

    private void setContainerSize(int width, int height) {
        LayoutParams layoutParams = (LayoutParams) mWidgetImageContainer.getLayoutParams();
        layoutParams.width = (int) (width * mPreviewScale);
        layoutParams.height = (int) (height * mPreviewScale);
        mWidgetImageContainer.setLayoutParams(layoutParams);
    }

    public void ensurePreview() {
        if (mPreview != null && mActiveRequest == null) {
            Bitmap preview = generateFromRemoteViews(
                    mActivity, mPreview, mItem.widgetInfo, mPresetPreviewSize, new int[1]);
            if (preview != null) {
                applyPreview(new FastBitmapDrawable(preview));
                return;
            }
        }

        if (mAppWidgetHostViewPreview != null) {
            DeviceProfile dp = mActivity.getDeviceProfile();
            int viewWidth = dp.cellWidthPx * mItem.spanX;
            int viewHeight = dp.cellHeightPx * mItem.spanY;

            setContainerSize(viewWidth, viewHeight);
            applyPreview((Drawable) null);
            return;
        }
        if (mActiveRequest != null) {
            return;
        }
        mActiveRequest = mWidgetPreviewLoader.getPreview(mItem, mPreviewWidth, mPreviewHeight,
                this);
    }

    /** Sets the widget preview image size in number of cells. */
    public void setPreviewSize(int spanX, int spanY) {
        setPreviewSize(spanX, spanY, 1f);
    }

    /** Sets the widget preview image size, in number of cells, and preview scale. */
    public void setPreviewSize(int spanX, int spanY, float previewScale) {
        int padding = 2 * getResources()
                .getDimensionPixelSize(R.dimen.widget_preview_shortcut_padding);
        mPreviewWidth = mDeviceProfile.cellWidthPx * spanX + padding;
        mPreviewHeight = mDeviceProfile.cellHeightPx * spanY + padding;
        mPreviewScale = previewScale;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        removeOnLayoutChangeListener(this);
        ensurePreview();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);
        mLongPressHelper.onTouchEvent(ev);
        return true;
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    /**
     * Helper method to get the string info of the tag.
     */
    private String getTagToString() {
        if (getTag() instanceof PendingAddWidgetInfo ||
                getTag() instanceof PendingAddShortcutInfo) {
            return getTag().toString();
        }
        return "";
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return WidgetCell.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    /**
     * Generates a bitmap by inflating {@param views}.
     * @see com.android.launcher3.WidgetPreviewLoader#generateWidgetPreview
     *
     * TODO: Consider moving this to the background thread.
     */
    public static Bitmap generateFromRemoteViews(BaseActivity activity, RemoteViews views,
            LauncherAppWidgetProviderInfo info, int previewSize, int[] preScaledWidthOut) {
        try {
            return generateFromView(activity, views.apply(activity, new FrameLayout(activity)),
                    info, previewSize, preScaledWidthOut);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap generateFromView(BaseActivity activity, View v,
            LauncherAppWidgetProviderInfo info, int previewSize, int[] preScaledWidthOut) {

        DeviceProfile dp = activity.getDeviceProfile();
        int viewWidth = dp.cellWidthPx * info.spanX;
        int viewHeight = dp.cellHeightPx * info.spanY;

        v.measure(MeasureSpec.makeMeasureSpec(viewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(viewHeight, MeasureSpec.EXACTLY));

        viewWidth = v.getMeasuredWidth();
        viewHeight = v.getMeasuredHeight();
        v.layout(0, 0, viewWidth, viewHeight);

        preScaledWidthOut[0] = viewWidth;
        final int bitmapWidth, bitmapHeight;
        final float scale;
        if (viewWidth > previewSize) {
            scale = ((float) previewSize) / viewWidth;
            bitmapWidth = previewSize;
            bitmapHeight = (int) (viewHeight * scale);
        } else {
            scale = 1;
            bitmapWidth = viewWidth;
            bitmapHeight = viewHeight;
        }

        return BitmapRenderer.createSoftwareBitmap(bitmapWidth, bitmapHeight, c -> {
            c.scale(scale, scale);
            v.draw(c);
        });
    }
}
