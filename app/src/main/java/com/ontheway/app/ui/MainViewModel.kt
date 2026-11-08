package com.ontheway.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ontheway.app.delivery.DeliveryApi
import com.ontheway.app.delivery.MockDeliveryApi
import com.ontheway.app.delivery.OrderPreset
import com.ontheway.app.engine.Decision
import com.ontheway.app.engine.Detour
import com.ontheway.app.engine.EtaSnapshot
import com.ontheway.app.engine.EtaSource
import com.ontheway.app.engine.GeoPoint
import com.ontheway.app.engine.SimulatedEtaSource
import com.ontheway.app.engine.TimingEngine
import com.ontheway.app.errand.Errand
import com.ontheway.app.errand.ErrandStatus
import com.ontheway.app.errand.SampleErrands
import com.ontheway.app.navi.NaviHelperEtaSource
import com.ontheway.app.sync.RtdbClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalTime
import kotlin.math.abs

enum class EtaMode { SIMULATION, NAVI }

enum class OrderStatus { PLANNED, ALERTED, ORDERED, ARRIVED }

/** 배달 타이밍 계획. 가족(웹)이나 운전자(차)가 적어 둔다. */
data class OrderPlan(
    val menu: String,
    val store: String,
    val deliveryMin: Int,
    val bufferMin: Int,
    val by: String,
    val status: OrderStatus,
    val orderAtClockSec: Long? = null,
    val orderedClockSec: Long? = null,
    val foodArrivalClockSec: Long? = null,
    val lateMin: Int = 0,
    val actualMin: Int? = null,
)

