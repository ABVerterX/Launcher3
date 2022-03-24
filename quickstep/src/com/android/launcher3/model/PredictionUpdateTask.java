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
package com.android.launcher3.model;

import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT;
import static com.android.launcher3.model.QuickstepModelDelegate.LAST_PREDICTION_ENABLED_STATE;
import static com.android.quickstep.InstantAppResolverImpl.COMPONENT_CLASS_MARKER;

import android.app.prediction.AppTarget;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.Utilities;
import com.android.launcher3.model.BgDataModel.FixedContainerItems;
import com.android.launcher3.model.QuickstepModelDelegate.PredictorState;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task to update model as a result of predicted apps update
 */
public class PredictionUpdateTask extends BaseModelUpdateTask {

    private final List<AppTarget> mTargets;
    private final PredictorState mPredictorState;

    PredictionUpdateTask(PredictorState predictorState, List<AppTarget> targets) {
        mPredictorState = predictorState;
        mTargets = targets;
    }

    @Override
    public void execute(LauncherAppState app, BgDataModel dataModel, AllAppsList apps) {
        Context context = app.getContext();

        // TODO: remove this
        Utilities.getDevicePrefs(context).edit()
                .putBoolean(LAST_PREDICTION_ENABLED_STATE, !mTargets.isEmpty()).apply();

        Set<UserHandle> usersForChangedShortcuts =
                dataModel.extraItems.get(mPredictorState.containerId).items.stream()
                        .filter(info -> info.itemType == ITEM_TYPE_DEEP_SHORTCUT)
                        .map(info -> info.user)
                        .collect(Collectors.toSet());

        FixedContainerItems fci = new FixedContainerItems(mPredictorState.containerId);
        for (AppTarget target : mTargets) {
            WorkspaceItemInfo itemInfo;
            ShortcutInfo si = target.getShortcutInfo();
            if (si != null) {
                usersForChangedShortcuts.add(si.getUserHandle());
                itemInfo = new WorkspaceItemInfo(si, context);
                app.getIconCache().getShortcutIcon(itemInfo, si);
            } else {
                String className = target.getClassName();
                if (COMPONENT_CLASS_MARKER.equals(className)) {
                    // TODO: Implement this
                    continue;
                }
                ComponentName cn = new ComponentName(target.getPackageName(), className);
                UserHandle user = target.getUser();
                itemInfo = apps.data.stream()
                        .filter(info -> user.equals(info.user) && cn.equals(info.componentName))
                        .map(ai -> {
                            app.getIconCache().getTitleAndIcon(ai, false);
                            return ai.makeWorkspaceItem();
                        })
                        .findAny()
                        .orElseGet(() -> {
                            LauncherActivityInfo lai = context.getSystemService(LauncherApps.class)
                                    .resolveActivity(AppInfo.makeLaunchIntent(cn), user);
                            if (lai == null) {
                                return null;
                            }
                            AppInfo ai = new AppInfo(context, lai, user);
                            app.getIconCache().getTitleAndIcon(ai, lai, false);
                            return ai.makeWorkspaceItem();
                        });

                if (itemInfo == null) {
                    continue;
                }
            }

            itemInfo.container = fci.containerId;
            fci.items.add(itemInfo);
        }

        dataModel.extraItems.put(fci.containerId, fci);
        bindExtraContainerItems(fci);
        usersForChangedShortcuts.forEach(
                u -> dataModel.updateShortcutPinnedState(app.getContext(), u));

        // Save to disk
        mPredictorState.storage.write(context, fci.items);
    }
}
