/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.jcodeing.kmedia.exo;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.util.Util;
import com.jcodeing.kmedia.IPlayer;
import com.jcodeing.kmedia.video.AControlGroupView;

import java.util.Formatter;
import java.util.Locale;

/**
 * Copy PlaybackControlView {@link com.google.android.exoplayer2.ui.PlaybackControlView}
 */
public class ExoControlGroupView extends AControlGroupView {

  /**
   * Listener to be notified about changes of the visibility of the UI control.
   */
  public interface VisibilityListener {

    /**
     * Called when the visibility changes.
     *
     * @param visibility The new visibility. Either {@link View#VISIBLE} or {@link View#GONE}.
     */
    void onVisibilityChange(int visibility);

  }

  /**
   * Dispatches seek operations to the player.
   */
  public interface SeekDispatcher {

    /**
     * @param player The player to seek.
     * @param windowIndex The index of the window.
     * @param positionMs The seek position in the specified window, or {@link C#TIME_UNSET} to seek
     * to the window's default position.
     * @return True if the seek was dispatched. False otherwise.
     */
    boolean dispatchSeek(ExoPlayer player, int windowIndex, long positionMs);

  }

  /**
   * +  Listener -> Update Progress ...
   */
  public interface UpdateListener {

    void onUpdateProgress(long position, long bufferedPosition, long duration);
  }

  private UpdateListener updateListener;

  public void setUpdateListener(
      UpdateListener updateListener) {
    this.updateListener = updateListener;
  }

  /**
   * Default {@link SeekDispatcher} that dispatches seeks to the player without modification.
   */
  public static final SeekDispatcher DEFAULT_SEEK_DISPATCHER = new SeekDispatcher() {

    @Override
    public boolean dispatchSeek(ExoPlayer player, int windowIndex, long positionMs) {
      player.seekTo(windowIndex, positionMs);
      return true;
    }

  };

  private static final long MAX_POSITION_FOR_SEEK_TO_PREVIOUS = 3000;

  private final StringBuilder formatBuilder;
  private final Formatter formatter;
  private final Timeline.Window currentWindow;

  private SeekDispatcher seekDispatcher;
  private VisibilityListener visibilityListener;


  private boolean dragging;


  private final Runnable updateProgressAction = new Runnable() {
    @Override
    public void run() {
      updateProgress();
    }
  };


  public ExoControlGroupView(Context context) {
    this(context, null);
  }

