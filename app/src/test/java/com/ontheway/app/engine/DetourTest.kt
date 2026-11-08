package com.ontheway.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourTest {
    // 위도 0.009도 ≈ 1km. 경도 쪽도 비슷한 크기가 되도록 적도 근처 좌표를 씀
    private val me = GeoPoint(0.0, 0.0)
    private val dest = GeoPoint(0.18, 0.0) // 북쪽 약 20km

    @Test
    fun `가는 길 바로 위 가게는 거의 안 늘어남`() {
        val store = GeoPoint(0.09, 0.0)
        assertEquals(0.0, Detour.extraKm(me, store, dest), 0.05)
    }

    @Test
    fun `옆으로 1km 벗어난 가게는 들어갔다 나오는 2km가 늘어남`() {
        val store = GeoPoint(0.09, 0.009) // 10km 앞, 옆으로 1km
        assertEquals(2.0, Detour.extraKm(me, store, dest), 0.05)
        assertFalse(Detour.shouldAutoReject(Detour.extraKm(me, store, dest) - 0.1, 2.0))
    }

    @Test
    fun `옆으로 15km 벗어난 가게는 많이 늘어남 (나보다 가까워도)`() {
        val store = GeoPoint(0.09, 0.135) // 10km 앞, 옆으로 15km
        val extra = Detour.extraKm(me, store, dest)
        assertTrue(Detour.distanceKm(me, store) < Detour.distanceKm(me, dest))
        assertTrue(extra > 29)
        assertTrue(Detour.shouldAutoReject(extra, 2.0))
    }

    @Test
    fun `지나온 쪽 5km 뒤 가게는 약 10km 늘어남`() {
        val store = GeoPoint(-0.045, 0.0)
        assertEquals(10.0, Detour.extraKm(me, store, dest), 0.1)
    }

    @Test
    fun `목적지 너머 3km 가게는 약 6km 늘어남`() {
        val store = GeoPoint(0.207, 0.0)
        assertEquals(6.0, Detour.extraKm(me, store, dest), 0.1)
    }

    @Test
    fun `기준 이하이거나 기준이 꺼져 있으면 자동 거절 안 함`() {
        assertFalse(Detour.shouldAutoReject(1.5, 2.0))
        assertFalse(Detour.shouldAutoReject(30.0, null))
    }

    @Test
    fun `늘어나는 시간은 정차 2분 + 시속 30km`() {
        assertEquals(2, Detour.extraMinutes(0.0))
        assertEquals(6, Detour.extraMinutes(2.0))
    }
}
