/*
 * Copyright (C) 2025-2026 The AviumUI Project
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

package org.avium.systemui.keyguard;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemProperties;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;

public class AviumMusicLockscreenManager {
    private static final String TAG = "AviumMusicLockscreenManager";

    private final Context mContext;
    private final Handler mHandler;
    private final AviumMusicLockscreenController mAviumMusicController;
    private final MediaSessionManager mMediaSessionManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;

    private MediaController mMediaController;
    private MediaController.Callback mMediaCallback;
    private boolean mMusicLockscreenDismissed = false;
    private boolean mIsAviumMusicLockscreenShowing = false;
    private boolean mIsAviumMusicLockscreenEnabled = false;
    private Runnable mDelayedHideRunnable;

    public AviumMusicLockscreenManager(
            Context context,
            Handler handler,
            AviumMusicLockscreenController aviumMusicController,
            MediaSessionManager mediaSessionManager,
            SysuiStatusBarStateController statusBarStateController,
            NotificationShadeWindowController notificationShadeWindowController
    ) {
        mContext = context;
        mHandler = handler;
        mAviumMusicController = aviumMusicController;
        mMediaSessionManager = mediaSessionManager;
        mStatusBarStateController = statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;

        mAviumMusicController.setInteractionListener(new AviumMusicLockscreenController.InteractionListener() {
            @Override
            public void onSwipeUpToDismiss() {
                AviumMusicLockscreenManager.this.onSwipeUpToDismiss();
            }

            @Override
            public void onSkipToNext() {
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToPrevious();
                }
            }

            @Override
            public void onPlayPauseToggle() {
                if (mMediaController == null) return;
                PlaybackState state = mMediaController.getPlaybackStateIfAlive();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    mMediaController.getTransportControls().pause();
                } else {
                    mMediaController.getTransportControls().play();
                }
            }
        });

        mMediaCallback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                updateAviumMusicLockscreen();
            }

            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                updateAviumMusicLockscreen();
            }
        };

        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mActiveSessionsListener, null, mHandler);
        updateActiveMediaController();
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveSessionsListener =
            controllers -> updateActiveMediaController();

    private void updateActiveMediaController() {
        var controllers = mMediaSessionManager.getActiveSessions(null);
        MediaController newController = (controllers != null && !controllers.isEmpty())
                ? controllers.get(0) : null;

        if (newController != null && newController.equals(mMediaController)) {
            updateAviumMusicLockscreen();
            return;
        }

        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaCallback);
        }

        mMediaController = newController;

        if (mMediaController != null) {
            mMediaController.registerCallback(mMediaCallback, mHandler);
        }

        if (mMediaController == null) {
            showAviumMusicLockscreen(false);
        } else {
            mMediaController.registerCallback(mMediaCallback, mHandler);
            updateAviumMusicLockscreen();
        }
    }

    public void updateAviumMusicLockscreen() {
        if (mDelayedHideRunnable != null) {
            mHandler.removeCallbacks(mDelayedHideRunnable);
            mDelayedHideRunnable = null;
        }

        checkAviumSystemProperty();

        PlaybackState playbackState = (mMediaController != null)
                ? mMediaController.getPlaybackStateIfAlive() : null;
        MediaMetadata metadata = (mMediaController != null)
                ? mMediaController.getMetadataIfAlive() : null;
        boolean isPlaying = playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PLAYING;

        final int statusBarState = mStatusBarStateController.getState();
        final boolean onKeyguard = (statusBarState == StatusBarState.KEYGUARD);

        boolean shouldShow = onKeyguard
                && !mMusicLockscreenDismissed
                && mIsAviumMusicLockscreenEnabled
                && isPlaying;

        if (shouldShow) {
            showAviumMusicLockscreen(true);
            mAviumMusicController.setMediaController(mMediaController);
            mAviumMusicController.updateMetadata(metadata);
            mAviumMusicController.updatePlaybackState(playbackState);
        } else if (mIsAviumMusicLockscreenShowing) {
            if (!mIsAviumMusicLockscreenEnabled) {
                showAviumMusicLockscreen(false);
            } else {
                mDelayedHideRunnable = () -> showAviumMusicLockscreen(false);
                mHandler.postDelayed(mDelayedHideRunnable, 3000);
            }
        }
    }

    private void checkAviumSystemProperty() {
        String prop = SystemProperties.get("persist.avium.lockscreen.music", "0");
        mIsAviumMusicLockscreenEnabled = "1".equals(prop);
    }

    private void showAviumMusicLockscreen(boolean show) {
        if (mIsAviumMusicLockscreenShowing == show) {
            return;
        }

        ViewGroup rootView = mNotificationShadeWindowController.getWindowRootView();
        if (rootView == null) {
            return;
        }

        if (show) {
            mIsAviumMusicLockscreenShowing = true;
            View aviumView = mAviumMusicController.getView();

            if (aviumView.getParent() != null) {
                ((ViewGroup) aviumView.getParent()).removeView(aviumView);
            }

            rootView.addView(aviumView);
            mAviumMusicController.onShown();
        } else {
            mIsAviumMusicLockscreenShowing = false;
            mAviumMusicController.animateDismissAndHide();
        }
    }

    private void onSwipeUpToDismiss() {
        mMusicLockscreenDismissed = true;
        showAviumMusicLockscreen(false);
    }

    public void onFinishedGoingToSleep() {
        mMusicLockscreenDismissed = false;
    }

    public void onKeyguardShowing() {
        updateAviumMusicLockscreen();
    }

    public void setMediaController(MediaController controller) {
        this.mMediaController = controller;
    }

    public MediaController getMediaController() {
        return mMediaController;
    }
}