  public ExoControlGroupView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ExoControlGroupView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    int controllerLayoutId = -1;
    rewindMs = DEFAULT_REWIND_MS;
    fastForwardMs = DEFAULT_FAST_FORWARD_MS;
    if (attrs != null) {
      TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
          R.styleable.PlaybackControlView, 0, 0);
      try {
        rewindMs = a.getInt(R.styleable.PlaybackControlView_rewind_increment, rewindMs);
        fastForwardMs = a.getInt(R.styleable.PlaybackControlView_fastforward_increment,
            fastForwardMs);
        controllerLayoutId = a.getResourceId(R.styleable.PlaybackControlView_controller_layout_id,
            controllerLayoutId);
      } finally {
        a.recycle();
      }
    }
    currentWindow = new Timeline.Window();
    formatBuilder = new StringBuilder();
    formatter = new Formatter(formatBuilder, Locale.getDefault());
    seekDispatcher = DEFAULT_SEEK_DISPATCHER;

    if (controllerLayoutId != -1) {
      LayoutInflater.from(context).inflate(controllerLayoutId, this);
    }
    setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
  }


  @Override
  protected void init() {
    super.init();
    // =========@Init Config@=========
    PROGRESS_BAR_MAX = 1000;
  }

  @Override
  protected AControlGroupView.ComponentListener initGetComponentListener() {
    return new ComponentListener();
  }

  // ============================@Player@============================
  private ExoPlayer player;

  /**
   * Returns the player currently being controlled by this view, or null if no player is set.
   */
  public ExoPlayer getExoPlayer() {
    return player;
  }

  @Override
  public void setPlayer(IPlayer player) {
    //Do nothing
  }

  /**
   * Sets the {@link ExoPlayer} to control.
   *
   * @param player the {@code ExoPlayer} to control.
   */
  public void setExoPlayer(ExoPlayer player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.removeListener((ComponentListener) componentListener);
    }
    this.player = player;
    if (player != null) {
      player.addListener((ComponentListener) componentListener);
    }
    updateAll();
  }

  /**
   * Sets the {@link VisibilityListener}.
   *
   * @param listener The listener to be notified about visibility changes.
   */
  public void setVisibilityListener(VisibilityListener listener) {
    this.visibilityListener = listener;
  }

  /**
   * Sets the {@link SeekDispatcher}.
   *
   * @param seekDispatcher The {@link SeekDispatcher}, or null to use {@link
   * #DEFAULT_SEEK_DISPATCHER}.
   */
  public void setSeekDispatcher(SeekDispatcher seekDispatcher) {
    this.seekDispatcher = seekDispatcher == null ? DEFAULT_SEEK_DISPATCHER : seekDispatcher;
  }

  /**
   * Sets the rewind increment in milliseconds.
   *
   * @param rewindMs The rewind increment in milliseconds. A non-positive value will cause the
   * rewind button to be disabled.
   */
  @Override
  public void setRewindIncrementMs(int rewindMs) {
    super.setRewindIncrementMs(rewindMs);
    updateNavigation();
  }

  /**
   * Sets the fast forward increment in milliseconds.
   *
   * @param fastForwardMs The fast forward increment in milliseconds. A non-positive value will
   * cause the fast forward button to be disabled.
   */
  @Override
  public void setFastForwardIncrementMs(int fastForwardMs) {
    super.setFastForwardIncrementMs(fastForwardMs);
    updateNavigation();
  }


  /**
   * Shows the playback controls. If {@link #getShowTimeoutMs()} is positive then the controls will
   * be automatically hidden after this duration of time has elapsed without user input.
   */
  public boolean show() {
    if (super.show()) {
      updateAll();
      requestPlayPauseFocus();
      return true;
    }
    return false;
  }

  /**
   * Hides the controller.
   */
  public boolean hide() {
    if (super.hide()) {
      if (updateListener == null) {
        removeCallbacks(updateProgressAction);
      }
      return true;
    }
    return false;
  }

  @Override
  public void setVisibility(int visibility) {
    super.setVisibility(visibility);
    if (visibilityListener != null) {
      visibilityListener.onVisibilityChange(visibility);
    }
  }

  @Override
  protected void updateAll() {
    updatePlayPauseButton();
    updateNavigation();
    updateProgress();
  }

  private void updatePlayPauseButton() {
    if (!isVisibleByPlayController() || !isAttachedToWindow) {
      return;
    }
    boolean requestPlayPauseFocus = false;
    boolean playing = player != null && player.getPlayWhenReady();
    if (playView != null) {
      requestPlayPauseFocus |= playing && playView.isFocused();
      playView.setVisibility(playing ? GONE : VISIBLE);
    }
    if (pauseView != null) {
      requestPlayPauseFocus |= !playing && pauseView.isFocused();
      pauseView.setVisibility(!playing ? GONE : VISIBLE);
    }
    if (requestPlayPauseFocus) {
      requestPlayPauseFocus();
    }
  }

  private void updateNavigation() {
    if (!isVisibleByPlayController() || !isAttachedToWindow) {
      return;
    }
    Timeline currentTimeline = player != null ? player.getCurrentTimeline() : null;
    boolean haveNonEmptyTimeline = currentTimeline != null && !currentTimeline.isEmpty();
    boolean isSeekable = false;
    boolean enablePrevious = false;
    boolean enableNext = false;
    if (haveNonEmptyTimeline) {
      int currentWindowIndex = player.getCurrentWindowIndex();
      currentTimeline.getWindow(currentWindowIndex, currentWindow);
      isSeekable = currentWindow.isSeekable;
      enablePrevious = currentWindowIndex > 0 || isSeekable || !currentWindow.isDynamic;
      enableNext = (currentWindowIndex < currentTimeline.getWindowCount() - 1)
          || currentWindow.isDynamic;
    }
    setButtonEnabled(enablePrevious, previousView);
    setButtonEnabled(enableNext, nextView);
    setButtonEnabled(fastForwardMs > 0 && isSeekable, fastForwardView);
    setButtonEnabled(rewindMs > 0 && isSeekable, rewindView);
    if (progressBar != null) {
      progressBar.setEnabled(isSeekable);
    }
    if (progressAny != null) {
      progressAny.setEnabled(isSeekable);
    }
  }

  private void updateProgress() {
    if ((updateListener == null && !isVisibleByPlayController()) || !isAttachedToWindow) {
      return;
    }
    long duration = player == null ? 0 : player.getDuration();
    long position = player == null ? 0 : player.getCurrentPosition();
    long bufferedPosition = player == null ? 0 : player.getBufferedPosition();
    if (updateListener != null) {
      updateListener.onUpdateProgress(position, bufferedPosition, duration);
    }
    updateProgressView(position, bufferedPosition, duration);

    removeCallbacks(updateProgressAction);
    // Schedule an update if necessary.
    int playbackState = player == null ? ExoPlayer.STATE_IDLE : player.getPlaybackState();
    if (playbackState != ExoPlayer.STATE_IDLE && playbackState != ExoPlayer.STATE_ENDED) {
      long delayMs;
      if (player.getPlayWhenReady() && playbackState == ExoPlayer.STATE_READY) {
        delayMs = 1000 - (position % 1000);
        if (delayMs < 200) {
          delayMs += 1000;
        }
      } else {
        delayMs = 1000;
      }
      postDelayed(updateProgressAction, delayMs);
    }
  }

  private void updateProgressView(long position, long bufferedPosition, long duration) {
    if (!isVisibleByPlayController() || !isAttachedToWindow) {
      return;
    }
    if (durationTv != null) {
      durationTv.setText(stringForTime(duration));
    }
    if (positionTv != null && !dragging) {
      positionTv.setText(stringForTime(position));
    }

    if (progressBar != null) {
      if (!dragging) {
        progressBar.setProgress(progressValue(position));
      }
      progressBar.setSecondaryProgress(progressValue(bufferedPosition));
      // Remove scheduled updates.
    }
    if (progressAny != null) {
      if (!dragging) {
        progressAny.setProgress(progressValue(position));
      }
      progressAny.setSecondaryProgress(progressValue(bufferedPosition));
      // Remove scheduled updates.
    }
  }

  private void requestPlayPauseFocus() {
    boolean playing = player != null && player.getPlayWhenReady();
    if (!playing && playView != null) {
      playView.requestFocus();
    } else if (playing && pauseView != null) {
      pauseView.requestFocus();
    }
  }

  private void setButtonEnabled(boolean enabled, View view) {
    if (view == null) {
      return;
    }
    view.setEnabled(enabled);
    if (Util.SDK_INT >= 11) {
      setViewAlphaV11(view, enabled ? 1f : 0.3f);
      view.setVisibility(VISIBLE);
    } else {
      view.setVisibility(enabled ? VISIBLE : INVISIBLE);
    }
  }

  @TargetApi(11)
  private void setViewAlphaV11(View view, float alpha) {
    view.setAlpha(alpha);
  }

  private String stringForTime(long timeMs) {
    if (timeMs == C.TIME_UNSET) {
      timeMs = 0;
    }
    long totalSeconds = (timeMs + 500) / 1000;
    long seconds = totalSeconds % 60;
    long minutes = (totalSeconds / 60) % 60;
    long hours = totalSeconds / 3600;
    formatBuilder.setLength(0);
    return hours > 0 ? formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
        : formatter.format("%02d:%02d", minutes, seconds).toString();
  }

  protected int progressValue(long position) {
    long duration = player == null ? C.TIME_UNSET : player.getDuration();
    return duration == C.TIME_UNSET || duration == 0 ? 0
        : (int) ((position * PROGRESS_BAR_MAX) / duration);
  }

  protected long positionValue(int progress) {
    long duration = player == null ? C.TIME_UNSET : player.getDuration();
    return duration == C.TIME_UNSET ? 0 : ((duration * progress) / PROGRESS_BAR_MAX);
  }

  // ============================@Media Correlation@============================
  @Override
  public boolean isPlayable() {
    return player != null;
  }

  @Override
  public void play(int order) {
    if (!isPlayable()) {
      return;
    }
    if (order == 1) {
      player.setPlayWhenReady(true);
    } else if (order == 0) {
      player.setPlayWhenReady(false);
    } else {
      player.setPlayWhenReady(!player.getPlayWhenReady());
    }
  }

  @Override
  public void seekTo(long positionMs) {
    if (!isPlayable()) {
      return;
    }
    seekTo(player.getCurrentWindowIndex(), positionMs);
  }

  private void seekTo(int windowIndex, long positionMs) {
    if (!isPlayable()) {
      return;
    }
    boolean dispatched = seekDispatcher.dispatchSeek(player, windowIndex, positionMs);
    if (!dispatched) {
      // The seek wasn't dispatched. If the progress bar was dragged by the user to perform the
      // seek then it'll now be in the wrong position. Trigger a progress update to snap it back.
      updateProgress();
    }
    showBufferingView(true);
  }

  @Override
  public void rewind() {
    if (rewindMs <= 0 || player == null) {
      return;
    }
    seekTo(Math.max(player.getCurrentPosition() - rewindMs, 0));
  }

  @Override
  public void fastForward() {
    if (fastForwardMs <= 0 || player == null) {
      return;
    }
    seekTo(Math.min(player.getCurrentPosition() + fastForwardMs, player.getDuration()));
  }

  @Override
  public void previous() {
    Timeline currentTimeline = player.getCurrentTimeline();
    if (currentTimeline.isEmpty()) {
      return;
    }
    int currentWindowIndex = player.getCurrentWindowIndex();
    currentTimeline.getWindow(currentWindowIndex, currentWindow);
    if (currentWindowIndex > 0 && (player.getCurrentPosition() <= MAX_POSITION_FOR_SEEK_TO_PREVIOUS
        || (currentWindow.isDynamic && !currentWindow.isSeekable))) {
      seekTo(currentWindowIndex - 1, C.TIME_UNSET);
    } else {
      seekTo(0);
    }
  }

  @Override
  public void next() {
    Timeline currentTimeline = player.getCurrentTimeline();
    if (currentTimeline.isEmpty()) {
      return;
    }
    int currentWindowIndex = player.getCurrentWindowIndex();
    if (currentWindowIndex < currentTimeline.getWindowCount() - 1) {
      seekTo(currentWindowIndex + 1, C.TIME_UNSET);
    } else if (currentTimeline.getWindow(currentWindowIndex, currentWindow, false).isDynamic) {
      seekTo(currentWindowIndex, C.TIME_UNSET);
    }
  }


  @Override
  public void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateAll();
  }

  @Override
  public void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    removeCallbacks(updateProgressAction);
  }


  // ============================@ComponentListener@============================
  private final class ComponentListener extends AControlGroupView.ComponentListener implements
      ExoPlayer.EventListener {

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      updatePlayPauseButton();
      updateProgress();
      if (playbackState == ExoPlayer.STATE_BUFFERING) {
        showBufferingView(true);
      } else if (playbackState == ExoPlayer.STATE_READY) {
        showBufferingView(false);
      }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    @Override
    public void onPositionDiscontinuity() {
      updateNavigation();
      updateProgress();
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      // Do nothing.
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
      updateNavigation();
      updateProgress();
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
      // Do nothing.
    }

    @Override
    public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
      // Do nothing.
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
      // Do nothing.
    }

    // ============================@SeekBar.OnSeekBarChangeListener
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
      removeCallbacks(hideAction);
      dragging = true;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
      if (fromUser) {
        long position = positionValue(progress);
        if (positionTv != null) {
          positionTv.setText(stringForTime(position));
        }
        if (player != null && !dragging) {
          seekTo(position);
        }
      }
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
      dragging = false;
      if (player != null) {
        seekTo(positionValue(seekBar.getProgress()));
      }
      hideAfterTimeout();
    }
  }
}
