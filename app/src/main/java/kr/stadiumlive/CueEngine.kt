package kr.stadiumlive

enum class Mode { PARKED, DRIVING }

/** 사용자 설정. intensity는 0(약)~1(강). */
data class Prefs(
    val effectsOn: Boolean = true,
    val intensity: Float = 0.7f,
    val myTeam: String = "W",
    /** 정차하면 몰입 화면으로 전환할지(끄면 정차 중에도 해설 중심 화면). */
    val immersiveOnPark: Boolean = true,
)

/** 한 번 재생하는 효과음. pan은 -1(왼쪽)~1(오른쪽). */
data class Shot(val sound: String, val gain: Float, val pan: Float = 0f)

/** 지금 시각에 계속 깔려 있어야 하는 소리(반복 재생)의 목표 음량. */
data class Mix(
    val breathGain: Float = 0f,
    val chant: String? = null,
    val chantGain: Float = 0f,
) {
    companion object { val SILENT = Mix() }
}

/**
 * 중계 재생 위치(ms)만 보고 효과를 결정한다. 상태를 갖지 않으므로
 * 일시정지·탐색·되감기 후에도 같은 위치에서는 항상 같은 결과가 나온다.
 */
class CueEngine(private val game: Game) {

    /**
     * prev→now 사이에 지나간 순간 효과. 정상 재생으로 넘어간 구간만 다룬다.
     * 멈춤(now==prev)·되감기·큰 점프(탐색)에서는 아무것도 내지 않아 중복·지각 재생을 막는다.
     */
    fun shots(prev: Long, now: Long, mode: Mode, p: Prefs, catchUp: Boolean = false): List<Shot> {
        if (!p.effectsOn || now <= prev) return emptyList()
        // 사용자가 직접 탐색한 점프는 건너뛴다. 오디오 장치 지연 보정처럼 의도하지 않은 짧은 점프는
        // 순간 효과(타격·함성 등)만 따라잡고 지나간 심박은 몰아서 내지 않는다.
        val jumped = now - prev > MAX_STEP_MS
        if (jumped && (!catchUp || now - prev > MAX_CATCH_UP_MS)) return emptyList()
        val k = p.intensity.coerceIn(0f, 1f)
        val drive = mode == Mode.DRIVING
        val out = mutableListOf<Pair<Long, Shot>>()
        for (c in game.cues) {
            if (c.t <= prev) continue
            if (c.t > now) break
            val s = when (c.type) {
                "BALL", "STRIKE" -> if (drive) null else Shot("glove", 0.75f * k)
                "FOUL" -> Shot("foul", (if (drive) 0.35f else 0.8f) * k)
                "HIT" -> Shot("crack", (if (drive) 0.55f else 1f) * (0.4f + 0.6f * k))
                "SCORE" ->
                    if (c.team == p.myTeam) Shot("roar", (if (drive) 0.45f else 1f) * (0.4f + 0.6f * k))
                    else Shot("groan", (if (drive) 0.3f else 0.75f) * k)
                else -> null
            }
            if (s != null) out += c.t to s
            if (c.type == "HIT" && !drive) out += c.t to Shot("whoosh", 0.85f * k, -0.8f)
        }
        if (!drive && !jumped) beats(prev, now).forEach { out += it to Shot("heartbeat", 0.95f * k) }
        return out.sortedBy { it.first }.map { it.second }.filter { it.gain > 0.01f }
    }

    /** prev < t ≤ now 인 심장 박동 시각. 박자는 긴장 구간 시작점에서 bpm 간격으로 고정된다. */
    fun beats(prev: Long, now: Long): List<Long> {
        val out = mutableListOf<Long>()
        val tension = game.cues.filter { it.type == "TENSION" }
        for ((i, c) in tension.withIndex()) {
            if (c.bpm <= 0) continue
            val end = tension.getOrNull(i + 1)?.t ?: game.durationMs
            if (end <= prev || c.t > now) continue
            val step = 60_000.0 / c.bpm
            var k = kotlin.math.max(0.0, kotlin.math.floor((prev - c.t) / step) + 1)
            while (true) {
                val b = c.t + (k * step).toLong()
                if (b >= end || b > now) break
                if (b > prev) out += b
                k += 1
            }
        }
        return out
    }

    fun tensionAt(ms: Long): Cue? = game.cues.lastOrNull { it.type == "TENSION" && it.t <= ms }?.takeIf { it.bpm > 0 }

    fun mix(ms: Long, mode: Mode, p: Prefs): Mix {
        if (!p.effectsOn) return Mix.SILENT
        val k = p.intensity.coerceIn(0f, 1f)
        val drive = mode == Mode.DRIVING
        val breath = if (drive) 0f else (tensionAt(ms)?.breath ?: 0f) * 0.55f * k
        val cheer = game.cues.firstOrNull { it.type == "CHEER" && it.t <= ms && ms < it.t + it.durationMs }
        val chantGain = cheer?.let {
            val mine = it.team == p.myTeam
            val fadeOut = ((it.t + it.durationMs - ms) / 2500f).coerceIn(0f, 1f)
            (if (mine) 0.8f else 0.22f) * (if (drive) 0.5f else 1f) * (0.4f + 0.6f * k) * fadeOut
        } ?: 0f
        return Mix(breath, cheer?.let { "chant_${it.team}" }, chantGain)
    }

    /** 타구 비행 진행률 0..1, 비행 중이 아니면 null. 화면 궤적과 좌우 이동에 함께 쓴다. */
    fun flightAt(ms: Long): Float? {
        val hit = game.cues.firstOrNull { it.type == "HIT" && it.t <= ms && ms < it.t + it.flightMs } ?: return null
        return (ms - hit.t).toFloat() / hit.flightMs
    }

    /** 마지막 타격 뒤 '딱!' 글자를 보여줄 시간인지. */
    fun crackFlash(ms: Long): Boolean = game.cues.any { it.type == "HIT" && ms in it.t until it.t + 900 }

    companion object {
        /** 이보다 큰 위치 변화는 탐색으로 본다(틱 간격 25ms의 수십 배). */
        const val MAX_STEP_MS = 700L

        /** 의도하지 않은 점프를 따라잡는 최대 폭. */
        const val MAX_CATCH_UP_MS = 10_000L
    }
}
