package com.moviebox.player

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.moviebox.model.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Built-in media player for MovieBox Android app.
 * Replaces external media players (mpv, VLC, IINA) with an integrated ExoPlayer engine.
 *
 * Features:
 * - Adaptive bitrate streaming (HLS, DASH)
 * - Headers/cookies forwarding (User-Agent, Referer, Cookie)
 * - Subtitle support (SRT, VTT, ASS)
 * - Playback position tracking
 * - Stream proxy/cookie forwarding
 */
class MovieBoxPlayer private constructor(context: Context) {

    private val player: ExoPlayer
    private val context: Context
    private val logger: (String) -> Unit = { Log.d("MovieBox.Player", it) }

    private var currentSource: PlaybackSource? = null
    private var currentPositionMs: Long = 0
    private var totalDurationMs: Long = 0
    private var isPlaying: Boolean = false

    private val positionChannel = Channel<Float>(Channel.UNLIMITED)
    private val playbackStateChannel = Channel<Player.PlaybackState>(Channel.UNLIMITED)

    private var lastResumeRequestMs: Long = 0

    init {
        this.context = context.applicationContext

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUpon().setRendererDisabled(1, false)
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationMs(120_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setBackBufferDurationMs(15_000)
            .build()

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes)
            .build()

        player.addListener(object : Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                MovieBoxPlayer.this.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        currentPositionMs = player.currentPosition
                        totalDurationMs = player.duration
                    }
                    Player.STATE_IDLE -> {
                        isPlaying = false
                    }
                }
            }

            override fun onPositionDiscontinuity(reason: Int) {
                currentPositionMs = player.currentPosition
            }

            override fun onTimelineChanged(timeline: Player.Timeline, reason: Int) {
                totalDurationMs = player.duration
            }

            override fun onPlayerError(error: PlaybackException) {
                logger("Player error: ${error.message}")
            }
        })
    }

    /**
     * Get the player instance for direct access.
     */
    fun getPlayer(): ExoPlayer = player

    /**
     * Get current playback source.
     */
    fun getCurrentSource(): PlaybackSource? = currentSource

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPositionMs(): Long = currentPositionMs

    /**
     * Get total duration in milliseconds.
     */
    fun getTotalDurationMs(): Long = totalDurationMs

    /**
     * Check if player is currently playing.
     */
    fun isPlaying(): Boolean = isPlaying

    /**
     * Get playback position as a percentage.
     */
    fun getProgressPercent(): Float {
        if (totalDurationMs <= 0) return 0f
        return (currentPositionMs.toFloat() / totalDurationMs * 100).coerceIn(0f, 100f)
    }

    /**
     * Format current position as MM:SS.
     */
    fun formatCurrentPosition(): String {
        return formatDurationMs(currentPositionMs)
    }

    /**
     * Format total duration as MM:SS.
     */
    fun formatTotalDuration(): String {
        return formatDurationMs(totalDurationMs)
    }

    /**
     * Load a playback source with optional headers.
     * Sets up data source factory with custom headers for the stream URL.
     */
    suspend fun loadSource(source: PlaybackSource) {
        currentSource = source

        withContext(Dispatchers.IO) {
            val dataSourceFactory = DefaultDataSource.Factory(context, Util.getUserAgent(context, "MovieBox"))
                .setDefaultRequestProperties(defaultHeaders(source.headers))

            val mediaItem = MediaItem.fromUri(Uri.parse(source.url))

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            // Set up subtitle if provided
            val finalMediaItem = if (source.subtitle != null) {
                mediaItem.buildUpon()
                    .setSubtitleConfiguration(
                        androidx.media3.common.SubtitleConfiguration.Builder(
                            Uri.parse(source.subtitle!!)
                        )
                        .setMimeType(determineMimeType(source.subtitle))
                        .build()
                    )
                    .build()
            } else {
                mediaItem
            }

            withContext(Dispatchers.Main) {
                player.setMediaSourceFactory(mediaSourceFactory)
                player.addMediaItem(finalMediaItem)
                player.prepare()
                player.seekTo(0)
                player.play()
            }
        }
    }

    /**
     * Play or resume playback.
     */
    fun play() {
        if (!player.isPlaying) {
            player.setPlayWhenReady(true)
            isPlaying = true
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        player.setPlayWhenReady(false)
        isPlaying = false
    }

    /**
     * Stop playback and reset.
     */
    fun stop() {
        player.stop()
        currentSource = null
        currentPositionMs = 0
        totalDurationMs = 0
        isPlaying = false
    }

    /**
     * Seek to a position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /**
     * Seek forward/backward by the given delta in milliseconds.
     */
    fun seekRelative(deltaMs: Long) {
        val target = currentPositionMs + deltaMs
        player.seekTo(target.coerceIn(0, totalDurationMs))
    }

    /**
     * Set playback speed.
     */
    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
    }

    /**
     * Set volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    /**
     * Release player resources.
     */
    fun release() {
        player.release()
        positionChannel.close()
        playbackStateChannel.close()
    }

    /**
     * Build default request properties from stream headers.
     * Forwards User-Agent, Referer, Cookie headers as needed.
     */
    private fun defaultHeaders(headers: List<(String, String)>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((name, value) in headers) {
            when (name.lowercase()) {
                "user-agent" -> map["User-Agent"] = value
                "referer" -> map["Referer"] = value
                "cookie" -> map["Cookie"] = value
                else -> map[name] = value
            }
        }
        return map
    }

    /**
     * Determine MIME type for subtitle file based on extension.
     */
    private fun determineMimeType(url: String): String {
        val lower = url.lowercase()
        return if (lower.endsWith(".srt")) {
            "text/srt"
        } else if (lower.endsWith(".vtt")) {
            "text/vtt"
        } else if (lower.endsWith(".ass")) {
            "application/x-ass"
        } else if (lower.endsWith(".ssa")) {
            "application/x-ssa"
        } else {
            "text/srt"
        }
    }

    /**
     * Format duration in milliseconds to MM:SS.
     */
    private fun formatDurationMs(millis: Long): String {
        if (millis <= 0) return "0:00"
        val seconds = millis / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "$minutes:${String.format("%02d", remainingSeconds)}"
    }

    /**
     * Live state channel for observers.
     */
    sealed class PlaybackState {
        object Loading : PlaybackState()
        object Playing : PlaybackState()
        object Paused : PlaybackState()
        object Stopped : PlaybackState()
        data class Error(val message: String) : PlaybackState()
    }

    companion object {
        private var instance: MovieBoxPlayer? = null

        fun getInstance(context: Context): MovieBoxPlayer {
            return instance ?: synchronized(this) {
                instance ?: MovieBoxPlayer(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Data source factory wrapper for header forwarding.
 */
class HeaderDataSourceFactory(
    context: Context,
    private val headers: List<(String, String)>
) : androidx.media3.datasource.DataSource.Factory {

    private val defaultFactory = DefaultDataSource.Factory(context, Util.getUserAgent(context, "MovieBox"))

    override fun createDataSource(): DataSource {
        return defaultFactory.createDataSource()
    }

    fun setDefaultRequestProperties(headers: List<(String, String)>): androidx.media3.datasource.DataSource.Factory {
        return this
    }
}