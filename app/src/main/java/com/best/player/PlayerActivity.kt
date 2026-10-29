package com.best.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        private const val CONTROLS_TIMEOUT_MS = 3500L
        private const val SEEK_WATCHDOG_MS = 400L
        private const val DOUBLE_TAP_SEEK_MS = 10_000L
        /** Horizontal swipe across the full screen width moves this much time (capped by duration). */
        private const val SWIPE_FULL_WIDTH_MS = 120_000L
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var touchLayer: View
    private lateinit var gestureInfo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSeekMode: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnCapture: ImageButton

    private var videoUri: Uri? = null
    private var videoTitle: String = "video"

    // Restored across onStop/onStart.
    private var resumePositionMs = 0L
    private var resumePlayWhenReady = true

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true

    // --- Realtime scrubbing state ---
    // While the user drags, we keep at most one seek in flight. Each new position
    // overwrites pendingSeekMs; as soon as the previous frame is on screen we jump
    // to the latest position. This keeps the picture following the finger in real
    // time without queueing up hundreds of stale seeks.
    private var isScrubbing = false
    private var wasPlayingBeforeScrub = false
    private var scrubStartMs = 0L
    private var seekInFlight = false
    private var pendingSeekMs = C.TIME_UNSET
    private var exactScrub = true

    // --- Frame stepping state ---
    private var frameDurationUs = 1_000_000L / 30
    /** Presentation time of the frame we stepped to, or TIME_UNSET when not stepping. */
    private var stepAnchorUs = C.TIME_UNSET

    private var speedIndex = SPEEDS.indexOf(1f)
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private lateinit var audioManager: AudioManager
    private var gestureMode = GestureMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var gestureStartPositionMs = 0L
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f
    private var touchSlop = 0

    private enum class GestureMode { NONE, SEEK, VOLUME, BRIGHTNESS }

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideInfoRunnable = Runnable { gestureInfo.visibility = View.GONE }
    private val seekWatchdog = Runnable { onSeekSettled() }
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 100)
        }
    }

    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) captureFrame()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        playerView = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        touchLayer = findViewById(R.id.touch_layer)
        gestureInfo = findViewById(R.id.gesture_info)
        seekBar = findViewById(R.id.seek_bar)
        tvPosition = findViewById(R.id.position)
        tvDuration = findViewById(R.id.duration)
        tvTitle = findViewById(R.id.title)
        tvSeekMode = findViewById(R.id.btn_seek_mode)
        tvSpeed = findViewById(R.id.btn_speed)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnCapture = findViewById(R.id.btn_capture)

        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val cut = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        playerView.keepScreenOn = true
        handleIntent(intent)
        setupControls()
        setupGestures()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resumePositionMs = 0L
        resumePlayWhenReady = true
        handleIntent(intent)
        player?.let { loadMedia(it) }
    }

    private fun handleIntent(intent: Intent) {
        videoUri = intent.data
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: queryDisplayName(intent.data) ?: "video"
        tvTitle.text = videoTitle
    }

    private fun queryDisplayName(uri: Uri?): String? {
        uri ?: return null
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        initializePlayer()
        handler.post(progressUpdater)
        scheduleHideControls()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }

    private fun initializePlayer() {
        if (player != null) return
        // Prefer hardware decoders, but fall back to another decoder instead of
        // failing when the first one can't handle the stream (e.g. 10-bit / 4K).
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setSeekBackIncrementMs(DOUBLE_TAP_SEEK_MS)
            .setSeekForwardIncrementMs(DOUBLE_TAP_SEEK_MS)
            .build()
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.setPlaybackSpeed(SPEEDS[speedIndex])
        exo.addListener(playerListener)
        playerView.player = exo
        playerView.resizeMode = resizeMode
        player = exo
        loadMedia(exo)
    }

    private fun loadMedia(exo: ExoPlayer) {
        val uri = videoUri ?: return
        exo.setMediaItem(MediaItem.fromUri(uri), resumePositionMs)
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()
        stepAnchorUs = C.TIME_UNSET
    }

    private fun releasePlayer() {
        val exo = player ?: return
        resumePositionMs = exo.currentPosition
        resumePlayWhenReady = exo.playWhenReady
        exo.removeListener(playerListener)
        exo.release()
        playerView.player = null
        player = null
        seekInFlight = false
        pendingSeekMs = C.TIME_UNSET
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                onSeekSettled()
                updateDuration()
                updateFrameRate()
            }
            if (playbackState == Player.STATE_ENDED) setControlsVisible(true)
        }

        override fun onRenderedFirstFrame() {
            // Fires after every seek once the new frame is actually on screen.
            onSeekSettled()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) stepAnchorUs = C.TIME_UNSET
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.playback_error, error.errorCodeName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun updateDuration() {
        val duration = player?.duration ?: return
        if (duration == C.TIME_UNSET || duration <= 0) return
        seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        tvDuration.text = TimeFormat.format(duration)
    }

    private fun updateFrameRate() {
        val fps = player?.videoFormat?.frameRate ?: return
        if (fps > 1f) frameDurationUs = (1_000_000.0 / fps).roundToLong()
    }

    private fun updateProgress() {
        val exo = player ?: return
        if (isScrubbing || gestureMode == GestureMode.SEEK) return
        val pos = exo.currentPosition
        seekBar.progress = pos.toInt()
        seekBar.secondaryProgress = exo.bufferedPosition.toInt()
        tvPosition.text = TimeFormat.format(pos)
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleHideControls()
        }

        findViewById<View>(R.id.btn_prev_frame).setOnClickListener { stepFrame(-1) }
        findViewById<View>(R.id.btn_next_frame).setOnClickListener { stepFrame(+1) }
        btnCapture.setOnClickListener { captureFrame() }

        tvSeekMode.setOnClickListener {
            exactScrub = !exactScrub
            tvSeekMode.setText(if (exactScrub) R.string.seek_exact else R.string.seek_fast)
            scheduleHideControls()
        }

        tvSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            val speed = SPEEDS[speedIndex]
            player?.setPlaybackSpeed(speed)
            tvSpeed.text = "${speed}x"
            scheduleHideControls()
        }

        findViewById<View>(R.id.btn_aspect).setOnClickListener {
            val (mode, label) = when (resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.resize_zoom
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.resize_fill
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.resize_fit
            }
            resizeMode = mode
            playerView.resizeMode = mode
            showInfo(getString(label))
            scheduleHideControls()
        }

        findViewById<View>(R.id.btn_rotate).setOnClickListener {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            scheduleHideControls()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                beginScrub()
            }

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                scrubTo(progress.toLong())
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                endScrub(bar.progress.toLong())
            }
        })
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        handler.removeCallbacks(hideControlsRunnable)
        controls.animate().cancel()
        if (visible) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            scheduleHideControls()
        } else {
            controls.animate().alpha(0f).setDuration(200)
                .withEndAction { if (!controlsVisible) controls.visibility = View.GONE }
                .start()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying != false && !isScrubbing) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun showInfo(text: String, autoHide: Boolean = true) {
        handler.removeCallbacks(hideInfoRunnable)
        gestureInfo.text = text
        gestureInfo.visibility = View.VISIBLE
        if (autoHide) handler.postDelayed(hideInfoRunnable, 800)
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- realtime scrub

    private fun beginScrub() {
        val exo = player ?: return
        isScrubbing = true
        wasPlayingBeforeScrub = exo.playWhenReady
        scrubStartMs = exo.currentPosition
        exo.pause()
        // EXACT shows the exact frame under the finger; CLOSEST_SYNC jumps to the
        // nearest keyframe, which is cheaper on long-GOP / 4K files.
        exo.setSeekParameters(if (exactScrub) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        stepAnchorUs = C.TIME_UNSET
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun scrubTo(positionMs: Long) {
        val exo = player ?: return
        val target = positionMs.coerceIn(0, maxOf(exo.duration, 0))
        tvPosition.text = TimeFormat.format(target)
        val delta = target - scrubStartMs
        val sign = if (delta >= 0) "+" else "-"
        showInfo(
            "${TimeFormat.format(target, withMillis = true)}\n[$sign${TimeFormat.format(abs(delta))}]",
            autoHide = false,
        )
        pendingSeekMs = target
        if (!seekInFlight) dispatchPendingSeek()
    }

    private fun dispatchPendingSeek() {
        val exo = player ?: return
        val target = pendingSeekMs
        if (target == C.TIME_UNSET) return
        pendingSeekMs = C.TIME_UNSET
        seekInFlight = true
        exo.seekTo(target)
        handler.removeCallbacks(seekWatchdog)
        handler.postDelayed(seekWatchdog, SEEK_WATCHDOG_MS)
    }

    private fun onSeekSettled() {
        handler.removeCallbacks(seekWatchdog)
        if (!seekInFlight) return
        seekInFlight = false
        dispatchPendingSeek()
    }

    private fun endScrub(finalPositionMs: Long) {
        val exo = player ?: return
        isScrubbing = false
        pendingSeekMs = C.TIME_UNSET
        seekInFlight = false
        handler.removeCallbacks(seekWatchdog)
        // Always land on the exact frame when the finger is lifted.
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.seekTo(finalPositionMs.coerceIn(0, maxOf(exo.duration, 0)))
        if (wasPlayingBeforeScrub) exo.play()
        handler.postDelayed(hideInfoRunnable, 600)
        scheduleHideControls()
    }

    // ---------------------------------------------------------------- frame step

    /**
     * Moves exactly one frame. An EXACT seek shows the first frame whose timestamp
     * is >= the target, so we aim half a frame before the wanted frame to be
     * robust against millisecond rounding.
     */
    private fun stepFrame(direction: Int) {
        val exo = player ?: return
        exo.pause()
        exo.setSeekParameters(SeekParameters.EXACT)
        if (stepAnchorUs == C.TIME_UNSET) {
            val posUs = exo.currentPosition * 1000
            stepAnchorUs = (posUs / frameDurationUs) * frameDurationUs
        }
        val maxUs = maxOf(exo.duration, 0) * 1000
        stepAnchorUs = (stepAnchorUs + direction * frameDurationUs).coerceIn(0, maxUs)
        val targetMs = ((stepAnchorUs - frameDurationUs / 2).coerceAtLeast(0)) / 1000
        exo.seekTo(targetMs)
        val frameNo = stepAnchorUs / frameDurationUs
        showInfo("${TimeFormat.format(stepAnchorUs / 1000, withMillis = true)}\nเฟรม #$frameNo")
        setControlsVisible(true)
    }

    // ---------------------------------------------------------------- capture

    private fun captureFrame() {
        val exo = player ?: return
        val uri = videoUri ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val timeUs = if (stepAnchorUs != C.TIME_UNSET) {
            stepAnchorUs
        } else {
            // While paused/playing the shown frame is the last one at or before the position.
            val posUs = exo.currentPosition * 1000
            (posUs / frameDurationUs) * frameDurationUs
        }
        val baseName = "${videoTitle.substringBeforeLast('.')}_${TimeFormat.forFileName(timeUs / 1000)}"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        btnCapture.isEnabled = false
        showInfo(getString(R.string.capturing), autoHide = false)
        lifecycleScope.launch {
            val bitmap = FrameCapture.decodeFrame(this@PlayerActivity, uri, timeUs)
                ?: (playerView.videoSurfaceView as? SurfaceView)?.let { FrameCapture.copySurface(it) }
            val saved = bitmap?.let { FrameCapture.save(this@PlayerActivity, it, baseName) }
            btnCapture.isEnabled = true
            if (bitmap != null && saved != null) {
                showInfo("📷 ${bitmap.width}×${bitmap.height}")
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.capture_saved, saved),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                showInfo(getString(R.string.capture_failed))
            }
        }
    }

    // ---------------------------------------------------------------- gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val exo = player ?: return false
                val third = touchLayer.width / 3f
                when {
                    e.x < third -> {
                        exo.seekBack()
                        showInfo("« ${DOUBLE_TAP_SEEK_MS / 1000}s")
                    }
                    e.x > third * 2 -> {
                        exo.seekForward()
                        showInfo("${DOUBLE_TAP_SEEK_MS / 1000}s »")
                    }
                    else -> togglePlayPause()
                }
                stepAnchorUs = C.TIME_UNSET
                return true
            }
        })

        touchLayer.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    gestureMode = GestureMode.NONE
                }
                MotionEvent.ACTION_MOVE -> handleDrag(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
            }
            true
        }
    }

    private fun handleDrag(event: MotionEvent) {
        val exo = player ?: return
        val dx = event.x - downX
        val dy = event.y - downY
        if (gestureMode == GestureMode.NONE) {
            if (abs(dx) < touchSlop && abs(dy) < touchSlop) return
            gestureMode = when {
                abs(dx) > abs(dy) -> GestureMode.SEEK
                downX < touchLayer.width / 2f -> GestureMode.BRIGHTNESS
                else -> GestureMode.VOLUME
            }
            when (gestureMode) {
                GestureMode.SEEK -> {
                    gestureStartPositionMs = exo.currentPosition
                    beginScrub()
                }
                GestureMode.VOLUME ->
                    gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                GestureMode.BRIGHTNESS -> {
                    val current = window.attributes.screenBrightness
                    gestureStartBrightness = if (current < 0) 0.5f else current
                }
                GestureMode.NONE -> Unit
            }
        }

        val height = touchLayer.height.coerceAtLeast(1)
        when (gestureMode) {
            GestureMode.SEEK -> {
                val range = minOf(SWIPE_FULL_WIDTH_MS, maxOf(exo.duration, 0))
                val deltaMs = (dx / touchLayer.width.coerceAtLeast(1) * range).toLong()
                val target = gestureStartPositionMs + deltaMs
                seekBar.progress = target.toInt()
                scrubTo(target)
            }
            GestureMode.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val vol = (gestureStartVolume - dy / height * max * 1.5f).toInt().coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                showInfo("🔊 ${vol * 100 / max.coerceAtLeast(1)}%")
            }
            GestureMode.BRIGHTNESS -> {
                val value = (gestureStartBrightness - dy / height * 1.5f).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = value }
                showInfo("☀ ${(value * 100).toInt()}%")
            }
            GestureMode.NONE -> Unit
        }
    }

    private fun finishDrag() {
        if (gestureMode == GestureMode.SEEK) endScrub(seekBar.progress.toLong())
        gestureMode = GestureMode.NONE
    }
}