data class UiState(
    val mode: EtaMode = EtaMode.SIMULATION,
    val naviConnected: Boolean = false,
    val clockSec: Long = 18L * 3600 + 30 * 60,
    val simSpeed: Int = 60,
    val started: Boolean = false,
    val parked: Boolean = true,
    val shareOn: Boolean = true,
    val eta: EtaSnapshot? = null,
    val presets: List<OrderPreset> = emptyList(),
    val selectedPresetId: String? = null,
    val plan: OrderPlan? = null,
    val decision: Decision? = null,
    val errands: List<Errand> = emptyList(),
    val learned: Map<String, Int> = emptyMap(),
    val syncUrl: String = "",
    val syncConnected: Boolean = false,
    val log: List<String> = emptyList(),
    val showDemo: Boolean = true,
    /** 자동 거절 기준: 들르면 늘어나는 거리(km). null이면 끔 */
    val autoRejectKm: Double? = 2.0,
) {
    /** 사람이 목적지에 도착할 예상 시각 */
    val arrivalSec: Long?
        get() = eta?.takeIf { it.remainSec >= 0 }?.let { clockSec + it.remainSec }

    val arrived: Boolean get() = started && eta != null && eta.remainSec == 0

    /** 가족에게 주문 알림을 보낼 시각 */
    val alertAtSec: Long?
        get() = plan?.orderAtClockSec ?: (decision as? Decision.Wait)?.let { clockSec + it.secondsUntilOrder }

    /** 음식 도착 예상 시각: 주문했으면 실제 값, 아니면 계획 값 */
    val foodArrivalSec: Long?
        get() = plan?.foodArrivalClockSec ?: alertAtSec?.let { it + (plan?.deliveryMin ?: 0) * 60 }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val delivery: DeliveryApi = MockDeliveryApi() // 2단계(가게 제휴) 자리. 지금은 메뉴 예시로만 씀
    private val simSource = SimulatedEtaSource()
    private val naviSource = NaviHelperEtaSource(app, viewModelScope, pollIntervalMs = 15_000)
    private var source: EtaSource = simSource

    private val prefs = app.getSharedPreferences("ontheway", Context.MODE_PRIVATE)
    private val sync = RtdbClient(viewModelScope)
    private val tripPath = "trips/demo"

    private val _state = MutableStateFlow(
        UiState(
            presets = delivery.presets(),
            selectedPresetId = delivery.presets().first().id,
            syncUrl = prefs.getString("syncUrl", DEFAULT_SYNC_URL) ?: DEFAULT_SYNC_URL,
            autoRejectKm = prefs.getFloat("autoRejectKm", 2f).takeIf { it > 0f }?.toDouble(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var naviOrigin: GeoPoint? = null
    private var naviTotalSec = 0

    init {
        evaluate()
        startTicker()
        connectSync(_state.value.syncUrl)
        viewModelScope.launch { sync.data.collect { onSyncData(it) } }
        viewModelScope.launch {
            sync.connected.collect { c ->
                _state.update { it.copy(syncConnected = c) }
                if (c) publishTrip(force = true)
            }
        }
    }

    // ---------- 운전자 동작 ----------

    fun selectPreset(id: String) = _state.update { it.copy(selectedPresetId = id) }

    /** 운전자가 직접 메뉴를 정함 (가족이 웹에서 적어도 됨). 정차 중에만. */
    fun planFromPreset() {
        val s = _state.value
        if (!s.parked) return
        val p = s.presets.firstOrNull { it.id == s.selectedPresetId } ?: return
        val minutes = s.learned[storeKey(p.storeName)] ?: (delivery.deliveryEstimateSec(p.storeName, s.clockSec) / 60)
        val plan = OrderPlan(p.menu, p.storeName, minutes, 3, by = "driver", status = OrderStatus.PLANNED)
        lastSentOrderAt = null
        _state.update { it.copy(plan = plan) }
        write { sync.put("$tripPath/order", planJson(plan)) }
        log("배달 타이밍 설정: ${p.menu} (보통 ${minutes}분)")
        evaluate()
    }

    fun cancelPlan() {
        if (!_state.value.parked) return
        _state.update { it.copy(plan = null, decision = null) }
        write { sync.delete("$tripPath/order") }
        log("배달 타이밍 취소")
    }

    /** 정차 중 1탭: 가족이 고른 가게를 경유지로 추가 → 도착이 늦어지면 주문 알림 시점도 자동으로 밀림 */
    fun acceptErrand(id: String) {
        val s = _state.value
        if (!s.parked) return
        val e = s.errands.firstOrNull { it.id == id } ?: return
        e.toStop()?.let { source.addWaypoint(it) }
        setErrandStatus(id, ErrandStatus.ACCEPTED, mapOf("acceptedClockSec" to s.clockSec))
        log("수락: ${e.storeName} 경유지 추가 (+${e.extraMin}분)")
        evaluate()
        publishTrip(force = true)
    }

    fun rejectErrand(id: String) {
        val s = _state.value
        if (!s.parked) return
        val e = s.errands.firstOrNull { it.id == id } ?: return
        setErrandStatus(id, ErrandStatus.REJECTED, mapOf("rejectedClockSec" to s.clockSec))
        log("거절: ${e.from}의 부탁 (${e.item})")
    }

    fun setParked(parked: Boolean) {
        _state.update { it.copy(parked = parked) }
        publishTrip(force = true)
    }

    fun setShareOn(on: Boolean) {
        _state.update { it.copy(shareOn = on) }
        log(if (on) "위치 공유 켬" else "위치 공유 끔 (도착 시각만 공유)")
        publishTrip(force = true)
    }

    /** 설정: 자동 거절 기준 거리 (null = 끔). 정차 중에만 바꿀 수 있음 */
    fun setAutoRejectKm(km: Double?) {
        if (!_state.value.parked) return
        prefs.edit().putFloat("autoRejectKm", km?.toFloat() ?: 0f).apply()
        _state.update { it.copy(autoRejectKm = km) }
        log(if (km == null) "자동 거절 끔" else "자동 거절 기준: 들르면 ${fmtKm(km)}km 넘게 늘어나는 부탁")
        publishTrip(force = true)
    }

    // ---------- 데모 컨트롤 ----------

    /** 시뮬레이션: 출발 (가족 화면에 운행이 보이기 시작) / 일시정지 */
    fun toggleStarted() {
        val start = !_state.value.started
        _state.update { it.copy(started = start, parked = if (start) false else it.parked) }
        log(if (start) "출발 · ${_state.value.eta?.destinationName ?: "목적지"}까지 가족과 공유 시작" else "일시정지")
        evaluate()
        publishTrip(force = true)
    }

    fun setMode(mode: EtaMode) {
        if (mode == _state.value.mode) return
        source.stop()
        source = if (mode == EtaMode.NAVI) naviSource else simSource
        source.start()
        naviOrigin = null
        naviTotalSec = 0
        _state.update {
            it.copy(
                mode = mode,
                started = mode == EtaMode.NAVI,
                clockSec = if (mode == EtaMode.NAVI) LocalTime.now().toSecondOfDay().toLong() else it.clockSec,
            )
        }
        log(if (mode == EtaMode.NAVI) "실제 내비(NaviHelper) 모드" else "시뮬레이션 모드")
        evaluate()
        publishTrip(force = true)
    }

    fun simTrafficJam(minutes: Int) {
        simSource.addDelay(minutes * 60)
        log("정체 발생: 도착 +${minutes}분")
        evaluate(); publishTrip(force = true)
    }

    fun simSetRemainMinutes(minutes: Int) {
        simSource.setRemain(minutes * 60)
        evaluate(); publishTrip(force = true)
    }

    fun simNextDestination() {
        val d = simSource.nextDestination()
        log("목적지 변경: ${d.destinationName} (${formatMinutes(d.remainSec)} 남음)")
        evaluate(); publishTrip(force = true)
    }

    /** 데모: 가족 폰 없이 부탁 하나 넣기 (가족이 보낸 것처럼 실시간 DB에 씀) */
    fun receiveSampleErrand() {
        val s = _state.value
        val eta = s.eta ?: return
        val dest = eta.destination ?: return
        val origin = if (s.mode == EtaMode.SIMULATION) SimulatedEtaSource.ORIGIN else (naviOrigin ?: eta.position ?: return)
        val total = if (s.mode == EtaMode.SIMULATION) simSource.totalRouteSec else naviTotalSec
        val progress = if (total > 0) 1.0 - (eta.remainSec.toDouble() / total) else 0.0
        val data = SampleErrands.next(origin, dest, (progress + 0.25).coerceAtMost(0.9), s.clockSec)
        if (s.syncConnected) {
            viewModelScope.launch { sync.post("$tripPath/errands", JSONObject(data)) }
        } else {
            parseErrand("local-${System.currentTimeMillis()}", JSONObject(data))?.let { e ->
                _state.update { it.copy(errands = it.errands + e) }
                log("${e.from}의 부탁 도착: ${e.item} @ ${e.storeName} (운전 중에는 알림만)")
                screenErrands()
            }
        }
    }

    fun setSimSpeed(speed: Int) = _state.update { it.copy(simSpeed = speed) }

    fun toggleDemo() = _state.update { it.copy(showDemo = !it.showDemo) }

    fun setSyncUrl(url: String) {
        val u = url.trim().trimEnd('/')
        prefs.edit().putString("syncUrl", u).apply()
        _state.update { it.copy(syncUrl = u) }
        connectSync(u)
    }

    fun resetDemo() {
        simSource.resetDestination(60 * 60)
        naviOrigin = null
        lastSentOrderAt = null
        screened.clear()
        _state.update {
            UiState(
                presets = it.presets, selectedPresetId = it.selectedPresetId, mode = it.mode,
                simSpeed = it.simSpeed, showDemo = it.showDemo, syncUrl = it.syncUrl,
                syncConnected = it.syncConnected, learned = it.learned, started = it.mode == EtaMode.NAVI,
                autoRejectKm = it.autoRejectKm,
            )
        }
        write {
            sync.delete("$tripPath/order"); sync.delete("$tripPath/errands")
        }
        evaluate()
        publishTrip(force = true)
    }

    // ---------- 실시간 DB ----------

    private fun connectSync(url: String) {
        if (url.isBlank()) sync.disconnect() else sync.connect(url, tripPath)
    }

    /** 실시간 DB 쓰기 (연결이 없으면 화면 상태만 바뀜) */
    private fun write(block: suspend () -> Unit) {
        if (_state.value.syncConnected) viewModelScope.launch { block() }
    }

    private fun onSyncData(root: JSONObject?) {
        if (root == null) {
            if (_state.value.syncConnected) _state.update { it.copy(plan = null, errands = it.errands.filter { e -> e.id.startsWith("local-") }) }
            evaluate()
            return
        }
        val plan = root.optJSONObject("order")?.let { parsePlan(it) }
        val errands = root.optJSONObject("errands")?.let { obj ->
            obj.keys().asSequence().mapNotNull { id -> obj.optJSONObject(id)?.let { parseErrand(id, it) } }
                .sortedBy { it.receivedAtSec ?: 0 }.toList()
        } ?: emptyList()
        val learned = root.optJSONObject("learned")?.let { obj -> obj.keys().asSequence().associateWith { obj.optInt(it) } } ?: emptyMap()
        val before = _state.value
        val newOnes = errands.filter { e -> e.status == ErrandStatus.NEW && before.errands.none { it.id == e.id } }
        _state.update { it.copy(plan = plan, errands = errands, learned = learned) }
        newOnes.forEach { log("${it.from}의 부탁 도착: ${it.item} @ ${it.storeName}") }
        screenErrands()
        if (before.plan?.status != plan?.status) when (plan?.status) {
            OrderStatus.ORDERED -> log("가족이 주문했어요 · 음식 ${plan.foodArrivalClockSec?.let { formatClock(it) } ?: "-"} 도착 예정")
            OrderStatus.ARRIVED -> log("음식 도착 기록 · 실제 ${plan.actualMin ?: "-"}분 (다음 계산에 반영)")
            OrderStatus.PLANNED -> if (before.plan == null) log("배달 타이밍 설정됨: ${plan.menu} (${if (plan.by == "family") "가족이 적음" else "운전자"})")
            else -> Unit
        }
        evaluate()
    }

    private fun parsePlan(o: JSONObject): OrderPlan? {
        val menu = o.optString("menu").ifBlank { return null }
        return OrderPlan(
            menu = menu, store = o.optString("store"), deliveryMin = o.optInt("deliveryMin", 40), bufferMin = o.optInt("bufferMin", 3),
            by = o.optString("by", "family"),
            status = runCatching { OrderStatus.valueOf(o.optString("status", "PLANNED")) }.getOrDefault(OrderStatus.PLANNED),
            orderAtClockSec = o.optLongOrNull("orderAtClockSec"), orderedClockSec = o.optLongOrNull("orderedClockSec"),
            foodArrivalClockSec = o.optLongOrNull("foodArrivalClockSec"), lateMin = o.optInt("lateMin", 0),
            actualMin = if (o.has("actualMin") && !o.isNull("actualMin")) o.optInt("actualMin") else null,
        )
    }

    private fun parseErrand(id: String, o: JSONObject): Errand? {
        val store = o.optJSONObject("store") ?: return null
        return Errand(
            id = id, from = o.optString("from", "가족"), item = o.optString("item"),
            storeName = store.optString("name"), storeAddress = store.optString("address"),
            storePoint = if (store.has("lat")) GeoPoint(store.optDouble("lat"), store.optDouble("lon")) else null,
            extraMin = if (o.has("carExtraMin")) o.optInt("carExtraMin") else o.optInt("extraMin", 5),
            status = runCatching { ErrandStatus.valueOf(o.optString("status", "NEW")) }.getOrDefault(ErrandStatus.NEW),
            receivedAtSec = o.optLongOrNull("sentClockSec") ?: _state.value.clockSec,
            extraKm = if (o.has("extraKm") && !o.isNull("extraKm")) o.optDouble("extraKm") else null,
            autoRejected = o.optBoolean("autoRejected", false),
        )
    }

    private fun planJson(p: OrderPlan) = JSONObject().apply {
        put("menu", p.menu); put("store", p.store); put("deliveryMin", p.deliveryMin); put("bufferMin", p.bufferMin)
        put("by", p.by); put("status", p.status.name); put("createdAt", System.currentTimeMillis())
    }

    private fun setErrandStatus(id: String, status: ErrandStatus, extra: Map<String, Any?>) {
        _state.update { s -> s.copy(errands = s.errands.map { if (it.id == id) it.copy(status = status) else it }) }
        if (!id.startsWith("local-")) write { sync.patch("$tripPath/errands/$id", mapOf("status" to status.name) + extra) }
    }

    /** 이미 거리 계산을 한 부탁 */
    private val screened = mutableSetOf<String>()

    /**
     * 새 부탁이 오면 차가 직접 '들르면 늘어나는 거리'를 계산한다 (GPS 직선거리, 지도 API 없이).
     * 기준을 넘으면 운전자에게 띄우지 않고 자동으로 거절하고, 가족에게 사유를 보낸다.
     */
    private fun screenErrands() {
        val s = _state.value
        val eta = s.eta ?: return
        val dest = eta.destination ?: return
        val me = eta.position ?: (if (s.mode == EtaMode.SIMULATION) SimulatedEtaSource.ORIGIN else null) ?: return
        val updates = mutableMapOf<String, Errand>()
        for (e in s.errands) {
            if (e.status != ErrandStatus.NEW || e.id in screened) continue
            val store = e.storePoint ?: continue
            screened += e.id
            val km = Detour.extraKm(me, store, dest)
            val minutes = Detour.extraMinutes(km)
            val reject = Detour.shouldAutoReject(km, s.autoRejectKm)
            val roundedKm = Math.round(km * 10) / 10.0
            updates[e.id] = e.copy(
                extraKm = roundedKm, extraMin = minutes,
                status = if (reject) ErrandStatus.REJECTED else e.status, autoRejected = reject,
            )
            val fields = mutableMapOf<String, Any?>("extraKm" to roundedKm, "carExtraMin" to minutes)
            if (reject) {
                fields += mapOf("status" to "REJECTED", "autoRejected" to true, "limitKm" to s.autoRejectKm, "rejectedClockSec" to s.clockSec)
                log("자동 거절: ${e.storeName} (들르면 +${fmtKm(roundedKm)}km, 기준 ${fmtKm(s.autoRejectKm ?: 0.0)}km)")
            }
            if (!e.id.startsWith("local-")) write { sync.patch("$tripPath/errands/${e.id}", fields) }
        }
        if (updates.isNotEmpty()) _state.update { st -> st.copy(errands = st.errands.map { updates[it.id] ?: it }) }
    }

    private var lastPublishMs = 0L
    private var lastPublished: String? = null

    /** 운행 상태를 실시간 DB에 올린다 (가족 화면의 도착 시각·지도) */
    private fun publishTrip(force: Boolean = false) {
        val s = _state.value
        if (!s.syncConnected) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < 1_000) return
        val eta = s.eta
        val trip = JSONObject().apply {
            put("status", when { !s.started || eta == null -> "idle"; eta.remainSec == 0 -> "arrived"; else -> "driving" })
            put("parked", s.parked); put("shareOn", s.shareOn); put("clockSec", s.clockSec)
            put("autoRejectKm", s.autoRejectKm ?: JSONObject.NULL)
            if (eta != null) {
                put("remainSec", eta.remainSec)
                put("totalSec", if (s.mode == EtaMode.SIMULATION) simSource.totalRouteSec else naviTotalSec.coerceAtLeast(eta.remainSec))
                put("arrivalClockSec", s.clockSec + eta.remainSec)
                put("waypointCount", eta.waypointCount)
                val origin = if (s.mode == EtaMode.SIMULATION) SimulatedEtaSource.ORIGIN else naviOrigin
                val originName = if (s.mode == EtaMode.SIMULATION) SimulatedEtaSource.ORIGIN_NAME else "출발"
                origin?.let { put("origin", JSONObject().put("name", originName).put("lat", it.lat).put("lon", it.lon)) }
                eta.destination?.let {
                    put("dest", JSONObject().put("name", eta.destinationName ?: "목적지").put("address", eta.destinationAddress ?: "").put("lat", it.lat).put("lon", it.lon))
                }
                if (s.shareOn) eta.position?.let { put("pos", JSONObject().put("lat", it.lat).put("lon", it.lon)) }
            }
        }
        val sig = trip.toString()
        if (!force && sig == lastPublished) return
        lastPublished = sig; lastPublishMs = now
        trip.put("updatedAt", now)
        viewModelScope.launch { sync.put("$tripPath/trip", trip) }
    }

    // ---------- 내부 ----------

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val s = _state.value
                if (s.mode == EtaMode.SIMULATION) {
                    if (s.started && !s.arrived) {
                        // 1초에 simSpeed초가 흐른다 (기본 1초 = 1분)
                        _state.update { it.copy(clockSec = it.clockSec + it.simSpeed) }
                        simSource.advance(s.simSpeed)
                    } else if (s.started && s.arrived && s.plan?.status == OrderStatus.ORDERED) {
                        // 도착 뒤에도 음식이 올 때까지는 시계를 흘린다
                        _state.update { it.copy(clockSec = it.clockSec + it.simSpeed) }
                    }
                } else {
                    _state.update { it.copy(clockSec = LocalTime.now().toSecondOfDay().toLong()) }
                }
                evaluate()
                publishTrip()
            }
        }
    }

    private var lastSentOrderAt: Long? = null
    /** 같은 계획에 알림을 두 번 보내지 않도록 (실시간 DB 응답이 늦게 와도) */
    private var alertSentFor: String? = null

    private fun evaluate() {
        val eta = source.eta.value
        if (_state.value.mode == EtaMode.NAVI && eta != null) {
            if (naviOrigin == null) naviOrigin = eta.position
            if (eta.remainSec > naviTotalSec) naviTotalSec = eta.remainSec
        }
        _state.update { it.copy(eta = eta, naviConnected = naviSource.connected.value) }
        screenErrands() // 위치·목적지가 늦게 들어와도 새 부탁을 거리로 걸러냄
        val s = _state.value
        val plan = s.plan ?: run { alertSentFor = null; _state.update { it.copy(decision = null) }; return }

        when (plan.status) {
            OrderStatus.PLANNED -> {
                if (!s.started) { _state.update { it.copy(decision = null) }; return }
                val decision = TimingEngine.decide(eta, plan.deliveryMin * 60, plan.bufferMin * 60)
                _state.update { it.copy(decision = decision) }
                when (decision) {
                    is Decision.Wait -> {
                        val at = s.clockSec + decision.secondsUntilOrder
                        val prev = lastSentOrderAt ?: plan.orderAtClockSec
                        if (prev == null || abs(at - prev) >= 30) {
                            lastSentOrderAt = at
                            _state.update { it.copy(plan = it.plan?.copy(orderAtClockSec = at)) }
                            write { sync.patch("$tripPath/order", mapOf("orderAtClockSec" to at)) }
                        }
                    }
                    Decision.OrderNow -> {
                        val key = "${plan.menu}|${plan.store}|${plan.by}"
                        if (alertSentFor == key) return
                        alertSentFor = key
                        lastSentOrderAt = null
                        _state.update { it.copy(plan = it.plan?.copy(status = OrderStatus.ALERTED, orderAtClockSec = s.clockSec)) }
                        write { sync.patch("$tripPath/order", mapOf("status" to "ALERTED", "alertedClockSec" to s.clockSec, "orderAtClockSec" to s.clockSec)) }
                        log("가족에게 \"지금 주문하세요\" 알림 보냄 (${plan.menu})")
                    }
                    is Decision.Hold -> Unit
                }
            }
            OrderStatus.ORDERED -> {
                // 주문 뒤 정체로 운전자가 음식보다 늦어지면 가족에게 알림 (가게 제휴 후에는 조리 지연 요청으로)
                val food = plan.foodArrivalClockSec ?: return
                val arrival = s.arrivalSec ?: return
                if (eta == null || eta.remainSec == 0) return
                val lateSec = arrival - food
                val lateMin = if (lateSec > 120) ((lateSec + 59) / 60).toInt() else 0
                if (lateMin != plan.lateMin) {
                    _state.update { it.copy(plan = it.plan?.copy(lateMin = lateMin)) }
                    write { sync.patch("$tripPath/order", mapOf("lateMin" to lateMin)) }
                    if (lateMin > plan.lateMin) log("음식보다 ${lateMin}분 늦어요 → 가족에게 알림")
                }
            }
            else -> Unit
        }
    }

    private fun log(msg: String) = _state.update {
        it.copy(log = (listOf("${formatClock(it.clockSec)}  $msg") + it.log).take(30))
    }

    override fun onCleared() {
        source.stop()
        sync.disconnect()
        super.onCleared()
    }

    companion object {
        /** 에뮬레이터에서 본 PC의 중계 서버 주소. Firebase를 쓰면 https://<프로젝트>-default-rtdb.<지역>.firebasedatabase.app */
        const val DEFAULT_SYNC_URL = "http://10.0.2.2:8787"
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

fun storeKey(s: String) = s.trim().replace(Regex("[.#$\\[\\]/\\s]+"), "_")

fun formatClock(sec: Long): String {
    val daySec = ((sec % 86_400) + 86_400) % 86_400
    return "%02d:%02d".format(daySec / 3600, (daySec % 3600) / 60)
}

fun formatMinutes(sec: Int): String = "${(sec + 59) / 60}분"

/** 2.0 → "2", 1.5 → "1.5" */
fun fmtKm(km: Double): String = if (km % 1.0 == 0.0) km.toInt().toString() else "%.1f".format(km)
