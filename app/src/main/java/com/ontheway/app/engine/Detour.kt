package com.ontheway.app.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 부탁 가게에 들르면 늘어나는 거리 (GPS 직선거리 기준, 지도 API 없이).
 *
 *   늘어나는 거리 = (나→가게) + (가게→목적지) − (나→목적지)
 *
 * 가는 길 바로 옆이면 0에 가깝고, 옆으로 벗어나거나 지나온 쪽·목적지 너머에 있으면 커진다.
 */
object Detour {
    private const val EARTH_KM = 6371.0

    /** 두 좌표 사이 직선거리(km, 하버사인) */
    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * 들르면 늘어나는 거리(km).
     * 직선거리 공식은 옆으로 벗어난 가게를 너무 작게 잡는다 (직선은 대각선으로 질러가지만 실제 길은 들어갔다 나와야 함).
     * 그래서 '경로 선(나→목적지)에서 가게까지 거리 × 2'(들어갔다 나오기)와 비교해 큰 값을 쓴다.
     * 지나온 쪽이나 목적지 너머 가게는 두 값이 같다.
     */
    fun extraKm(me: GeoPoint, store: GeoPoint, dest: GeoPoint): Double {
        val ellipse = distanceKm(me, store) + distanceKm(store, dest) - distanceKm(me, dest)
        return maxOf(ellipse, 2 * offRouteKm(me, store, dest), 0.0)
    }

    /** 경로 선(나→목적지 직선 구간)에서 가게까지의 거리(km) */
    fun offRouteKm(me: GeoPoint, store: GeoPoint, dest: GeoPoint): Double {
        val kx = 111.32 * cos(Math.toRadians(me.lat)); val ky = 110.57
        val bx = (dest.lon - me.lon) * kx; val by = (dest.lat - me.lat) * ky
        val px = (store.lon - me.lon) * kx; val py = (store.lat - me.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 늘어나는 시간(분) 대략값: 정차 2분 + 늘어난 거리를 시내 평균 시속 30km로.
     * 수락한 뒤의 실제 도착 시각은 내비가 다시 계산한다.
     */
    fun extraMinutes(extraKm: Double, stopMin: Int = 2, kmh: Double = 30.0): Int =
        stopMin + (extraKm / kmh * 60).roundToInt()

    /** 자동 거절 여부. limitKm가 null이면 끔. */
    fun shouldAutoReject(extraKm: Double, limitKm: Double?): Boolean = limitKm != null && extraKm > limitKm
}
