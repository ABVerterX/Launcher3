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

package com.android.quickstep.util;

import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistContent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.launcher3.util.Executors;

import java.util.concurrent.Executor;

/**
 * Can be used to request the AssistContent from a provided task id, useful for getting the web uri
 * if provided from the task.
 */
public class AssistContentRequester {
    private static final String TAG = "AssistContentRequester";
    private static final String ASSIST_KEY_CONTENT = "content";

    /** For receiving content, called on the main thread. */
    public interface Callback {
        /**
         * Called when the {@link android.app.assist.AssistContent} of the requested task is
         * available.
         **/
        void onAssistContentAvailable(AssistContent assistContent);
    }

    private final IActivityTaskManager mActivityTaskManager;
    private final String mPackageName;
    private final Executor mCallbackExecutor;

    public AssistContentRequester(Context context) {
        mActivityTaskManager = ActivityTaskManager.getService();
        mPackageName = context.getApplicationContext().getPackageName();
        mCallbackExecutor = Executors.MAIN_EXECUTOR;
    }

    /**
     * Request the {@link AssistContent} from the task with the provided id.
     *
     * @param taskId to query for the content.
     * @param callback to call when the content is available, called on the main thread.
     */
    public void requestAssistContent(int taskId, Callback callback) {
        try {
            mActivityTaskManager.requestAssistDataForTask(
                    new AssistDataReceiver(callback, mCallbackExecutor), taskId, mPackageName);
        } catch (RemoteException e) {
            Log.e(TAG, "Requesting assist content failed for task: " + taskId, e);
        }
    }

    private static final class AssistDataReceiver extends IAssistDataReceiver.Stub {

        private final Executor mExecutor;
        private final Callback mCallback;

        AssistDataReceiver(Callback callback, Executor callbackExecutor) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onHandleAssistData(Bundle data) {
            if (data == null) {
                return;
            }

            final AssistContent content = data.getParcelable(ASSIST_KEY_CONTENT);
            if (content == null) {
                Log.e(TAG, "Received AssistData, but no AssistContent found");
                return;
            }

            mExecutor.execute(() -> mCallback.onAssistContentAvailable(content));
        }

        @Override
        public void onHandleAssistScreenshot(Bitmap screenshot) {}
    }
}
