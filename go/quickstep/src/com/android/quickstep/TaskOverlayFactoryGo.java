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

package com.android.quickstep;

import static com.android.quickstep.views.OverviewActionsView.DISABLED_NO_THUMBNAIL;
import static com.android.quickstep.views.OverviewActionsView.DISABLED_ROTATED;

import android.annotation.SuppressLint;
import android.app.ActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistContent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.R;
import com.android.quickstep.views.OverviewActionsView;
import com.android.quickstep.views.TaskThumbnailView;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.ThumbnailData;

/**
 * Go-specific extension of the factory class that adds an overlay to TaskView
 */
public final class TaskOverlayFactoryGo extends TaskOverlayFactory {
    public static final String ACTION_LISTEN = "com.android.quickstep.ACTION_LISTEN";
    public static final String ACTION_TRANSLATE = "com.android.quickstep.ACTION_TRANSLATE";
    public static final String ACTION_SEARCH = "com.android.quickstep.ACTION_SEARCH";
    public static final String ELAPSED_NANOS = "niu_actions_elapsed_realtime_nanos";
    public static final String ACTIONS_URL = "niu_actions_app_url";
    private static final String ASSIST_KEY_CONTENT = "content";
    private static final String TAG = "TaskOverlayFactoryGo";

    // Empty constructor required for ResourceBasedOverride
    public TaskOverlayFactoryGo(Context context) {}

    /**
     * Create a new overlay instance for the given View
     */
    public TaskOverlayGo createOverlay(TaskThumbnailView thumbnailView) {
        return new TaskOverlayGo(thumbnailView);
    }

    /**
     * Overlay on each task handling Overview Action Buttons.
     * @param <T> The type of View in which the overlay will be placed
     */
    public static final class TaskOverlayGo<T extends OverviewActionsView> extends TaskOverlay {
        private String mNIUPackageName;
        private int mTaskId;
        private Bundle mAssistData;
        private final Handler mMainThreadHandler;

        private TaskOverlayGo(TaskThumbnailView taskThumbnailView) {
            super(taskThumbnailView);
            mMainThreadHandler = new Handler(Looper.getMainLooper());
        }

        /**
         * Called when the current task is interactive for the user
         */
        @Override
        public void initOverlay(Task task, ThumbnailData thumbnail, Matrix matrix,
                boolean rotated) {
            getActionsView().updateDisabledFlags(DISABLED_NO_THUMBNAIL, thumbnail == null);
            mNIUPackageName =
                    mApplicationContext.getResources().getString(R.string.niu_actions_package);

            if (thumbnail == null || TextUtils.isEmpty(mNIUPackageName)) {
                return;
            }

            getActionsView().updateDisabledFlags(DISABLED_ROTATED, rotated);
            boolean isAllowedByPolicy = mThumbnailView.isRealSnapshot();
            getActionsView().setCallbacks(new OverlayUICallbacksGoImpl(isAllowedByPolicy, task));

            mTaskId = task.key.id;
            AssistDataReceiverImpl receiver = new AssistDataReceiverImpl();
            receiver.setOverlay(this);

            try {
                ActivityTaskManager.getService().requestAssistDataForTask(receiver, mTaskId,
                        mApplicationContext.getPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to request AssistData");
            }
        }

        /**
         * Called when AssistDataReceiverImpl receives data from ActivityTaskManagerService's
         * AssistDataRequester
         */
        public void onAssistDataReceived(Bundle data) {
            mMainThreadHandler.post(() -> {
                if (data != null) {
                    mAssistData = data;
                }
            });
        }

        /**
         * Creates and sends an Intent corresponding to the button that was clicked
         */
        @VisibleForTesting
        public void sendNIUIntent(String actionType) {
            Intent intent = createNIUIntent(actionType);
            mImageApi.shareAsDataWithExplicitIntent(/* crop */ null, intent);
        }

        private Intent createNIUIntent(String actionType) {
            Intent intent = new Intent(actionType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .setPackage(mNIUPackageName)
                    .putExtra(ELAPSED_NANOS, SystemClock.elapsedRealtimeNanos());

            if (mAssistData != null) {
                final AssistContent content = mAssistData.getParcelable(ASSIST_KEY_CONTENT);
                Uri webUri = (content == null) ? null : content.getWebUri();
                if (webUri != null) {
                    intent.putExtra(ACTIONS_URL, webUri.toString());
                }
            }

            return intent;
        }

        protected class OverlayUICallbacksGoImpl extends OverlayUICallbacksImpl
                implements OverlayUICallbacksGo {
            public OverlayUICallbacksGoImpl(boolean isAllowedByPolicy, Task task) {
                super(isAllowedByPolicy, task);
            }

            @SuppressLint("NewApi")
            public void onListen() {
                if (mIsAllowedByPolicy) {
                    sendNIUIntent(ACTION_LISTEN);
                } else {
                    showBlockedByPolicyMessage();
                }
            }

            @SuppressLint("NewApi")
            public void onTranslate() {
                if (mIsAllowedByPolicy) {
                    sendNIUIntent(ACTION_TRANSLATE);
                } else {
                    showBlockedByPolicyMessage();
                }
            }

            @SuppressLint("NewApi")
            public void onSearch() {
                if (mIsAllowedByPolicy) {
                    sendNIUIntent(ACTION_SEARCH);
                } else {
                    showBlockedByPolicyMessage();
                }
            }
        }

        @VisibleForTesting
        public void setImageActionsAPI(ImageActionsApi imageActionsApi) {
            mImageApi = imageActionsApi;
        }
    }

    /**
     * Basic AssistDataReceiver. This is passed to ActivityTaskManagerService, which then requests
     * the data.
     */
    private static final class AssistDataReceiverImpl extends IAssistDataReceiver.Stub {
        private TaskOverlayGo mOverlay;

        public void setOverlay(TaskOverlayGo overlay) {
            mOverlay = overlay;
        }

        @Override
        public void onHandleAssistData(Bundle data) {
            mOverlay.onAssistDataReceived(data);
        }

        @Override
        public void onHandleAssistScreenshot(Bitmap screenshot) {}
    }

    /**
     * Callbacks the Ui can generate. This is the only way for a Ui to call methods on the
     * controller.
     */
    public interface OverlayUICallbacksGo extends OverlayUICallbacks {
        /** User has requested to listen to the current content read aloud */
        void onListen();

        /** User has requested a translation of the current content */
        void onTranslate();

        /** User has requested a visual search of the current content */
        void onSearch();
    }
}
