/*
 * Copyright (C) 2026 The AviumUI Project
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
package com.android.wm.shell.bubbles;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.Log;

public class BubbleExt {
    private static final String TAG = "AviumBubble";
    private static final String ACTION_LAUNCH_BUBBLE = "org.avium.LAUNCH_BUBBLE";

    private final Context mContext;
    private final BubbleController mController;

    public BubbleExt(Context context, BubbleController controller) {
        mContext = context;
        mController = controller;
    }

    public void onInit() {
        IntentFilter profiles = new IntentFilter(Intent.ACTION_PROFILE_UNAVAILABLE);
        profiles.addAction(Intent.ACTION_PROFILE_INACCESSIBLE);
        profiles.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
                if (user != null) mController.onAppBubbleProfileUnavailable(user);
            }
        }, profiles, Context.RECEIVER_EXPORTED);

        IntentFilter filter = new IntentFilter(ACTION_LAUNCH_BUBBLE);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String packageName = intent.getStringExtra("package_name");
                if (packageName != null) {
                    Log.d(TAG, "Requesting bubble for: " + packageName);
                    mController.showOrUpdateAppBubble(packageName, UserHandle.of(
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                                    ActivityManager.getCurrentUser())));
                }
            }
        }, filter, "com.android.systemui.permission.LAUNCH_BUBBLE", null,
                Context.RECEIVER_EXPORTED);
    }
}
