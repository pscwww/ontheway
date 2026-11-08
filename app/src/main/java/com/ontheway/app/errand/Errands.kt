package com.ontheway.app.errand

import com.ontheway.app.engine.GeoPoint
import com.ontheway.app.engine.StopSuggestion

/**
 * 차 밖 가족이 보낸 부탁. 가족이 웹페이지 지도에서 가게까지 골라서 보낸다.
 * 운전자는 정차 중에 수락하거나 거절만 한다.
 */
data class Errand(
    val id: String,
    val from: String,
    val item: String,
    val storeName: String,
    val storeAddress: String,
    val storePoint: GeoPoint?,
    /** 가족 화면에서 계산한, 들르면 늘어나는 대략적인 시간(분) */
    val extraMin: Int,
    val status: ErrandStatus = ErrandStatus.NEW,
    /** 부탁을 받은 시각 (앱 시계 기준 초) */
    val receivedAtSec: Long? = null,
    /** 차가 계산한, 들르면 늘어나는 거리(km). 계산 전이면 null */
    val extraKm: Double? = null,
    /** 기준 거리를 넘어 차가 자동으로 거절했는지 */
    val autoRejected: Boolean = false,
) {
    fun toStop(): StopSuggestion? = storePoint?.let {
        StopSuggestion(storeName, storeAddress, it.lat, it.lon, extraSec = extraMin * 60)
    }
}

enum class ErrandStatus { NEW, ACCEPTED, REJECTED, DONE }

/** 데모: 가족 폰 없이도 부탁을 넣어 볼 수 있게 경로 위 가게를 하나 만들어 준다 */
object SampleErrands {
    private var seq = 0
    private val samples = listOf(
        Triple("배우자", "아이 해열제", "햇살약국"),
        Triple("배우자", "우유 1통", "길목편의점"),
        Triple("부모님", "과일 조금", "제철과일가게"),
    )

    /** origin→dest 직선의 frac 지점 근처 가게 */
    fun next(origin: GeoPoint, dest: GeoPoint, frac: Double, nowSec: Long): Map<String, Any?> {
        val (from, item, store) = samples[seq % samples.size]
        seq += 1
        val lat = origin.lat + (dest.lat - origin.lat) * frac + 0.003
        val lon = origin.lon + (dest.lon - origin.lon) * frac - 0.003
        return mapOf(
            "from" to from, "item" to item, "extraMin" to 4, "status" to "NEW",
            "createdAt" to System.currentTimeMillis(), "sentClockSec" to nowSec,
            "store" to mapOf("name" to store, "address" to "경로 옆 (데모)", "lat" to lat, "lon" to lon, "f" to frac),
        )
    }
}
