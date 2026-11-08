package com.ontheway.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 경로 위에 들를 가게 (가족이 고른 곳) */
data class StopSuggestion(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    /** 들렀을 때 늘어나는 예상 시간(초) */
    val extraSec: Int,
)

/** 도착 시간을 알려 주는 곳. 실제 내비(NaviHelper) 또는 데모용 시뮬레이션. */
interface EtaSource {
    val eta: StateFlow<EtaSnapshot?>
    val connected: StateFlow<Boolean>
    fun start()
    fun stop()
    fun addWaypoint(stop: StopSuggestion)
}

/**
 * 데모·에뮬레이터용. 에뮬레이터에는 내비 시뮬레이션 카드가 없어서 남은 시간을 직접 넣는다.
 * 현재 위치는 출발지(회사)와 목적지 사이를 남은 시간 비율만큼 움직인 지점으로 흉내 낸다.
 */
class SimulatedEtaSource(initialRemainSec: Int = 60 * 60) : EtaSource {
    private var destIndex = 0
    private var totalSec = initialRemainSec

    private val _eta = MutableStateFlow<EtaSnapshot?>(snapshot(DESTINATIONS.first(), initialRemainSec, 0))
    override val eta: StateFlow<EtaSnapshot?> = _eta.asStateFlow()

    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit

    /** 출발지 (데모: 회사) */
    val origin = ORIGIN
    /** 출발할 때 남은 시간. 가족 지도에서 진행률을 그릴 때 쓴다. */
    val totalRouteSec: Int get() = totalSec

    /** 시뮬레이션 시간이 elapsedSec 만큼 흘렀다 */
    fun advance(elapsedSec: Int) = update { (it - elapsedSec).coerceAtLeast(0) }

    /** 정체 발생: 도착이 extraSec 늦어짐 */
    fun addDelay(extraSec: Int) {
        totalSec += extraSec // 위치가 뒤로 가지 않도록 전체 길이도 같이 늘림
        update { it + extraSec }
    }

    fun setRemain(remainSec: Int) {
        totalSec = remainSec
        update { remainSec.coerceAtLeast(0) }
    }

    /** 데모: 내비 목적지를 다음 예시로 바꾼다. 경로가 새로 잡히므로 남은 시간과 경유지도 새로 정해진다. */
    fun nextDestination(): EtaSnapshot {
        destIndex = (destIndex + 1) % DESTINATIONS.size
        val d = DESTINATIONS[destIndex]
        totalSec = d.remainSec
        return snapshot(d, d.remainSec, 0).also { _eta.value = it }
    }

    fun resetDestination(remainSec: Int) {
        destIndex = 0
        totalSec = remainSec
        _eta.value = snapshot(DESTINATIONS.first(), remainSec, 0)
    }

    override fun addWaypoint(stop: StopSuggestion) {
        val cur = _eta.value ?: return
        totalSec += stop.extraSec
        _eta.value = cur.copy(remainSec = cur.remainSec + stop.extraSec, waypointCount = cur.waypointCount + 1)
    }

    private fun update(f: (Int) -> Int) {
        val cur = _eta.value ?: return
        val remain = f(cur.remainSec)
        _eta.value = cur.copy(remainSec = remain, position = positionAt(remain, cur.destination))
    }

    private fun snapshot(d: Dest, remain: Int, waypoints: Int) = EtaSnapshot(
        remainSec = remain, destinationName = d.name, destinationAddress = d.address,
        waypointCount = waypoints, destination = d.point, position = positionAt(remain, d.point),
    )

    private fun positionAt(remain: Int, dest: GeoPoint?): GeoPoint? {
        dest ?: return null
        val p = if (totalSec <= 0) 1.0 else (1.0 - remain.toDouble() / totalSec).coerceIn(0.0, 1.0)
        return GeoPoint(ORIGIN.lat + (dest.lat - ORIGIN.lat) * p, ORIGIN.lon + (dest.lon - ORIGIN.lon) * p)
    }

    private data class Dest(val name: String, val address: String, val point: GeoPoint, val remainSec: Int)

    companion object {
        /** 데모 출발지 */
        val ORIGIN = GeoPoint(37.4979, 127.0276)
        const val ORIGIN_NAME = "회사"

        /** 데모용 목적지 예시. 실제로는 내비에 설정된 목적지를 그대로 쓴다. */
        private val DESTINATIONS = listOf(
            Dest("집", "경기도 예시시 집로 1", GeoPoint(37.3595, 127.1052), 60 * 60),
            Dest("부모님 댁", "경기도 예시시 효도로 2", GeoPoint(37.2636, 127.0286), 75 * 60),
            Dest("캠핑장", "강원도 예시군 숲길 3", GeoPoint(37.8315, 127.5105), 90 * 60),
        )
    }
}
