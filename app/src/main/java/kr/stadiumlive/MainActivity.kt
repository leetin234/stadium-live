package kr.stadiumlive

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { SETUP, PLAYER, FEEDBACK }

/** 화면이 읽는 상태 묶음. Activity가 소유하고 Compose가 관찰한다. */
class UiState {
    var mode by mutableStateOf(Mode.DRIVING)
    var modeKnown by mutableStateOf(false)
    var focus by mutableStateOf(Focus.IDLE)
    var simulation by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var prefs by mutableStateOf(Prefs())
    var screen by mutableStateOf(Screen.SETUP)
    var feedbackSaved by mutableStateOf(false)
    var cabin by mutableStateOf(CabinState())
}

class MainActivity : ComponentActivity() {
    private lateinit var director: AudioDirector
    private lateinit var driving: DrivingState
    private lateinit var cabin: CabinFx
    private val ui = UiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = Game.parse(assets.open("game1/game.json").bufferedReader().readText())
        val store = getSharedPreferences("stadium", Context.MODE_PRIVATE)
        ui.prefs = Prefs(
            effectsOn = store.getBoolean("effectsOn", true),
            intensity = store.getFloat("intensity", 0.7f),
            myTeam = store.getString("myTeam", "W") ?: "W",
            immersiveOnPark = store.getBoolean("immersiveOnPark", true),
            cabinOn = store.getBoolean("cabinOn", true),
        )
        cabin = CabinFx(this) { ui.cabin = it }
        val engine = CueEngine(game)
        val colorOf = { id: String -> (game.team(id).color and 0xFFFFFF).toInt() }
        director = AudioDirector(this, game).apply {
            prefs = ui.prefs
            onTick = { p, playing ->
                ui.position = p; ui.playing = playing
                // 실내 연출: 재생 중·정차 몰입 모드에서만, 그 외에는 원래 상태로 복원.
                cabin.apply(if (playing && ui.prefs.immersiveOnPark) engine.cabin(p, ui.mode, ui.prefs, colorOf(ui.prefs.myTeam), colorOf) else null)
            }
            onFocus = { ui.focus = it }
            onSimulation = { ui.simulation = it }
            onEnded = { ui.screen = Screen.FEEDBACK; ui.feedbackSaved = false }
        }
        driving = DrivingState(this) { mode, known ->
            runOnUiThread {
                ui.mode = mode
                ui.modeKnown = known
                director.mode = mode
            }
        }

        val actions = Actions(
            savePrefs = { p ->
                ui.prefs = p
                director.prefs = p
                store.edit().putBoolean("effectsOn", p.effectsOn).putFloat("intensity", p.intensity)
                    .putString("myTeam", p.myTeam).putBoolean("immersiveOnPark", p.immersiveOnPark).putBoolean("cabinOn", p.cabinOn).apply()
            },
            start = { ui.screen = Screen.PLAYER; director.seekTo(0); director.play() },
            toggle = { if (ui.playing) director.pause() else director.play() },
            seek = { director.seekTo(it) },
            openSetup = { director.pause(); ui.screen = Screen.SETUP },
            simulateNav = { director.simulateNavigation() },
            simulateCall = { director.simulateCall() },
            saveFeedback = { scores ->
                store.edit().apply {
                    scores.forEach { (k, v) -> putInt("fb_$k", v) }
                    putLong("fb_at", System.currentTimeMillis())
                }.apply()
                ui.feedbackSaved = true
            },
            replay = { ui.screen = Screen.PLAYER; director.seekTo(0); director.play() },
            preview = {
                // 설정 화면에서 타격 직전부터 6초만 들려준다.
                if (ui.playing) director.pause() else {
                    val hit = game.cues.first { it.type == "HIT" }.t
                    director.seekTo(hit - 2_500)
                    director.play()
                    window.decorView.postDelayed({ if (ui.screen == Screen.SETUP) director.pause() }, 6_500)
                }
            },
        )
        setContent { App(game, CueEngine(game), ui, actions) }
        handleStartAt(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleStartAt(intent)
    }

    /** 시연·화면 확인용: `am start --el startAt 40000 [--ez pause true]`로 해당 시점부터 재생. */
    private fun handleStartAt(intent: android.content.Intent?) {
        // 시연 녹화용: `--es sim nav|call`로 내비 안내·전화 수신을 재현한다.
        when (intent?.getStringExtra("sim")) {
            "nav" -> director.simulateNavigation()
            "call" -> director.simulateCall()
        }
        val at = intent?.getLongExtra("startAt", -1L) ?: -1L
        if (at < 0) return
        ui.screen = Screen.PLAYER
        director.seekTo(at)
        if (intent?.getBooleanExtra("pause", false) == true) director.pause() else director.play()
    }

    override fun onDestroy() {
        driving.close()
        cabin.release()
        director.release()
        super.onDestroy()
    }
}

class Actions(
    val savePrefs: (Prefs) -> Unit,
    val start: () -> Unit,
    val toggle: () -> Unit,
    val seek: (Long) -> Unit,
    val openSetup: () -> Unit,
    val simulateNav: () -> Unit,
    val simulateCall: () -> Unit,
    val saveFeedback: (Map<String, Int>) -> Unit,
    val replay: () -> Unit,
    val preview: () -> Unit,
)
