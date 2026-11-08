package com.ontheway.app.engine

/**
 * 내비에서 읽은 현재 경로 상태.
 * remainSec는 경유지를 포함한 "최종 목적지까지" 남은 시간(초).
 */
data class EtaSnapshot(
    val remainSec: Int,
    val destinationName: String?,
    /** 내비에 설정한 최종 목적지 주소 */
    val destinationAddress: String? = null,
    val waypointCount: Int = 0,
    /** 최종 목적지 좌표 */
    val destination: GeoPoint? = null,
    /** 차의 현재 위치 (가족 지도에 표시) */
    val position: GeoPoint? = null,
)

data class GeoPoint(val lat: Double, val lon: Double)

/** 지금 주문을 넣을지에 대한 판단 */
sealed interface Decision {
    /** 주문하지 않고 보류 (목적지가 설정되지 않음) */
    data class Hold(val reason: HoldReason) : Decision

    /** 아직 이르다. secondsUntilOrder 뒤에 주문 */
    data class Wait(val secondsUntilOrder: Int) : Decision

    /** 지금 주문 */
    data object OrderNow : Decision
}

enum class HoldReason { NO_ROUTE }

/**
 * 오는 길에의 핵심: 내비 목적지가 정해지면, 사람이 음식보다 bufferSec 먼저 목적지에 도착하도록
 * 주문 시점을 계산한다. 목적지는 집이 아니어도 된다 (부모님 댁, 캠핑장, 친구 집 등).
 *
 *   사람 도착 = 지금 + R (내비 남은 시간)
 *   음식 도착 = 주문 시각 + D (가게 배달 예상 시간)
 *   목표: 음식 도착 = 사람 도착 + B (여유 시간, 사람이 B만큼 먼저 도착)
 *   → 주문까지 남은 시간 = R + B - D
 *
 * 순수 Kotlin이라 안드로이드 없이 단위 테스트할 수 있다.
 */
object TimingEngine {

    fun decide(eta: EtaSnapshot?, deliveryEstimateSec: Int, bufferSec: Int): Decision {
        if (eta == null || eta.remainSec <= 0) return Decision.Hold(HoldReason.NO_ROUTE)

        val untilOrder = eta.remainSec + bufferSec - deliveryEstimateSec
        // 이동 시간이 배달 시간보다 짧으면 바로 주문 (음식이 조금 늦게 도착)
        return if (untilOrder <= 0) Decision.OrderNow else Decision.Wait(untilOrder)
    }

    /**
     * 주문을 넣은 뒤 정체로 사람이 늦어질 때, 가게에 요청할 조리 지연 시간(초).
     * 음식이 목표 시각(사람 도착 + B)보다 toleranceSec 넘게 일찍 오게 되면 그 차이만큼 지연을 요청한다.
     * 0이면 요청할 필요 없음.
     */
    fun cookingDelayNeeded(
        personArrivalEpochSec: Long,
        foodArrivalEpochSec: Long,
        bufferSec: Int,
        toleranceSec: Int = 120,
    ): Int {
        val targetFoodArrival = personArrivalEpochSec + bufferSec
        val foodTooEarlyBy = targetFoodArrival - foodArrivalEpochSec
        return if (foodTooEarlyBy > toleranceSec) foodTooEarlyBy.toInt() else 0
    }
}
