package com.ontheway.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingEngineTest {

    private val min = 60
    private fun home(remainMin: Int, waypoints: Int = 0) =
        EtaSnapshot(remainSec = remainMin * min, destinationName = "집", destinationAddress = "집 주소", waypointCount = waypoints)

    @Test
    fun `경로가 없으면 보류`() {
        assertEquals(Decision.Hold(HoldReason.NO_ROUTE), TimingEngine.decide(null, 40 * min, 5 * min))
    }

    @Test
    fun `목적지가 집이 아니어도 같은 방식으로 주문 시점을 계산`() {
        val eta = EtaSnapshot(90 * min, "캠핑장", destinationAddress = "캠핑장 주소")
        // 90 + 5 - 40 = 55분 뒤
        assertEquals(Decision.Wait(55 * min), TimingEngine.decide(eta, 40 * min, 5 * min))
    }

    @Test
    fun `60분 남고 배달 40분 여유 5분이면 25분 뒤 주문`() {
        // 주문까지 = R + B - D = 60 + 5 - 40 = 25분
        assertEquals(Decision.Wait(25 * min), TimingEngine.decide(home(60), 40 * min, 5 * min))
    }

    @Test
    fun `남은 시간이 35분이 되면 지금 주문`() {
        assertEquals(Decision.OrderNow, TimingEngine.decide(home(35), 40 * min, 5 * min))
    }

    @Test
    fun `이동이 배달보다 짧으면 바로 주문`() {
        assertEquals(Decision.OrderNow, TimingEngine.decide(home(20), 40 * min, 5 * min))
    }

    @Test
    fun `경유지로 4분 늦어지면 주문도 4분 늦춘다`() {
        val before = TimingEngine.decide(home(60), 40 * min, 5 * min) as Decision.Wait
        val after = TimingEngine.decide(home(64, waypoints = 1), 40 * min, 5 * min) as Decision.Wait
        assertEquals(4 * min, after.secondsUntilOrder - before.secondsUntilOrder)
    }

    @Test
    fun `주문 후 사람이 크게 늦어지면 조리 지연 요청`() {
        // 사람 19:34 도착, 음식 19:30 도착 예정, 여유 3분 → 목표 19:37, 7분 일찍 옴
        val delay = TimingEngine.cookingDelayNeeded(
            personArrivalEpochSec = 34L * min,
            foodArrivalEpochSec = 30L * min,
            bufferSec = 3 * min,
        )
        assertEquals(7 * min, delay)
    }

    @Test
    fun `차이가 허용치 안이면 지연 요청 안 함`() {
        val delay = TimingEngine.cookingDelayNeeded(
            personArrivalEpochSec = 30L * min,
            foodArrivalEpochSec = 32L * min,
            bufferSec = 3 * min,
        )
        assertEquals(0, delay)
    }
}
