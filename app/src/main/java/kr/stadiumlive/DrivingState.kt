package kr.stadiumlive

import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.util.Log

/**
 * AAOS 표준 CarUxRestrictions로 주행·정차를 판단한다.
 * 서비스에 연결하지 못했거나 제한 정보가 없으면 '확인 불가'로 보고 주행과 같은 제한을 건다.
 */
class DrivingState(context: Activity, private val update: (Mode, Boolean) -> Unit) {
    private var closed = false
    private var manager: CarUxRestrictionsManager? = null

    private fun apply(rule: CarUxRestrictions?) {
        if (closed) return
        val known = rule != null
        val parked = rule != null && !rule.isRequiresDistractionOptimization &&
            rule.activeRestrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        Log.i(TAG, "known=$known parked=$parked mask=${rule?.activeRestrictions}")
        update(if (parked) Mode.PARKED else Mode.DRIVING, known)
    }

    private val car = runCatching {
        Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            if (closed) return@createCar
            apply(null)
            if (ready) runCatching {
                manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                manager?.registerListener(::apply)
                apply(manager?.currentCarUxRestrictions)
            }.onFailure { Log.w(TAG, "restrictions unavailable", it) }
        }
    }.onFailure { Log.w(TAG, "car service unavailable", it); apply(null) }.getOrNull()

    fun close() {
        closed = true
        runCatching { manager?.unregisterListener() }
        car?.disconnect()
    }

    companion object { const val TAG = "StadiumUX" }
}
