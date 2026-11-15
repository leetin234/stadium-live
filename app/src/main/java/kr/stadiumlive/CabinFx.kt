package kr.stadiumlive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** 화면에 보여 줄 실내 연출 상태. 값은 차량이 '설정 완료'로 응답한 것만 반영한다. */
data class CabinState(
    val ready: Boolean = false,
    val light: Boolean = false,
    val fan: Boolean = false,
    val color: Int = 0,
    val brightness: Int = 0,
    val brightnessMax: Int = 100,
    val fanLevel: Int = 0,
    val fanMax: Int = 0,
    val label: String = "",
    val note: String = "차량 연결 중",
)

/**
 * Pleos Vehicle SDK로 실내 앰비언트 조명(색·밝기)과 공조 송풍 세기를 바꾼다.
 * 지원 범위는 capability API로 먼저 확인하고, 연출이 끝나면 처음 상태로 되돌린다.
 */
class CabinFx(context: Context, private val report: (CabinState) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var state = CabinState()
    private var lightAreas: List<SeatArea> = emptyList()
    private var brightBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var fanBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var original: Triple<Int?, Int?, Int?> = Triple(null, null, null) // 색, 밝기, 송풍
    private var active = false
    private var lastSent = 0L
    private var sentColor = -1
    private var sentBright = -1
    private var sentFan = -1
    /** 시작 전 공조 전원. 꺼져 있었다면 연출 동안만 켜고 끝나면 다시 끈다. */
    private var originalPower: Boolean? = null
    private var fanFailures = 0
    private var powerChecked = false

    init {
        runCatching { vehicle.initialize() }.onFailure { emit(state.copy(note = "차량 SDK 연결 실패")) }
        main.postDelayed({ probe() }, 1_500)
    }

    private fun emit(s: CabinState) { state = s; main.post { report(s) } }

    private fun probe() = runCatching {
        val light = vehicle.getLight()
        light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
            lightAreas = areas
            Log.i(TAG, "ambient color areas=${areas.size}")
            emit(state.copy(ready = true, light = areas.isNotEmpty(), note = if (areas.isEmpty()) "앰비언트 조명 미지원" else "실내 조명 연결"))
            areas.firstOrNull()?.let { a -> light.getAmbientLightBrightnessColor(a, { c -> original = original.copy(first = c) }, {}) }
        }, { e -> Log.w(TAG, "ambient color unsupported", e); emit(state.copy(ready = true, note = "앰비언트 조명 미지원")) })
        light.checkAmbientLightBrightnessCapabilities({ _, map ->
            brightBounds = map
            map.values.firstOrNull()?.let { b -> emit(state.copy(brightnessMax = b.maxValue)) }
        }, { Log.w(TAG, "ambient brightness unsupported", it) })
        vehicle.getHVAC().checkHvacFanSpeedCapabilities({ _, map ->
            fanBounds = map
            Log.i(TAG, "fan areas=${map.size} bounds=${map.values.firstOrNull()?.let { "${it.minValue}..${it.maxValue}" }}")
            emit(state.copy(fan = map.isNotEmpty(), fanMax = map.values.firstOrNull()?.maxValue ?: 0))
            map.keys.firstOrNull()?.let { a ->
                vehicle.getHVAC().getHvacFanSpeed(a, { f -> original = original.copy(third = f) }, {})
                vehicle.getHVAC().getHvacPowerState(a, { on -> originalPower = on; Log.i(TAG, "hvac power=$on") }, {})
            }
        }, { Log.w(TAG, "fan unsupported", it) })
    }.onFailure { Log.w(TAG, "probe failed", it) }

    /** 목표값 적용. null이면 원래 상태로 복원. 차량에는 최대 초당 8회만 보낸다. */
    fun apply(target: Cabin?) {
        if (target == null) { restore(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < 120) return
        lastSent = now
        if (lightAreas.isEmpty() && fanBounds.isEmpty()) return // 차량 능력 확인 전에는 보내지 않는다
        if (!active) {
            active = true
            lightAreas.forEach { a -> runCatching { vehicle.getLight().setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> }, {}) } }
        }
        if (fanBounds.isNotEmpty() && !powerChecked) {
            powerChecked = true
                // 공조가 꺼져 있으면 송풍을 바꿀 수 없으므로 연출 동안만 켠다.
            fanBounds.keys.forEach { a ->
                runCatching {
                    vehicle.getHVAC().getHvacPowerState(a, { on ->
                        originalPower = on
                        Log.i(TAG, "hvac power was=$on")
                        if (on == false && active) vehicle.getHVAC().setHvacPower(a, true, { _, now -> Log.i(TAG, "hvac on=$now"); sentFan = -1; fanFailures = 0 }, { Log.w(TAG, "power", it) })
                    }, { Log.i(TAG, "hvac power state n/a: ${it.message}") })
                }
            }
        }
        val light = vehicle.getLight()
        if (target.color != sentColor) {
            sentColor = target.color
            lightAreas.forEach { a ->
                runCatching { light.setAmbientLightBrightnessColor(a, target.color, { _, c -> Log.i(TAG, "ok color=#%06X".format((c ?: target.color) and 0xFFFFFF)); emit(state.copy(color = c ?: target.color, label = target.label)) }, { Log.w(TAG, "color", it) }) }
            }
        }
        brightBounds.forEach { (a, b) ->
            val v = (b.minValue + (b.maxValue - b.minValue) * target.brightness.coerceIn(0f, 1f)).toInt()
            if (v != sentBright) {
                sentBright = v
                runCatching { light.setAmbientLightBrightness(a, v, { _, got -> emit(state.copy(brightness = got ?: v, label = target.label)) }, { Log.w(TAG, "bright", it) }) }
            }
        }
        val hvac = vehicle.getHVAC()
        fanBounds.forEach { (a, b) ->
            val base = original.third ?: b.minValue
            val v = if (target.fan <= 0.01f) base else maxOf(base, (b.minValue + (b.maxValue - b.minValue) * target.fan).toInt())
            if (v != sentFan) {
                sentFan = v
                runCatching { hvac.setHvacFanSpeed(a, v, { _, got -> fanFailures = 0; Log.i(TAG, "ok fan=$got"); emit(state.copy(fanLevel = got ?: v, label = target.label)) }, {
                    // 공조가 꺼져 있거나 지원하지 않으면 몇 번 뒤 송풍 연출만 접는다.
                    Log.w(TAG, "fan ${it.message}")
                    if (++fanFailures >= 3) { fanBounds = emptyMap(); emit(state.copy(fan = false)) }
                }) }
            }
        }
        if (state.label != target.label) emit(state.copy(label = target.label))
    }

    /** 연출을 멈추고 시작 전 색·밝기·송풍으로 돌려놓는다. */
    fun restore() {
        if (!active) return
        active = false
        powerChecked = false
        sentColor = -1; sentBright = -1; sentFan = -1
        val (c, _, f) = original
        lightAreas.forEach { a -> c?.let { runCatching { vehicle.getLight().setAmbientLightBrightnessColor(a, it, { _, _ -> }, {}) } } }
        fanBounds.keys.forEach { a ->
            f?.let { runCatching { vehicle.getHVAC().setHvacFanSpeed(a, it, { _, got -> emit(state.copy(fanLevel = got ?: it)) }, {}) } }
            if (originalPower == false) runCatching { vehicle.getHVAC().setHvacPower(a, false, { _, _ -> }, {}) }
        }
        emit(state.copy(label = "복원 · 원래 실내 상태"))
        Log.i(TAG, "restored color=$c fan=$f")
    }

    fun release() {
        restore()
        runCatching { vehicle.release() }
    }

    companion object { const val TAG = "StadiumCabin" }
}
