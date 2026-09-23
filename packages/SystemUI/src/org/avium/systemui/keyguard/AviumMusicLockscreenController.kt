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

package org.avium.systemui.keyguard

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.android.systemui.res.R
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.S)
class AviumMusicLockscreenController @Inject constructor(
    private val context: Context,
    private val statusBarKeyguardViewManagerProvider: Lazy<com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager>
) {

    interface InteractionListener {
        fun onSwipeUpToDismiss()
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onPlayPauseToggle()
    }
    private var interactionListener: InteractionListener? = null
    fun setInteractionListener(listener: InteractionListener) {
        this.interactionListener = listener
    }

    val view: View = View.inflate(context, R.layout.avium_music_lockscreen, null)

    private val titleView: TextView = view.findViewById(R.id.music_title)
    private val artistView: TextView = view.findViewById(R.id.music_artist)
    private val albumArtView: ImageView = view.findViewById(R.id.album_art)
    private val vinylContainer: View = view.findViewById(R.id.vinyl_container)
    private val prevButton: ImageButton = view.findViewById(R.id.button_prev)
    private val nextButton: ImageButton = view.findViewById(R.id.button_next)
    private val playPauseButton: ImageButton = view.findViewById(R.id.button_play_pause)

    private var mediaController: MediaController? = null
    private var albumArtAnimator: ObjectAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var initialX = 0f
    private var initialY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isDragging = false
    private var isVerticalDrag = false
    private var isDozing = false


    private val touchListener = View.OnTouchListener { v, event ->
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                initialX = event.x
                initialY = event.y
                activePointerId = event.getPointerId(0)
                v.animate().cancel()
                vinylContainer.animate().cancel()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return@OnTouchListener true
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return@OnTouchListener true

                val currentX = event.getX(pointerIndex)
                val currentY = event.getY(pointerIndex)
                val dx = currentX - initialX
                val dy = currentY - initialY

                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop.toFloat())) {
                    isDragging = true
                    isVerticalDrag = abs(dy) > abs(dx)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isDragging) {
                    if (isVerticalDrag) {
                        val dampedDy = dy / 2f
                        view.translationY = dampedDy
                        
                        val progress = (abs(dy) / (v.height * 0.5f)).coerceIn(0f, 1f)
                        val scale = 1f - progress * 0.1f
                        val blurRadius = if (dy < 0) progress * 50f else 0f

                        view.scaleX = scale
                        view.scaleY = scale
                        if (dy < 0) {
                            view.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
                        } else {
                            view.setRenderEffect(null)
                        }
                    } else {
                        vinylContainer.translationX = dx
                        vinylContainer.rotation = dx / v.width * 15f
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    return@OnTouchListener false
                }

                velocityTracker!!.computeCurrentVelocity(1000)
                val velocityX = velocityTracker!!.getXVelocity(activePointerId)
                val velocityY = velocityTracker!!.getYVelocity(activePointerId)

                if (isVerticalDrag) {
                    val currentTranslationY = view.translationY 
                    if (abs(currentTranslationY) > v.height / 5 || velocityY < -800) {
                        interactionListener?.onSwipeUpToDismiss()
                    } else {
                        animateToInitialState()
                    }
                } else {
                    val translationX = vinylContainer.translationX
                    if (translationX > v.width / 3 || velocityX > 1500) {
                        animateRecordOffScreen(true)
                    } else if (translationX < -v.width / 3 || velocityX < -1500) {
                        animateRecordOffScreen(false)
                    } else {
                        animateRecordToCenter()
                    }
                }

                isDragging = false
                isVerticalDrag = false
                velocityTracker?.recycle()
                velocityTracker = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                true
            }
            else -> false
        }
    }

    private fun animateRecordToCenter() {
        vinylContainer.animate()
            .translationX(0f)
            .rotation(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateRecordOffScreen(toRight: Boolean) {
        val targetX = if (toRight) view.width.toFloat() else -view.width.toFloat()
        vinylContainer.animate()
            .translationX(targetX)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (toRight) {
                    interactionListener?.onSkipToPrevious()
                } else {
                    interactionListener?.onSkipToNext()
                }
                vinylContainer.translationX = 0f
                vinylContainer.alpha = 1f
            }
            .start()
    }


    private fun initAnimator() {
        albumArtAnimator = ObjectAnimator.ofFloat(albumArtView, "rotation", 0f, 360f).apply {
            duration = 20000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    init {
        view.setOnTouchListener(touchListener)
        setupClickListeners()
        initAnimator()
    }

    fun animateDismissAndHide() {
        view.animate()
            .translationY(-view.height * 0.4f) 
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(250) 
            .setInterpolator(DecelerateInterpolator())
            .withStartAction {
                view.setRenderEffect(null)
            }
            .withEndAction {
                view.visibility = View.GONE
                val rootView = view.parent as? ViewGroup
                rootView?.removeView(view) 
                onHidden() 
            }
            .start()
    }

    fun animateToInitialState() {
        view.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withStartAction {
                view.setRenderEffect(null)
            }
            .start()
    }

    fun setMediaController(controller: MediaController?) {
        this.mediaController = controller
    }

    fun updateMetadata(metadata: MediaMetadata?) {
        titleView.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
        artistView.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val albumArtBitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (albumArtBitmap != null) {
            albumArtView.setImageBitmap(albumArtBitmap)
        } else {
            albumArtView.setImageResource(android.R.color.darker_gray)
        }
    }

    fun updatePlaybackState(state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.avium_ic_media_pause)
            albumArtAnimator?.takeIf { it.isPaused }?.resume()
        } else {
            playPauseButton.setImageResource(R.drawable.avium_ic_media_play)
            albumArtAnimator?.takeIf { it.isRunning }?.pause()
        }
    }

    private fun setupClickListeners() {
        prevButton.setOnClickListener {
            animateRecordOffScreen(true)
        }
        nextButton.setOnClickListener {
            animateRecordOffScreen(false)
        }
        playPauseButton.setOnClickListener {
            interactionListener?.onPlayPauseToggle()
        }
    }
    
    fun onShown() {
        if (isDozing) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.translationY = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.setRenderEffect(null)
        
        vinylContainer.translationX = 0f
        vinylContainer.rotation = 0f
        vinylContainer.alpha = 1f

        if (albumArtAnimator?.isStarted == false) {
            albumArtAnimator?.start()
        }
        updatePlaybackState(mediaController?.playbackStateIfAlive)
    }

    fun onHidden() {
        albumArtAnimator?.cancel()
    }

    fun onDozingChanged(isDozing: Boolean) {
        this.isDozing = isDozing
        if (isDozing) {
            view.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    view.visibility = View.GONE
                }
                .start()
        } else {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.setRenderEffect(null)
            
            vinylContainer.translationX = 0f
            vinylContainer.rotation = 0f
            vinylContainer.alpha = 1f
            
            view.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}