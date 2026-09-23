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

package org.avium.systemui.lockscreen.type.mediablur;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

public class MusicController {

    private final MediaController mMediaController;
    private final MusicStateListener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mProgressUpdater;

    public interface MusicStateListener {
        void onMetadataChanged(String title, String artist, Bitmap albumArt);
        void onPlaybackStateChanged(boolean isPlaying);
        void onProgressChanged(long currentPosition, long duration);
    }

    private final MediaController.Callback mCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMetadata();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updatePlaybackState();
        }
    };

    public MusicController(MediaController mediaController, MusicStateListener listener) {
        if (mediaController == null || listener == null) {
            throw new IllegalArgumentException("MediaController and MusicStateListener cannot be null");
        }
        this.mMediaController = mediaController;
        this.mListener = listener;
        this.mMediaController.registerCallback(mCallback);

        this.mProgressUpdater = this::updateProgress;

        initializeState();
    }

    private void initializeState() {
        updateMetadata();
        updatePlaybackState();
    }

    private void updateMetadata() {
        MediaMetadata metadata = mMediaController.getMetadataIfAlive();
        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            mListener.onMetadataChanged(title, artist, albumArt);
        }
    }

    private void updatePlaybackState() {
        PlaybackState state = mMediaController.getPlaybackStateIfAlive();
        if (state != null) {
            boolean isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
            mListener.onPlaybackStateChanged(isPlaying);
            if (isPlaying) {
                startProgressUpdater();
            } else {
                stopProgressUpdater();
            }
        }
    }

    private void updateProgress() {
        PlaybackState state = mMediaController.getPlaybackStateIfAlive();
        MediaMetadata metadata = mMediaController.getMetadataIfAlive();
        if (state != null && metadata != null) {
            long currentPosition = state.getPosition();
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            mListener.onProgressChanged(currentPosition, duration);
        }
        mHandler.postDelayed(mProgressUpdater, 500);
    }

    public void playPause() {
        PlaybackState state = mMediaController.getPlaybackStateIfAlive();
        if (state != null) {
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                mMediaController.getTransportControls().pause();
            } else {
                mMediaController.getTransportControls().play();
            }
        }
    }

    public void nextTrack() {
        mMediaController.getTransportControls().skipToNext();
    }

    public void previousTrack() {
        mMediaController.getTransportControls().skipToPrevious();
    }

    private void startProgressUpdater() {
        mHandler.removeCallbacks(mProgressUpdater);
        mHandler.post(mProgressUpdater);
    }

    private void stopProgressUpdater() {
        mHandler.removeCallbacks(mProgressUpdater);
    }

    public void cleanup() {
        mMediaController.unregisterCallback(mCallback);
        stopProgressUpdater();
    }
}