package kr.stadiumlive

import org.json.JSONObject

data class Team(val id: String, val name: String, val color: Long)

data class Cue(
    val t: Long,
    val type: String,
    val label: String = "",
    val bpm: Int = 0,
    val breath: Float = 0f,
    val team: String = "",
    val walkoff: Boolean = false,
    val durationMs: Long = 0,
    val flightMs: Long = 0,
)

data class Caption(val t: Long, val end: Long, val text: String)

/** 화면에 보이는 경기 상황. states 배열을 시간순으로 덮어써 만든다. */
data class Board(
    val inning: String = "",
    val outs: Int = 0,
    val bases: List<Int> = listOf(0, 0, 0),
    val balls: Int = 0,
    val strikes: Int = 0,
    val home: Int = 0,
    val away: Int = 0,
    val batter: String = "",
    val batterLine: String = "",
    val bpm: Int = 0,
    val hit: Boolean = false,
    val final: Boolean = false,
)

class Game(
    val id: String,
    val title: String,
    val note: String,
    val durationMs: Long,
    val home: Team,
    val away: Team,
    val cues: List<Cue>,
    private val states: List<Pair<Long, JSONObject>>,
    val captions: List<Caption>,
) {
    fun team(id: String) = if (id == home.id) home else away

    fun boardAt(ms: Long): Board {
        var b = Board()
        for ((t, s) in states) {
            if (t > ms) break
            b = b.copy(
                inning = s.optString("inning", b.inning),
                outs = s.optInt("outs", b.outs),
                bases = s.optJSONArray("bases")?.let { a -> List(a.length()) { a.getInt(it) } } ?: b.bases,
                balls = s.optInt("balls", b.balls),
                strikes = s.optInt("strikes", b.strikes),
                home = s.optInt("home", b.home),
                away = s.optInt("away", b.away),
                batter = s.optString("batter", b.batter),
                batterLine = s.optString("batterLine", b.batterLine),
                bpm = s.optInt("bpm", b.bpm),
                hit = s.optBoolean("hit", b.hit),
                final = s.optBoolean("final", b.final),
            )
        }
        return b
    }

    fun captionAt(ms: Long): Caption? = captions.lastOrNull { it.t <= ms && ms < it.end + 1200 }

    /** 타임라인 마커로 보여줄 주요 이벤트. */
    val markers: List<Cue> by lazy {
        // 붙어 있는 마커는 글자가 겹치므로 앞의 것만 남긴다(예: 동점·끝내기 득점).
        cues.filter { it.type in setOf("AT_BAT", "HIT", "SCORE", "CHEER") }
            .fold(listOf<Cue>()) { acc, c -> if (acc.isNotEmpty() && c.t - acc.last().t < durationMs / 25) acc else acc + c }
    }

    companion object {
        fun parse(text: String): Game {
            val o = JSONObject(text)
            fun team(j: JSONObject) =
                Team(j.getString("id"), j.getString("name"), 0xFF000000 or j.getString("color").removePrefix("#").toLong(16))
            val cues = o.getJSONArray("cues").let { a ->
                List(a.length()) {
                    val c = a.getJSONObject(it)
                    Cue(
                        t = c.getLong("t"),
                        type = c.getString("type"),
                        label = c.optString("label"),
                        bpm = c.optInt("bpm"),
                        breath = c.optDouble("breath", 0.0).toFloat(),
                        team = c.optString("team"),
                        walkoff = c.optBoolean("walkoff"),
                        durationMs = c.optLong("durationMs"),
                        flightMs = c.optLong("flightMs"),
                    )
                }
            }.sortedBy { it.t }
            val states = o.getJSONArray("states").let { a -> List(a.length()) { a.getJSONObject(it).let { s -> s.getLong("t") to s } } }
                .sortedBy { it.first }
            val captions = o.getJSONArray("captions").let { a ->
                List(a.length()) { a.getJSONObject(it).let { c -> Caption(c.getLong("t"), c.getLong("end"), c.getString("text")) } }
            }
            return Game(
                o.getString("id"), o.getString("title"), o.optString("note"), o.getLong("durationMs"),
                team(o.getJSONObject("home")), team(o.getJSONObject("away")), cues, states, captions,
            )
        }
    }
}
