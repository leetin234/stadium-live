package kr.stadiumlive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueEngineTest {
    private val game = Game.parse(File("src/main/assets/game1/game.json").readText())
    private val engine = CueEngine(game)
    private val parked = Prefs(effectsOn = true, intensity = 1f, myTeam = "W")
    private val hit = game.cues.first { it.type == "HIT" }

    /** 25ms 틱으로 [from, to) 구간을 재생했을 때 나온 효과 이름 목록. */
    private fun play(from: Long, to: Long, mode: Mode = Mode.PARKED, p: Prefs = parked): List<String> {
        val out = mutableListOf<String>()
        var prev = from
        var now = from
        while (now < to) {
            now = minOf(now + 25, to)
            out += engine.shots(prev, now, mode, p).map { it.sound }
            prev = now
        }
        return out
    }

    @Test fun assetIsConsistent() {
        assertTrue(game.cues.zipWithNext().all { (a, b) -> a.t <= b.t })
        assertTrue(game.cues.all { it.t in 0..game.durationMs })
        assertEquals(1, game.cues.count { it.type == "HIT" })
        assertEquals(5, game.boardAt(game.durationMs).home)
        assertEquals(4, game.boardAt(0).away)
    }

    @Test fun normalPlaybackFiresEachCueOnce() {
        val shots = play(0, game.durationMs)
        assertEquals(1, shots.count { it == "crack" })
        assertEquals(1, shots.count { it == "whoosh" })
        assertEquals(1, shots.count { it == "foul" })
        assertEquals(2, shots.count { it == "glove" })
        assertEquals(2, shots.count { it == "roar" })
        assertTrue(shots.count { it == "heartbeat" } > 30)
    }

    @Test fun seekAndPauseFireNothing() {
        assertTrue(engine.shots(hit.t - 5_000, hit.t + 100, Mode.PARKED, parked).isEmpty())
        assertTrue(engine.shots(hit.t, hit.t, Mode.PARKED, parked).isEmpty())
        assertTrue(engine.shots(hit.t + 100, hit.t - 50, Mode.PARKED, parked).isEmpty())
    }

    @Test fun unrequestedJumpCatchesUpInstantCuesOnly() {
        val shots = engine.shots(hit.t - 3_000, hit.t + 100, Mode.PARKED, parked, catchUp = true).map { it.sound }
        assertEquals(listOf("crack", "whoosh"), shots)
        assertTrue(engine.shots(hit.t - 20_000, hit.t + 100, Mode.PARKED, parked, catchUp = true).isEmpty())
    }

    @Test fun crackLandsWithinOneTickOfCue() {
        var prev = hit.t - 1_000
        while (prev < hit.t + 1_000) {
            val now = prev + 25
            if (engine.shots(prev, now, Mode.PARKED, parked).any { it.sound == "crack" }) {
                assertTrue(hit.t in (prev + 1)..now)
                return
            }
            prev = now
        }
        throw AssertionError("crack not fired")
    }

    @Test fun heartbeatFollowsBpmAndStopsAtHit() {
        val first = game.cues.first { it.type == "TENSION" && it.bpm > 0 }
        val beats = engine.beats(first.t - 1, first.t + 3_000)
        val step = 60_000 / first.bpm
        assertEquals(first.t, beats.first())
        beats.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(b - a - step) <= 1) }
        assertTrue(engine.beats(hit.t, game.durationMs).isEmpty())
    }

    @Test fun beatsAreSameRegardlessOfTickSize() {
        val first = game.cues.first { it.type == "TENSION" && it.bpm > 0 }
        val whole = engine.beats(first.t - 1, hit.t)
        val split = mutableListOf<Long>()
        var p = first.t - 1
        while (p < hit.t) { val n = minOf(p + 37, hit.t); split += engine.beats(p, n); p = n }
        assertEquals(whole, split)
    }

    @Test fun drivingKeepsCommentaryFocus() {
        val shots = play(0, game.durationMs, Mode.DRIVING)
        assertTrue("heartbeat" !in shots && "whoosh" !in shots && "glove" !in shots)
        assertEquals(0f, engine.mix(hit.t - 2_000, Mode.DRIVING, parked).breathGain)
        val gain = { m: Mode -> engine.shots(hit.t - 10, hit.t, m, parked).first { it.sound == "crack" }.gain }
        assertTrue(gain(Mode.DRIVING) < gain(Mode.PARKED))
    }

    @Test fun opponentFanHearsGroanAndQuietChant() {
        val red = parked.copy(myTeam = "F")
        val shots = play(0, game.durationMs, p = red)
        assertEquals(0, shots.count { it == "roar" })
        assertEquals(2, shots.count { it == "groan" })
        val cheer = game.cues.first { it.type == "CHEER" }
        assertTrue(engine.mix(cheer.t + 1_000, Mode.PARKED, red).chantGain < engine.mix(cheer.t + 1_000, Mode.PARKED, parked).chantGain)
    }

    @Test fun effectsOffIsPlainBroadcast() {
        val off = parked.copy(effectsOn = false)
        assertTrue(play(0, game.durationMs, p = off).isEmpty())
        assertEquals(Mix.SILENT, engine.mix(hit.t - 2_000, Mode.PARKED, off))
    }

    @Test fun sustainedSoundsResumeAfterSeek() {
        val cheer = game.cues.first { it.type == "CHEER" }
        assertEquals("chant_W", engine.mix(cheer.t + 4_000, Mode.PARKED, parked).chant)
        assertTrue(engine.mix(hit.t - 2_000, Mode.PARKED, parked).breathGain > 0f)
        assertEquals(null, engine.mix(cheer.t + cheer.durationMs + 1, Mode.PARKED, parked).chant)
    }
}
