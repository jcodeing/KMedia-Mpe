/*
 * Copyright (c) 2017 K Sun <jcodeing@gmail.com>
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

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.C.ContentType;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.offline.FilteringManifestParser;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistParserFactory;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;
import com.jcodeing.kmedia.AMediaPlayer;
import com.jcodeing.kmedia.utils.L;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class ExoMediaPlayer extends AMediaPlayer {

  private final String TAG = L.makeTag("ExoMP@" + Integer.toHexString(hashCode()));

  private Context context;

  private PlayerListener playerListener;
  private final SimpleExoPlayer internalPlayer;

  public ExoMediaPlayer(Context context) {
    this.context = context.getApplicationContext();
    // =========@Init@=========
    dataSourceFactory = buildDataSourceFactory();

    // =========@Player
    internalPlayer = initInternalPlayer();
    playerListener = new PlayerListener();
    internalPlayer.addListener(playerListener);
    internalPlayer.addVideoListener(playerListener);
    internalPlayer.setPlayWhenReady(false);
  }

  protected DataSource.Factory buildDataSourceFactory() {
    DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
    return new DefaultDataSourceFactory(this.context,
        Util.getUserAgent(this.context, "ExoMediaPlayer"), bandwidthMeter);
  }

  protected SimpleExoPlayer initInternalPlayer() {
    return ExoPlayerFactory.newSimpleInstance(context, new DefaultTrackSelector());
  }

  public SimpleExoPlayer internalPlayer() {
    return internalPlayer;
  }

  // ============================@Source@============================
  private MediaSource mediaSource;
  private DataSource.Factory dataSourceFactory;

  @Override
  public void setDataSource(Context context, Uri uri, Map<String, String> headers)
      throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
    super.setDataSource(context, uri, headers);
    mediaSource = buildMediaSource(uri, "");
    setPlaybackState(STATE_GOT_SOURCE);
  }

  private MediaSource buildMediaSource(Uri uri, @Nullable String overrideExtension) {
    @ContentType int type = Util.inferContentType(uri, overrideExtension);
    switch (type) {
      case C.TYPE_DASH:
        return new DashMediaSource.Factory(dataSourceFactory)
            .setManifestParser(
                new FilteringManifestParser<>(new DashManifestParser(),
                    Collections.<StreamKey>emptyList()))
            .createMediaSource(uri);
      case C.TYPE_SS:
        return new SsMediaSource.Factory(dataSourceFactory)
            .setManifestParser(
                new FilteringManifestParser<>(new SsManifestParser(),
                    Collections.<StreamKey>emptyList()))
            .createMediaSource(uri);
      case C.TYPE_HLS:
        return new HlsMediaSource.Factory(dataSourceFactory)
            .setPlaylistParserFactory(
                new DefaultHlsPlaylistParserFactory(Collections.<StreamKey>emptyList()))
            .createMediaSource(uri);
      case C.TYPE_OTHER:
        return new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
      default: {
        throw new IllegalStateException("Unsupported type: " + type);
      }
    }
  }

  @Override
  public void prepareAsync() throws IllegalStateException {
    if (mediaSource != null) {
      internalPlayer.prepare(mediaSource);
      playerListener.isPreparing = true;
    }
  }


  // ============================@Control@============================
  @Override
  public boolean start() throws IllegalStateException {
    if (internalPlayer.getPlaybackState() == Player.STATE_ENDED) {
      seekTo(0);
      L.dd(TAG, "start()-$>seekTo(0)");//"-$>" internal actual call method
    } else {
      internalPlayer.setPlayWhenReady(true);
      L.dd(TAG, "start()-$>setPlayWhenReady(true)");
    }
    return true;
  }

  @Override
  public boolean pause() throws IllegalStateException {
    internalPlayer.setPlayWhenReady(false);
    L.dd(TAG, "pause()-$>setPlayWhenReady(false)");
    return true;
  }

  @Override
  public boolean seekTo(long ms) throws IllegalStateException {
    internalPlayer.seekTo(ms);
    playerListener.isSeekToing = true;
    L.dd(TAG, "seekTo(" + ms + ")");//omit "-$>." internal same name methods
    return true;
  }

  @Override
  public void stop() throws IllegalStateException {
    internalPlayer.stop();
    L.dd(TAG, "stop()");
  }

  @Override
  public void reset() {
    super.reset();
    internalPlayer.stop();
    L.dd(TAG, "reset()-$>stop()");
  }

  @Override
  public void release() {
    internalPlayer.release();
    internalPlayer.removeListener(playerListener);
    playerListener = null;
    L.dd(TAG, "release()-$>.>removeListener(.)");
  }

  // ============================@Video@============================
  @Override
  public void setVideo(SurfaceView surfaceView) {
    internalPlayer.setVideoSurfaceView(surfaceView);
  }

  @Override
  public void setVideo(TextureView textureView) {
    internalPlayer.setVideoTextureView(textureView);
  }

  @Override
  public void clearVideo() {
    internalPlayer.setVideoSurfaceView(null);
    internalPlayer.setVideoTextureView(null);
  }

  // ============================@Set/Get/Is@============================
  @Override
  public void setAudioStreamType(int streamtype) {
    // do nothing
  }

  @Override
  public void setVolume(float leftVolume, float rightVolume) {
    internalPlayer.setVolume(leftVolume);
  }

  @Override
  public float getVolume() {
    return internalPlayer.getVolume();
  }

  @Override
  public void setDisplay(SurfaceHolder sh) {
    internalPlayer.setVideoSurfaceHolder(sh);
  }

  @Override
  public void setSurface(Surface surface) {
    internalPlayer.setVideoSurface(surface);
  }

  @Override
  public void setScreenOnWhilePlaying(boolean screenOn) {
    //do nothing
  }

  private boolean looping;

  @Override
  public void setLooping(boolean looping) {
    this.looping = looping;
  }

  @Override
  public boolean isLooping() {
    return looping;
  }

  @Override
  public boolean setPlaybackSpeed(float speed) {
    internalPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1f));
    return true;
  }

  @Override
  public float getPlaybackSpeed() {
    return internalPlayer.getPlaybackParameters().speed;
  }

  @Override
  public long getCurrentPosition() {
    return internalPlayer.getCurrentPosition();
  }

  @Override
  public long getDuration() {
    return internalPlayer.getDuration();
  }

  private int mVideoWidth;
  private int mVideoHeight;

  @Override
  public int getVideoWidth() {
    return mVideoWidth;
  }

  @Override
  public int getVideoHeight() {
    return mVideoHeight;
  }

  @Override
  public int getAudioSessionId() {
    return 0;
  }

  @Override
  public int getPlaybackState() {
    return internalPlayer.getPlaybackState();
  }

  @Override
  public boolean isPlayable() {
    if (super.isPlayable()) {
      int state = internalPlayer.getPlaybackState();
      switch (state) {
        case Player.STATE_IDLE:
        case Player.STATE_BUFFERING:
          return false;
        default:
          return true;
      }
    }
    return false;
  }

  @Override
  public boolean isPlaying() {
    return isPlayable() && internalPlayer.getPlaybackState() != Player.STATE_ENDED
        && internalPlayer.getPlayWhenReady();
  }

  // ============================@Listener@============================
  private class PlayerListener implements Player.EventListener, VideoListener {

    private boolean isPreparing = false;
    private boolean isSeekToing = false;
    private boolean isBuffering = false;
    private boolean isCompletion = false;

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      mVideoWidth = width;
      mVideoHeight = height;
      notifyOnVideoSizeChanged(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
    }

    @Override
    public void onRenderedFirstFrame() {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

    }

    @Override
    public void onLoadingChanged(boolean isLoading) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      setPlaybackState(playbackState);

      if (isBuffering && (playbackState == Player.STATE_READY
          || playbackState == Player.STATE_ENDED)) {
        isBuffering = false;
        notifyOnInfo(MEDIA_INFO_BUFFERING_END, internalPlayer.getBufferedPercentage());
      }

      if (isPreparing && playbackState == Player.STATE_READY) {
        isPreparing = false;
        notifyOnPrepared();
      }

      if (isSeekToing && playbackState == Player.STATE_READY) {
        isSeekToing = false;
        notifyOnSeekComplete();
      }

      switch (playbackState) {
        case Player.STATE_IDLE:
          break;
        case Player.STATE_BUFFERING:
          notifyOnInfo(MEDIA_INFO_BUFFERING_START, internalPlayer.getBufferedPercentage());
          isBuffering = true;
          break;
        case Player.STATE_READY:
          isCompletion = false;
          break;
        case Player.STATE_ENDED:
          if (!isCompletion) {
            isCompletion = true;
            if (isLooping()) {
              internalPlayer.seekTo(0);
            } else {
              notifyOnCompletion();
            }
          }
          break;
      }

    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
      notifyOnError(MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_UNKNOWN, error);
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      //Do nothing
    }
  }
}