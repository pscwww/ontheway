package com.ontheway.app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.ontheway.app.engine.EtaSnapshot
import com.ontheway.app.engine.EtaSource
import com.ontheway.app.engine.GeoPoint
import com.ontheway.app.engine.StopSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pleos NaviHelper SDK로 실제 내비 정보를 읽는다.
 * - getDestinationInfo(): 최종 목적지(이름·주소)와 남은 시간 → onDestinationInfo 콜백
 *   목적지가 어디든(집, 부모님 댁, 캠핑장 …) 그 주소가 배달지가 된다.
 * - getCurrentLocationInfo(): 차의 현재 위치 → 가족 지도에 표시
 * - addWaypoint(): 가족이 고른 가게를 경유지로 추가
 *
 * DestinationInfo.remainTime 단위: 초 (Pleos Connect Emulator 2.1에서 확인. 내비 화면 "1분"일 때 60)
 */
class NaviHelperEtaSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 30_000,
) : EtaSource, NaviHelperEventListener {

    private val tag = "OnTheWay/Navi"

    private val _eta = MutableStateFlow<EtaSnapshot?>(null)
    override val eta: StateFlow<EtaSnapshot?> = _eta.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var helper: NaviHelper? = null
    private var pollJob: Job? = null
    private var waypointCount = 0
    @Volatile private var position: GeoPoint? = null

    override fun start() {
        if (helper != null) return
        try {
            val h = NaviHelper(context)
            h.initialize(
                {
                    Log.i(tag, "NaviHelper connected")
                    _connected.value = true
                    h.getWaypointInfo()
                    h.getDestinationInfo()
                    h.getCurrentLocationInfo()
                },
                { e ->
                    Log.e(tag, "NaviHelper connect failed", e)
                    _connected.value = false
                },
            )
            h.addListener(this)
            helper = h
            pollJob = scope.launch {
                while (isActive) {
                    delay(pollIntervalMs)
                    runCatching { helper?.getDestinationInfo(); helper?.getCurrentLocationInfo() }
                        .onFailure { Log.w(tag, "poll failed", it) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "NaviHelper init failed", e)
            _connected.value = false
            helper = null
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        helper?.let {
            runCatching { it.removeListener(this) }
            runCatching { it.release() }
        }
        helper = null
        _connected.value = false
    }

    override fun addWaypoint(stop: StopSuggestion) {
        val h = helper ?: return
        runCatching {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = stop.latitude,
                    longitude = stop.longitude,
                    poiName = stop.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = stop.address,
                    poiSubId = "0",
                )
            )
        }.onFailure { Log.e(tag, "addWaypoint failed", it) }
    }

    // ---- NaviHelperEventListener (필요한 콜백만 구현, 나머지는 SDK 기본 구현) ----

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        Log.i(tag, "onDestinationInfo $destinationInfo")
        _eta.value = EtaSnapshot(
            remainSec = destinationInfo.remainTime,
            destinationName = destinationInfo.name,
            destinationAddress = destinationInfo.address,
            waypointCount = waypointCount,
            destination = GeoPoint(destinationInfo.latitude, destinationInfo.longitude),
            position = position,
        )
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        position = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
        _eta.value = _eta.value?.copy(position = position)
    }

    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
        waypointCount = waypointInfoList.size
        helper?.getDestinationInfo()
    }

    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
        // 경유지가 추가·삭제되면 바로 다시 읽어서 배달 주문 시점을 맞춘다
        helper?.getWaypointInfo()
        helper?.getDestinationInfo()
    }

    override fun onDestinationChanged() {
        helper?.getDestinationInfo()
    }

    override fun onReRouteEnded() {
        helper?.getDestinationInfo()
    }

    override fun onRouteCancelled() {
        _eta.value = null
    }

    override fun onError(errorCode: NaviErrorCode) {
        Log.w(tag, "onError $errorCode")
    }

    /** 목적지 정보를 다시 요청 */
    fun refresh() {
        runCatching { helper?.getDestinationInfo() }
    }
}
