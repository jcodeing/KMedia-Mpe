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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.id3.ApicFrame;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.util.Assertions;
import com.jcodeing.kmedia.IPlayer;
import com.jcodeing.kmedia.video.APlayerView;
import java.util.List;

/**
 * Copy SimpleExoPlayerView extends PlayerView {@link APlayerView} PlaybackControlView->ExoControlGroupView
 */
@TargetApi(16)
public final class ExoPlayerView extends APlayerView<ExoPlayerView> {

  public static final int SURFACE_TYPE_NONE = 0;
  public static final int SURFACE_TYPE_SURFACE_VIEW = 1;
  public static final int SURFACE_TYPE_TEXTURE_VIEW = 2;

  private final AspectRatioFrameLayout contentFrame;
  private final View shutterView;
  private final View surfaceView;
  private final ImageView artworkView;
  private final SubtitleView subtitleView;

  private final ComponentListener componentListener;
  private final FrameLayout overlayFrameLayout;

  private boolean useArtwork;
  private Bitmap defaultArtwork;
  private int controllerShowTimeoutMs;

  public ExoPlayerView(Context context) {
    this(context, null);
  }

  public ExoPlayerView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    int playerLayoutId = R.layout.exo_player_view;
    boolean useArtwork = true;
    int defaultArtworkId = 0;
    int surfaceType = SURFACE_TYPE_SURFACE_VIEW;
    int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
    int controllerShowTimeoutMs = ExoControlGroupView.DEFAULT_SHOW_TIMEOUT_MS;
    if (attrs != null) {
      TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
          R.styleable.SimpleExoPlayerView, 0, 0);
      try {
        playerLayoutId = a.getResourceId(R.styleable.SimpleExoPlayerView_player_layout_id,
            playerLayoutId);
        useArtwork = a.getBoolean(R.styleable.SimpleExoPlayerView_use_artwork, useArtwork);
        defaultArtworkId = a.getResourceId(R.styleable.SimpleExoPlayerView_default_artwork,
            defaultArtworkId);
        surfaceType = a.getInt(R.styleable.SimpleExoPlayerView_surface_type, surfaceType);
        resizeMode = a.getInt(R.styleable.SimpleExoPlayerView_resize_mode, resizeMode);
        controllerShowTimeoutMs = a.getInt(R.styleable.SimpleExoPlayerView_show_timeout,
            controllerShowTimeoutMs);
      } finally {
        a.recycle();
      }
    }

    LayoutInflater.from(context).inflate(playerLayoutId, this);
    componentListener = new ComponentListener();
    setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

    // Content frame.
    contentFrame = (AspectRatioFrameLayout) findViewById(R.id.exo_content_frame);
    if (contentFrame != null) {
      setResizeModeRaw(contentFrame, resizeMode);
    }

    // Shutter view.
    shutterView = findViewById(R.id.exo_shutter);

    // Create a surface view and insert it into the content frame, if there is one.
    if (contentFrame != null && surfaceType != SURFACE_TYPE_NONE) {
      ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      surfaceView = surfaceType == SURFACE_TYPE_TEXTURE_VIEW ? new TextureView(context)
          : new SurfaceView(context);
      surfaceView.setLayoutParams(params);
      contentFrame.addView(surfaceView, 0);
    } else {
      surfaceView = null;
    }

    // Overlay frame layout.
    overlayFrameLayout = (FrameLayout) findViewById(R.id.exo_overlay);

    // Artwork view.
    artworkView = (ImageView) findViewById(R.id.exo_artwork);
    this.useArtwork = useArtwork && artworkView != null;
    if (defaultArtworkId != 0) {
      defaultArtwork = BitmapFactory.decodeResource(context.getResources(), defaultArtworkId);
    }

    // Subtitle view.
    subtitleView = (SubtitleView) findViewById(R.id.exo_subtitles);
    if (subtitleView != null) {
      subtitleView.setUserDefaultStyle();
      subtitleView.setUserDefaultTextSize();
    }

    // Playback control view. [ExoControlGroupView]
    controlGroupView = (ExoControlGroupView) findViewById(R.id.k_ctrl_group);
    this.controllerShowTimeoutMs = useControlGroup ? controllerShowTimeoutMs : 0;
    hideController();
  }

  // ============================@Player@============================
  private SimpleExoPlayer player;

  /**
   * Returns the player currently set on this view, or null if no player is set.
   */
  public SimpleExoPlayer getSimpleExoPlayer() {
    return player;
  }

  @Override
  public ExoPlayerView setPlayer(IPlayer player) {
    return this;
  }

  /**
   * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#setTextOutput} and {@link
   * SimpleExoPlayer#setVideoListener} method of the player will be called and previous assignments
   * are overridden.
   *
   * @param player The {@link SimpleExoPlayer} to use.
   */
  public void setPlayer(SimpleExoPlayer player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.setTextOutput(null);
      this.player.setVideoListener(null);
      this.player.removeListener(componentListener);
      this.player.setVideoSurface(null);
    }
    this.player = player;
    if (useControlGroup) {
      ((ExoControlGroupView) controlGroupView).setExoPlayer(player);
    }
    if (shutterView != null) {
      shutterView.setVisibility(VISIBLE);
    }
    if (player != null) {
      if (surfaceView instanceof TextureView) {
        player.setVideoTextureView((TextureView) surfaceView);
      } else if (surfaceView instanceof SurfaceView) {
        player.setVideoSurfaceView((SurfaceView) surfaceView);
      }
      player.setVideoListener(componentListener);
      player.addListener(componentListener);
      player.setTextOutput(componentListener);
      maybeShowController(false);
      updateForCurrentTrackSelections();
    } else {
      hideController();
      hideArtwork();
    }
  }

  /**
   * Sets the resize mode.
   *
   * @param resizeMode The resize mode.
   */
  public void setResizeMode(@ResizeMode int resizeMode) {
    Assertions.checkState(contentFrame != null);
    contentFrame.setResizeMode(resizeMode);
  }

  /**
   * Returns whether artwork is displayed if present in the media.
   */
  public boolean getUseArtwork() {
    return useArtwork;
  }

  /**
   * Sets whether artwork is displayed if present in the media.
   *
   * @param useArtwork Whether artwork is displayed.
   */
  public void setUseArtwork(boolean useArtwork) {
    Assertions.checkState(!useArtwork || artworkView != null);
    if (this.useArtwork != useArtwork) {
      this.useArtwork = useArtwork;
      updateForCurrentTrackSelections();
    }
  }

  /**
   * Returns the default artwork to display.
   */
  public Bitmap getDefaultArtwork() {
    return defaultArtwork;
  }

  /**
   * Sets the default artwork to display if {@code useArtwork} is {@code true} and no artwork is
   * present in the media.
   *
   * @param defaultArtwork the default artwork to display.
   */
  public void setDefaultArtwork(Bitmap defaultArtwork) {
    if (this.defaultArtwork != defaultArtwork) {
      this.defaultArtwork = defaultArtwork;
      updateForCurrentTrackSelections();
    }
  }

  /**
   * Shows the playback controls. Does nothing if playback controls are disabled.
   */
  public void showController() {
    if (useControlGroup) {
      maybeShowController(true);
    }
  }

  /**
   * Hides the playback controls. Does nothing if playback controls are disabled.
   */
  public void hideController() {
    if (controlGroupView != null) {
      controlGroupView.hide(false);
    }
  }

  /**
   * Returns the playback controls timeout. The playback controls are automatically hidden after
   * this duration of time has elapsed without user input and with playback or buffering in
   * progress.
   *
   * @return The timeout in milliseconds. A non-positive value will cause the controller to remain
   * visible indefinitely.
   */
  public int getControllerShowTimeoutMs() {
    return controllerShowTimeoutMs;
  }

  /**
   * Sets the playback controls timeout. The playback controls are automatically hidden after this
   * duration of time has elapsed without user input and with playback or buffering in progress.
   *
   * @param controllerShowTimeoutMs The timeout in milliseconds. A non-positive value will cause the
   * controller to remain visible indefinitely.
   */
  public void setControllerShowTimeoutMs(int controllerShowTimeoutMs) {
    Assertions.checkState(controlGroupView != null);
    this.controllerShowTimeoutMs = controllerShowTimeoutMs;
  }

  /**
   * Set the {@link ExoControlGroupView.VisibilityListener}.
   *
   * @param listener The listener to be notified about visibility changes.
   */
  public void setControllerVisibilityListener(ExoControlGroupView.VisibilityListener listener) {
    Assertions.checkState(controlGroupView != null);
    ((ExoControlGroupView) controlGroupView).setVisibilityListener(listener);
  }

  /**
   * Sets the {@link ExoControlGroupView.SeekDispatcher}.
   *
   * @param seekDispatcher The {@link ExoControlGroupView.SeekDispatcher}, or null to use {@link
   * ExoControlGroupView#DEFAULT_SEEK_DISPATCHER}.
   */
  public void setSeekDispatcher(ExoControlGroupView.SeekDispatcher seekDispatcher) {
    Assertions.checkState(controlGroupView != null);
    ((ExoControlGroupView) controlGroupView).setSeekDispatcher(seekDispatcher);
  }

  public void setUpdateListener(
      ExoControlGroupView.UpdateListener updateListener) {
    Assertions.checkState(controlGroupView != null);
    ((ExoControlGroupView) controlGroupView).setUpdateListener(updateListener);
  }

  /**
   * Sets the rewind increment in milliseconds.
   *
   * @param rewindMs The rewind increment in milliseconds.
   */
  public void setRewindIncrementMs(int rewindMs) {
    Assertions.checkState(controlGroupView != null);
    controlGroupView.setRewindIncrementMs(rewindMs);
  }

  /**
   * Sets the fast forward increment in milliseconds.
   *
   * @param fastForwardMs The fast forward increment in milliseconds.
   */
  public void setFastForwardIncrementMs(int fastForwardMs) {
    Assertions.checkState(controlGroupView != null);
    controlGroupView.setFastForwardIncrementMs(fastForwardMs);
  }

  /**
   * Gets the view onto which video is rendered. This is either a {@link SurfaceView} (default) or a
   * {@link TextureView} if the {@code use_texture_view} view attribute has been set to true.
   *
   * @return Either a {@link SurfaceView} or a {@link TextureView}.
   */
  public View getVideoSurfaceView() {
    return surfaceView;
  }

  /**
   * Gets the overlay {@link FrameLayout}, which can be populated with UI elements to show on top of
   * the player.
   *
   * @return The overlay {@link FrameLayout}, or {@code null} if the layout has been customized and
   * the overlay is not present.
   */
  public FrameLayout getOverlayFrameLayout() {
    return overlayFrameLayout;
  }

  /**
   * Gets the {@link SubtitleView}.
   *
   * @return The {@link SubtitleView}, or {@code null} if the layout has been customized and the
   * subtitle view is not present.
   */
  public SubtitleView getSubtitleView() {
    return subtitleView;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!useControlGroup || player == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
      return false;
    }
    if (controlGroupView.isVisibleByInteractionArea()) {
      controlGroupView.hide();
    } else {
      maybeShowController(true);
    }
    return true;
  }

  @Override
  public boolean onTrackballEvent(MotionEvent ev) {
    if (!useControlGroup || player == null) {
      return false;
    }
    maybeShowController(true);
    return true;
  }

  private void maybeShowController(boolean isForced) {
    if (!useControlGroup || player == null) {
      return;
    }
    int playbackState = player.getPlaybackState();
    boolean showIndefinitely = playbackState == ExoPlayer.STATE_IDLE
        || playbackState == ExoPlayer.STATE_ENDED || !player.getPlayWhenReady();
    boolean wasShowingIndefinitely =
        controlGroupView.isVisibleByInteractionArea() && controlGroupView.getShowTimeoutMs() <= 0;
    controlGroupView.setShowTimeoutMs(showIndefinitely ? 0 : controllerShowTimeoutMs);
    if (isForced || showIndefinitely || wasShowingIndefinitely) {
      controlGroupView.show();
    }
  }

  private void updateForCurrentTrackSelections() {
    if (player == null) {
      return;
    }
    TrackSelectionArray selections = player.getCurrentTrackSelections();
    for (int i = 0; i < selections.length; i++) {
      if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
        // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
        // onRenderedFirstFrame().
        hideArtwork();
        return;
      }
    }
    // Video disabled so the shutter must be closed.
    if (shutterView != null) {
      shutterView.setVisibility(VISIBLE);
    }
    // Display artwork if enabled and available, else hide it.
    if (useArtwork) {
      for (int i = 0; i < selections.length; i++) {
        TrackSelection selection = selections.get(i);
        if (selection != null) {
          for (int j = 0; j < selection.length(); j++) {
            Metadata metadata = selection.getFormat(j).metadata;
            if (metadata != null && setArtworkFromMetadata(metadata)) {
              return;
            }
          }
        }
      }
      if (setArtworkFromBitmap(defaultArtwork)) {
        return;
      }
    }
    // Artwork disabled or unavailable.
    hideArtwork();
  }

  private boolean setArtworkFromMetadata(Metadata metadata) {
    for (int i = 0; i < metadata.length(); i++) {
      Metadata.Entry metadataEntry = metadata.get(i);
      if (metadataEntry instanceof ApicFrame) {
        byte[] bitmapData = ((ApicFrame) metadataEntry).pictureData;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length);
        return setArtworkFromBitmap(bitmap);
      }
    }
    return false;
  }

  private boolean setArtworkFromBitmap(Bitmap bitmap) {
    if (bitmap != null) {
      int bitmapWidth = bitmap.getWidth();
      int bitmapHeight = bitmap.getHeight();
      if (bitmapWidth > 0 && bitmapHeight > 0) {
        if (contentFrame != null) {
          contentFrame.setAspectRatio((float) bitmapWidth / bitmapHeight);
        }
        artworkView.setImageBitmap(bitmap);
        artworkView.setVisibility(VISIBLE);
        return true;
      }
    }
    return false;
  }

  private void hideArtwork() {
    if (artworkView != null) {
      artworkView.setImageResource(android.R.color.transparent); // Clears any bitmap reference.
      artworkView.setVisibility(INVISIBLE);
    }
  }

  @SuppressWarnings("ResourceType")
  private static void setResizeModeRaw(AspectRatioFrameLayout aspectRatioFrame, int resizeMode) {
    aspectRatioFrame.setResizeMode(resizeMode);
  }

  private final class ComponentListener implements SimpleExoPlayer.VideoListener,
      TextRenderer.Output, ExoPlayer.EventListener {

    // TextRenderer.Output implementation

    @Override
    public void onCues(List<Cue> cues) {
      if (subtitleView != null) {
        subtitleView.onCues(cues);
      }
    }

    // SimpleExoPlayer.VideoListener implementation

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      if (contentFrame != null) {
        float aspectRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;
        contentFrame.setAspectRatio(aspectRatio);
      }
    }

    @Override
    public void onRenderedFirstFrame() {
      if (shutterView != null) {
        shutterView.setVisibility(INVISIBLE);
      }
    }

    @Override
    public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
      updateForCurrentTrackSelections();
    }

    // ExoPlayer.EventListener implementation

    @Override
    public void onLoadingChanged(boolean isLoading) {
      // Do nothing.
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      maybeShowController(false);
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
      // Do nothing.
    }

    @Override
    public void onPositionDiscontinuity() {
      // Do nothing.
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      // Do nothing.
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
      // Do nothing.
    }

  }

}
