package com.bcartfall.piworkoutandroid

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import com.bcartfall.piworkoutandroid.databinding.ActivityMainBinding
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.*
import kotlin.math.min
import kotlin.math.roundToLong

private const val DEBUG_TAG = "GESTURES"
private const val WEBSOCKET_TAG = "WEBSOCKET"

class MainActivity : AppCompatActivity(), GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    private var player: ExoPlayer? = null
    private var websocketScope: CoroutineScope? = null
    private var websocket: WebSocketSession? = null
    private var currentVideo: Video? = null
    private var videos: ArrayList<Video>? = null
    private var settings: Map<String, String>? = null
    private var buffering = false
    private var pingSent = 0L
    private var diffSyncWait = 0L
    private var serverLatency = 0 // determine latency between client and server
    private var playbackSpeed = 1.0f
    private val seekDelay = 1500 // est time for player to buffer and play video
    private val diffQueue: Queue<Long> = LinkedList<Long>()
    private var serverHost = ""
    private var volume = 0f
    private lateinit var timerTextView: TimerTextView

    private val viewBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val jsonUnknown = Json { ignoreUnknownKeys = true }

    private lateinit var mDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        timerTextView = viewBinding.timerTextView
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Instantiate the gesture detector with the
        // application context and an implementation of
        // GestureDetector.OnGestureListener
        mDetector = GestureDetectorCompat(this, this)
        // Set the gesture detector as the double tap
        // listener.
        mDetector.setOnDoubleTapListener(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setVideoScale(newConfig)
    }

    private fun setVideoScale(configuration: Configuration) {
        // Set scale depending on orientation of screen
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            viewBinding.videoView.scaleX = 1.0f
            viewBinding.videoView.scaleY = 1.0f
        } else if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            viewBinding.videoView.scaleX = 1.35f // approx 4:3
            viewBinding.videoView.scaleY = 1.35f
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun initializePlayer() {
        Log.i(WEBSOCKET_TAG, "initializePlayer()")
        player = ExoPlayer.Builder(this)
            .build()
            .also { exoPlayer ->
                viewBinding.videoView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        // when player is done buffering we should try to sync with the server
                        //Log.i(WEBSOCKET_TAG, "exoPlayer state change $state")
                        if (state == Player.STATE_READY && buffering) {
                            buffering = false
                        }
                    }
                })
            }

        setVideoScale(resources.configuration)
    }

    private fun initializeWebSocket() {
        Log.i(WEBSOCKET_TAG, "initializeWebSocket()")

        currentVideo = null

        // reconnect to websocket if already connected
        websocketScope?.cancel()
        websocketScope = CoroutineScope(Dispatchers.Main)

        // determine host and port
        val a = serverHost.split(":")
        if (a.count() != 2) {
            return switchToSettingsActivity()
        }
        val hostIp = a[0].trim()
        val hostPort = a[1].trim().toInt()
        if (hostIp == "" || hostPort <= 0) {
            return switchToSettingsActivity()
        }

        websocketScope?.launch {
            //Log.i(WEBSOCKET_TAG, "websocketScope: Current thread is ${Thread.currentThread().name}")
            val client = HttpClient {
                install(WebSockets)
            }

            try {
                client.webSocket(method = HttpMethod.Get, host = hostIp, port = hostPort, path = "/backend") {
                    websocket = this
                    receive()
                }
                Log.i(WEBSOCKET_TAG, "Closing websocket connection.")
            } catch (e: Exception) {
                Log.e(WEBSOCKET_TAG, "Error: " + e.localizedMessage)
            } finally {
                websocket = null
                client.close()
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.receive() {
        try {
            for (message in incoming) {
                message as? Frame.Text ?: continue

                val str = message.readText()
                //Log.i(WEBSOCKET_TAG, "Incoming message: $str")

                // determine type of message to decode
                try {
                    val obj = jsonUnknown.decodeFromString<NamespaceMessage>(str)
                    when (obj.namespace) {
                        "init" -> handleInit(str)
                        "videos" -> handleVideos(str)
                        "player" -> handlePlayer(str)
                        "ping" -> handlePing(str)
                        else -> {
                            Log.i(WEBSOCKET_TAG, "Unhandled namespace ${obj.namespace}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(WEBSOCKET_TAG, "receive() Namespace error: " + e.localizedMessage)
                }
            }
        } catch (e: Exception) {
            Log.e(WEBSOCKET_TAG, "receive() Message error while receiving: " + e.localizedMessage)
        }
        Log.i(WEBSOCKET_TAG, "Done outputMessages()")
    }

    private fun handlePing(str: String) {
        serverLatency = ((System.currentTimeMillis() - pingSent) * 0.5).toInt()
        Log.i(WEBSOCKET_TAG, "handlePing() serverLatency=$serverLatency")
    }

    /**
     * Get video by id
     * @param id
     */
    private fun getVideoById(id: Int) : Video?
    {
        for (video in videos!!) {
            if (video.id == id) {
                return video
            }
        }
        return null
    }

    /**
     * Handle player namespace
     * @param str JSON encoded string
     */
    private fun handlePlayer(str: String) {
        if (serverLatency == 0) {
            // ping result not received yet
            return
        }
        val message = jsonUnknown.decodeFromString<PlayerMessage>(str)
        //Log.i(WEBSOCKET_TAG, "handlePlayer() ${message.toString()}")

        val status = message.player.status
        val action = message.player.action
        val pId = message.player.videoId
        val serverPosition = (message.player.time * 1000).roundToLong() + serverLatency

        if (status == Status.PLAYING.value) {
            if (currentVideo == null || currentVideo?.id != pId) {
                val video = getVideoById(pId)

                if (video != null) {
                    currentVideo = video
                    // play video at position provided by server
                    val videoQuality = settings?.get("videoQuality") // check if server only has 720p quality
                    var format = "1080p" // this android device is limited to 1080p
                    if (video.height < 1080 || videoQuality == "720p") {
                        format = "720p"
                    }

                    val uri = "http://" + serverHost + "/videos/${video.id}-$format-${video.filename}"
                    Log.i(WEBSOCKET_TAG, "handlePlayer() Playing uri $uri serverPosition=$serverPosition")
                    val mediaItem = MediaItem.fromUri(uri)
                    player?.setMediaItem(mediaItem, (message.player.time * 1000).roundToLong())
                    player?.volume = volume
                    player?.playWhenReady = true
                    player?.seekTo(serverPosition + seekDelay)
                    buffering = true

                    player?.prepare()
                    diffSyncWait = System.currentTimeMillis() + seekDelay // wait seekDelay before attempting to sync with server
                }
            } else {
                // handle actions
                when (action) {
                    "progress" -> {
                        if (player != null) {
                            val clientPosition = player!!.currentPosition
                            val cDiff = serverPosition - clientPosition // if > 0 server is ahead

                            // average queue keeps playback speed adjustments more balanced
                            diffQueue.add(cDiff)
                            if (diffQueue.count() > 10) {
                                diffQueue.remove()
                            }
                            val aDiff = diffQueue.average()

                            if (!buffering && player!!.isPlaying) {
                                player!!.play()
                            }

                            // try to sync with server within tolerance
                            val minDiff = -66
                            val maxDiff = 0
                            if (!buffering && (aDiff <= minDiff || aDiff >= maxDiff) && System.currentTimeMillis() >= diffSyncWait) {
                                if (cDiff < 0) {
                                    // slow player so server can catch up (1.0f to 0.75f)
                                    playbackSpeed = 1.0f - (min(((-cDiff) + minDiff).toInt(), (1000 + minDiff)).toFloat() / (1000 + minDiff).toFloat() * 0.25f)
                                    Log.i(WEBSOCKET_TAG, "handlePlayer() sync!!!!!, client is ahead, setting playbackSpeed=$playbackSpeed")
                                } else {
                                    // increase player to catch up to server (1.0f to 1.5f)
                                    playbackSpeed = 1.0f + ((min(cDiff.toInt(), 1000).toFloat() / 1000) * 0.5f)
                                    Log.i(WEBSOCKET_TAG, "handlePlayer() sync!!!!!, server is ahead, setting playbackSpeed=$playbackSpeed")
                                }
                                player!!.setPlaybackSpeed(playbackSpeed)
                            } else {
                                if (playbackSpeed != 1.0f) {
                                    playbackSpeed = 1.0f
                                    player!!.setPlaybackSpeed(playbackSpeed)
                                }
                            }
                            Log.i(WEBSOCKET_TAG, "handlePlayer() clientPosition=$clientPosition, serverPosition=$serverPosition, cDiff=$cDiff, aDiff=$aDiff buffering=$buffering")
                        }

                    }
                    "seek" -> {
                        Log.i(WEBSOCKET_TAG, "handlePlayer() seeking to $serverPosition, ${message.player.time}")
                        buffering = true
                        player?.seekTo(serverPosition)
                    }
                    "play" -> {
                        Log.i(WEBSOCKET_TAG, "handlePlayer() resuming/playing")
                        player?.play()
                    }
                }
            }
        } else if (status == Status.STOPPED.value || status == Status.PAUSED.value) {
            Log.i(WEBSOCKET_TAG, "handlePlayer() pausing")
            player?.pause()
        }
    }

    private fun handleVideos(str: String) {
        val message = jsonUnknown.decodeFromString<VideosMessage>(str)
        videos = message.videos
    }

    private fun handleInit(str: String) {
        val initMessage = jsonUnknown.decodeFromString<InitMessage>(str)
        videos = initMessage.data.videos
        settings = initMessage.data.settings

        // send ping to server
        val message = PingMessage(namespace = "ping", uuid = UUID.randomUUID().toString())
        runBlocking {
            pingSent = System.currentTimeMillis()
            websocket?.send(Json.encodeToString(message))
        }
    }

    private fun switchToSettingsActivity()
    {
        Log.i(WEBSOCKET_TAG, "Switching to settings activity.")
        val switchActivityIntent = Intent(this, SettingsActivity::class.java)
        startActivity(switchActivityIntent)
    }

    public override fun onStart() {
        super.onStart()
        Log.i(WEBSOCKET_TAG, "onStart()")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        serverHost = sharedPreferences.getString("host", "").toString()
        volume = if (sharedPreferences.getBoolean("muted", true)) {
            0f
        } else {
            1f
        }

        if (sharedPreferences.getBoolean("timer", true)) {
            timerTextView.visibility = View.VISIBLE
        } else {
            timerTextView.visibility = View.GONE
        }

        if (serverHost == "") {
            // first launch or host is empty
            // switch to settings page
            Log.i(WEBSOCKET_TAG, "Server host not set.")
            switchToSettingsActivity()
        }

        initializePlayer()
        initializeWebSocket()
    }

    public override fun onResume() {
        super.onResume()
        Log.i(WEBSOCKET_TAG, "onResume()")
        hideSystemUi()

        if (serverHost == "") {
            Log.i(WEBSOCKET_TAG, "Server host not set.")
            return
        }
        if (player == null) {
            initializePlayer()
        }
        if (websocketScope == null) {
            initializeWebSocket()
        }
    }

    @SuppressLint("InlinedApi")
    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, viewBinding.videoView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        timerTextView.restart()
        return if (mDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun onDown(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onDown: $event")
        return true
    }

    override fun onFling(
        event1: MotionEvent,
        event2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        Log.d(DEBUG_TAG, "onFling: $event1 $event2")
        return true
    }

    override fun onLongPress(event: MotionEvent) {
        Log.d(DEBUG_TAG, "onLongPress: $event")
    }

    override fun onScroll(
        event1: MotionEvent,
        event2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        Log.d(DEBUG_TAG, "onScroll: $event1 $event2")
        return true
    }

    override fun onShowPress(event: MotionEvent) {
        Log.d(DEBUG_TAG, "onShowPress: $event")
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onSingleTapUp: $event")
        return true
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onDoubleTap: $event")
        switchToSettingsActivity()
        return true
    }

    override fun onDoubleTapEvent(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onDoubleTapEvent: $event")
        return true
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onSingleTapConfirmed: $event")
        return true
    }

    public override fun onPause() {
        super.onPause()
        releasePlayer()
        releaseWebSocket()
    }

    public override fun onStop() {
        super.onStop()
        releasePlayer()
        releaseWebSocket()
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            exoPlayer.release()
        }
        player = null
    }

    private fun releaseWebSocket() {
        if (websocketScope != null) {
            websocketScope?.cancel()
            websocketScope = null
        }
    }
}
