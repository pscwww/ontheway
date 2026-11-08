package com.ontheway.app.delivery

/** 미리 저장해 둔 주문 (프리셋) */
data class OrderPreset(
    val id: String,
    val storeName: String,
    val menu: String,
    val servings: Int,
    val priceWon: Int,
)

data class PlacedOrder(
    val orderId: String,
    val preset: OrderPreset,
    val placedAtSec: Long,
    /** 배달지 이름과 주소 (내비 목적지) */
    val deliverToName: String,
    val deliverToAddress: String,
    /** 음식이 배달지에 도착할 예상 시각 (시뮬레이션 시계 기준 초) */
    val foodArrivalSec: Long,
)

/**
 * 가맹점 자사앱·POS 연동 인터페이스.
 * 데모에서는 MockDeliveryApi를 쓰고, 제휴가 되면 실제 API 구현으로 교체한다.
 */
interface DeliveryApi {
    fun presets(): List<OrderPreset>
    /** 지금 주문하면 배달까지 걸리는 예상 시간(초) */
    fun deliveryEstimateSec(storeName: String, nowSec: Long): Int
    /** 내비 목적지를 배달지로 주문. 실제 서비스는 목적지 근처 지점으로 주문이 들어간다. */
    fun placeOrder(preset: OrderPreset, nowSec: Long, deliverToName: String, deliverToAddress: String): PlacedOrder
    /** 조리 시작을 delaySec 미뤄 달라고 요청. 새 도착 예상 시각을 돌려준다. */
    fun requestCookingDelay(order: PlacedOrder, delaySec: Int): PlacedOrder
    /** 주문 뒤 목적지가 바뀌면 배달지 변경을 요청 */
    fun requestAddressChange(order: PlacedOrder, deliverToName: String, deliverToAddress: String): PlacedOrder
}

class MockDeliveryApi : DeliveryApi {
    private var seq = 0

    override fun presets() = listOf(
        OrderPreset("p1", "동네 찌개집", "김치찌개 2인", servings = 2, priceWon = 22_000),
        OrderPreset("p2", "치킨 프랜차이즈", "후라이드 1마리", servings = 3, priceWon = 21_000),
        OrderPreset("p3", "분식집", "떡볶이·김밥 세트", servings = 1, priceWon = 13_000),
    )

    /** 저녁 피크(18~20시)에 더 오래 걸리는 것처럼 흉내 낸다 */
    override fun deliveryEstimateSec(storeName: String, nowSec: Long): Int {
        val hour = ((nowSec / 3600) % 24).toInt()
        val base = 35 * 60
        val peak = if (hour in 18..19) 5 * 60 else 0
        return base + peak
    }

    override fun placeOrder(preset: OrderPreset, nowSec: Long, deliverToName: String, deliverToAddress: String): PlacedOrder {
        seq += 1
        return PlacedOrder(
            orderId = "MOCK-$seq",
            preset = preset,
            placedAtSec = nowSec,
            deliverToName = deliverToName,
            deliverToAddress = deliverToAddress,
            foodArrivalSec = nowSec + deliveryEstimateSec(preset.storeName, nowSec),
        )
    }

    override fun requestCookingDelay(order: PlacedOrder, delaySec: Int): PlacedOrder =
        order.copy(foodArrivalSec = order.foodArrivalSec + delaySec)

    override fun requestAddressChange(order: PlacedOrder, deliverToName: String, deliverToAddress: String): PlacedOrder =
        order.copy(deliverToName = deliverToName, deliverToAddress = deliverToAddress)
}
