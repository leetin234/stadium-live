package kr.stadiumlive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes as M3Attributes

enum class Focus(val label: String) {
    IDLE("대기"),
    ACTIVE("재생 중"),
    DUCKED("내비 안내 · 음량 감쇠"),
    PAUSED_BY_OTHER("통화 · 일시정지"),
    LOST("다른 앱이 재생 중"),
}

/**
 * 중계 음원(ExoPlayer)과 효과음(SoundPool)을 한 시계로 묶는다.
 * 기준 시계는 항상 중계의 재생 위치이며, 25ms마다 CueEngine에 물어 효과를 낸다.
 * 오디오 포커스는 직접 처리해 감쇠·일시정지를 효과음까지 똑같이 적용한다.
 */
class AudioDirector(private val ctx: Context, game: Game) {
    private val engine = CueEngine(game)
    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    var mode = Mode.DRIVING
    var prefs = Prefs()
    var onTick: (positionMs: Long, playing: Boolean) -> Unit = { _, _ -> }
    var onFocus: (Focus) -> Unit = {}
    var onEnded: () -> Unit = {}
    var focus = Focus.IDLE
        private set

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
        setAudioAttributes(
            M3Attributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            false,
        )
        setMediaItem(MediaItem.fromUri("asset:///game1/broadcast.m4a"))
        prepare()
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    abandon()
                    onEnded()
                }
            }
        })
    }

    private val pool = SoundPool.Builder().setMaxStreams(16).setAudioAttributes(mediaAttrs).build()
    private val ids = SOUNDS.associateWith { name -> ctx.assets.openFd("fx/$name.wav").use { pool.load(it, 1) } }
    private val loops = mutableMapOf<String, Int>()
    private val oneShots = ArrayDeque<Int>()
    private var whooshStream = 0
    private var whooshGain = 0f
    private var prev = 0L
    private var wasPlaying = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAttrs)
        // true로 두면 Pleos(CarAudioFocus)가 내비 안내를 배타 요청으로 보고 중계를 멈춘다.
        // false로 두어 차량이 동시 재생·감쇠하게 하고, 내비 안내 감지는 재생 콜백으로 한다.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChange, main)
        .build()

    private val duck get() = if (focus == Focus.DUCKED) 0.2f else 1f

    private val tick = object : Runnable {
        override fun run() {
            val now = player.currentPosition
            val playing = player.isPlaying
            if (wasPlaying && !playing) stopOneShots()
            if (playing) {
                val catchUp = SystemClock.elapsedRealtime() - lastUserSeek > 1_500
                if (now - prev > CueEngine.MAX_STEP_MS) Log.w(TAG, "position jump $prev→$now catchUp=$catchUp")
                // 효과음(SoundPool)은 중계 음원보다 출력이 늦게 나오므로 그만큼 앞당겨 낸다.
                engine.shots(prev + FX_LEAD_MS, now + FX_LEAD_MS, mode, prefs, catchUp).forEach { fire(it, now) }
            }
            prev = now
            wasPlaying = playing
            val m = if (playing) engine.mix(now, mode, prefs) else Mix.SILENT
            loop("breath", m.breathGain)
            loop("chant_W", if (m.chant == "chant_W") m.chantGain else 0f)
            loop("chant_F", if (m.chant == "chant_F") m.chantGain else 0f)
            engine.flightAt(now)?.takeIf { playing && whooshStream != 0 }?.let { p ->
                // 타구가 왼쪽(타석)에서 오른쪽(외야)으로 날아가는 느낌.
                val g = whooshGain * duck
                pool.setVolume(whooshStream, g * (1f - 0.8f * p), g * (0.2f + 0.8f * p))
            }
            onTick(now, playing)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 내비 안내 음성이 재생되는 동안 효과음과 중계를 함께 낮춘다(다른 앱의 재생도 용도는 보인다). */
    private val playbackWatch = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
            val nav = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
            Log.i(TAG, "playback configs=${configs.size} nav=$nav focus=$focus")
            if (nav && focus == Focus.ACTIVE) setFocus(Focus.DUCKED)
            else if (!nav && focus == Focus.DUCKED) setFocus(Focus.ACTIVE)
        }
    }

    init {
        main.post(tick)
        am.registerAudioPlaybackCallback(playbackWatch, main)
    }

    private fun fire(s: Shot, now: Long) {
        val id = ids[s.sound] ?: return
        val g = (s.gain * duck).coerceIn(0f, 1f)
        val l = g * (if (s.pan > 0) 1 - s.pan else 1f)
        val r = g * (if (s.pan < 0) 1 + s.pan else 1f)
        val stream = pool.play(id, l, r, 1, 0, 1f)
        if (s.sound == "whoosh") { whooshStream = stream; whooshGain = s.gain }
        oneShots.addLast(stream)
        while (oneShots.size > 24) oneShots.removeFirst()
        if (s.sound != "heartbeat") Log.i(TAG, "fire ${s.sound} pos=$now gain=${"%.2f".format(g)}")
    }

    private fun loop(name: String, gain: Float) {
        val g = (gain * duck).coerceIn(0f, 1f)
        val stream = loops[name]
        when {
            g <= 0.005f && stream != null -> { pool.stop(stream); loops.remove(name) }
            g > 0.005f && stream == null -> ids[name]?.let { loops[name] = pool.play(it, g, g, 0, -1, 1f) }
            stream != null -> pool.setVolume(stream, g, g)
        }
    }

    private fun stopOneShots() {
        oneShots.forEach { pool.stop(it) }
        oneShots.clear()
        whooshStream = 0
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) seekTo(0)
        val r = am.requestAudioFocus(focusRequest)
        Log.i(TAG, "requestAudioFocus=$r")
        if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setFocus(Focus.ACTIVE)
            player.play()
        }
    }

    fun pause() {
        player.pause()
        abandon()
    }

    private var lastUserSeek = 0L

    fun seekTo(ms: Long) {
        lastUserSeek = SystemClock.elapsedRealtime()
        stopOneShots()
        player.seekTo(ms.coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        prev = ms
    }

    private fun abandon() {
        am.abandonAudioFocusRequest(focusRequest)
        setFocus(Focus.IDLE)
    }

    private fun setFocus(f: Focus) {
        focus = f
        player.volume = if (f == Focus.DUCKED) 0.3f else 1f
        onFocus(f)
    }

    private fun onFocusChange(change: Int) {
        Log.i(TAG, "focus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = focus == Focus.PAUSED_BY_OTHER
                setFocus(Focus.ACTIVE)
                if (resume) player.play()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setFocus(Focus.DUCKED)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { player.pause(); setFocus(Focus.PAUSED_BY_OTHER) }
            AudioManager.AUDIOFOCUS_LOSS -> { player.pause(); am.abandonAudioFocusRequest(focusRequest); setFocus(Focus.LOST) }
        }
    }

    // ---- 시연용: 다른 앱처럼 포커스를 요청해 내비 안내·전화 수신을 재현한다 ----
    var onSimulation: (String?) -> Unit = {}

    fun simulateNavigation() = simulate(
        "내비 안내 재생",
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        "sim_nav",
    )

    fun simulateCall() = simulate(
        "전화 수신 중",
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.CONTENT_TYPE_SONIFICATION,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        "sim_ring",
    )

    private fun simulate(label: String, usage: Int, content: Int, gain: Int, sound: String) {
        val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(content).build()
        val req = AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({}, main).build()
        val r = am.requestAudioFocus(req)
        Log.i(TAG, "simulate $sound focus=$r")
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { onSimulation("$label: 포커스 거부됨"); return }
        onSimulation(label)
        MediaPlayer().apply {
            setAudioAttributes(attrs)
            ctx.assets.openFd("fx/$sound.wav").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setOnCompletionListener {
                release()
                am.abandonAudioFocusRequest(req)
                onSimulation(null)
            }
            prepare()
            start()
        }
    }

    fun release() {
        main.removeCallbacks(tick)
        am.unregisterAudioPlaybackCallback(playbackWatch)
        pause()
        player.release()
        pool.release()
    }

    companion object {
        const val TAG = "StadiumAudio"
        const val TICK_MS = 25L
        /** Pleos 에뮬레이터에서 잰 SoundPool 출력 지연(타격음 vs 중계 음원 약 120ms). */
        const val FX_LEAD_MS = 110L
        val SOUNDS = listOf("heartbeat", "breath", "crack", "foul", "glove", "whoosh", "roar", "groan", "chant_W", "chant_F")
    }
}
