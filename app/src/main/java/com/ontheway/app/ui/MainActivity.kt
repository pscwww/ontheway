@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ontheway.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ontheway.app.R
import com.ontheway.app.ui.theme.ExtraColors
import com.ontheway.app.ui.theme.OnTheWayTheme
import com.ontheway.app.engine.Decision
import com.ontheway.app.errand.Errand
import com.ontheway.app.errand.ErrandStatus

/** 색은 테마(ui/theme/Theme.kt)의 역할 이름으로만 쓴다. 아래 둘은 짧게 부르기 위한 별칭 */
private val cs: ColorScheme
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme
private val ext: ExtraColors
    @Composable @ReadOnlyComposable get() = OnTheWayTheme.extra

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnTheWayTheme {
                val state by vm.state.collectAsState()
                MainScreen(state, vm)
            }
        }
    }
}

/*
 * 글자·터치 크기 — Google "Design for Driving" 시각 원칙을 따름
 *   보조 글자 24, 중요한 글자 32, 한눈에 볼 숫자 48 이상 (그보다 작은 글자는 쓰지 않음)
 *   터치 영역 76 × 76dp 이상, 버튼 사이 간격 24dp 이상 (가이드 23dp)
 *   주행 중에는 판단에 필요한 정보만 보이고, 긴 설명·처리 기록·취소 같은 정차 전용 기능은 정차 중에만 보임
 * 데모 컨트롤 패널은 심사 시연용이라 운전자 화면이 아니므로 예외.
 */
private object Size {
    val secondary = 24.sp
    val primary = 32.sp
    val hero = 48.sp
    val heroShort = 72.sp
    val touch = 76.dp
    val gap = 24.dp
    val icon = 28.dp
}

@Composable
private fun T(text: String, size: TextUnit, color: Color = cs.onSurface, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) =
    Text(text, fontSize = size, color = color, fontWeight = weight, modifier = modifier, lineHeight = size * 1.25f)

@Composable
private fun Pill(text: String, bg: Color, fg: Color, bold: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 18.dp, vertical = 8.dp)) {
        T(text, Size.secondary, fg, if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun MainScreen(s: UiState, vm: MainViewModel) {
    Row(
        Modifier.fillMaxSize().background(cs.background).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(cs.surface)
                .padding(horizontal = 36.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Size.gap),
        ) {
            Header(s, vm)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Size.gap)) {
                OrderCard(s, vm, Modifier.weight(1f).fillMaxHeight())
                ErrandCard(s, vm, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (s.showDemo) DemoPanel(s, vm, Modifier.width(320.dp).fillMaxHeight())
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.height(Size.touch).widthIn(min = Size.touch),
    shape = RoundedCornerShape(18.dp),
    border = BorderStroke(2.dp, cs.outline),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.sub2),
    contentPadding = PaddingValues(horizontal = 24.dp),
) { T(text, Size.secondary, ext.sub2, FontWeight.SemiBold) }

@Composable
private fun Header(s: UiState, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 앱 아이콘과 같은 그림 (길 위의 자동차 + 상자)
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "오는 길에 아이콘", modifier = Modifier.requiredSize(92.dp))
        }
        Spacer(Modifier.width(18.dp))
        T("오는 길에", Size.primary, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        val dest = s.eta?.destinationName ?: "목적지"
        val text = when {
            !s.syncConnected -> "가족 연결 대기"
            s.eta == null -> "가족 연결됨 · 길안내 대기"
            !s.started -> "가족 연결됨 · 출발 전"
            s.arrived -> "$dest 도착"
            else -> "가족과 공유 중 · ${s.arrivalSec?.let { formatClock(it) } ?: "-"} 도착"
        }
        Row(
            Modifier.height(Size.touch).clip(RoundedCornerShape(999.dp)).background(cs.surfaceContainer).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (s.syncConnected) cs.tertiary else cs.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            T(text, Size.secondary)
        }
        Spacer(Modifier.width(Size.gap))
        var showSettings by remember { mutableStateOf(false) }
        HeaderButton("설정") { showSettings = true }
        if (showSettings) SettingsDialog(s, vm) { showSettings = false }
        Spacer(Modifier.width(Size.gap))
        HeaderButton(if (s.showDemo) "데모 숨기기" else "데모", vm::toggleDemo)
    }
}

@Composable
private fun CardTitle(title: String, color: Color, pill: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(title, 28.sp, color, FontWeight.Bold, Modifier.weight(1f))
        pill()
    }
}

// ---------------- ① 배달 타이밍 ----------------

@Composable
private fun OrderCard(s: UiState, vm: MainViewModel, modifier: Modifier) {
    val plan = s.plan
    val alerted = plan?.status == OrderStatus.ALERTED
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
            .then(if (alerted) Modifier.border(3.dp, cs.primary, RoundedCornerShape(24.dp)) else Modifier)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(Size.gap),
    ) {
        CardTitle("① 배달 타이밍", ext.amberText) {
            when (plan?.status) {
                null -> Pill("설정 전", cs.surfaceVariant, cs.onSurfaceVariant)
                OrderStatus.PLANNED -> Pill("알림 대기", cs.surfaceVariant, cs.onSurfaceVariant)
                OrderStatus.ALERTED -> Pill("가족에게 알림", cs.primary, cs.onPrimary, bold = true)
                OrderStatus.ORDERED -> Pill("주문 완료", cs.primary, cs.onPrimary, bold = true)
                OrderStatus.ARRIVED -> Pill("음식 받음", cs.tertiary, cs.onTertiary, bold = true)
            }
        }
        if (plan == null) PlanPicker(s, vm) else PlanStatus(s, vm, plan)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlanPicker(s: UiState, vm: MainViewModel) {
    T("가족이 적은 메뉴가 여기에 떠요", Size.secondary, ext.sub2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Size.gap), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        s.presets.forEach { p ->
            val sel = p.id == s.selectedPresetId
            FilterChip(
                selected = sel,
                onClick = { vm.selectPreset(p.id) },
                modifier = Modifier.height(Size.touch),
                shape = RoundedCornerShape(18.dp),
                leadingIcon = if (sel) ({ CheckIcon(cs.onPrimary, Size.icon) }) else null,
                label = { T(p.menu, Size.secondary, if (sel) cs.onPrimary else ext.sub2, FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(containerColor = cs.surfaceVariant, selectedContainerColor = cs.primary),
                border = null,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    BigButton(
        text = if (s.parked) "도착에 맞춰 알려 주기" else "정차하면 정할 수 있어요",
        bg = cs.primary, fg = cs.onPrimary, enabled = s.parked, onClick = vm::planFromPreset,
        modifier = Modifier.fillMaxWidth(),
    )
    if (s.parked) T("때가 되면 가족 폰에 \"지금 주문하세요\"가 떠요", Size.secondary, cs.onSurfaceVariant)
    else LockLine("주행 중 잠김 · 정차하면 눌러요")
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlanStatus(s: UiState, vm: MainViewModel, plan: OrderPlan) {
    Column {
        T(plan.menu, Size.primary, weight = FontWeight.Bold)
        T("${plan.store} · 보통 ${plan.deliveryMin}분", Size.secondary, cs.onSurfaceVariant)
    }
    OrderBigNumber(s, plan)
    Timeline(s, plan)
    val late = plan.status == OrderStatus.ORDERED && plan.lateMin > 0
    T(
        when (plan.status) {
            OrderStatus.PLANNED -> "도착이 바뀌면 알림도 자동으로 맞춰요"
            OrderStatus.ALERTED -> "가족이 주문하면 여기에 표시돼요"
            OrderStatus.ORDERED -> if (late) "음식보다 ${plan.lateMin}분 늦어요 · 가족에게 알렸어요" else "음식보다 조금 먼저 도착해요"
            OrderStatus.ARRIVED -> "실제 ${plan.actualMin ?: "-"}분 걸림 · 다음부터 반영해요"
        },
        Size.secondary, if (late) cs.error else ext.sub2, if (late) FontWeight.Bold else FontWeight.Normal,
    )
    Spacer(Modifier.weight(1f))
    // 취소는 정차 전용 기능이라 주행 중에는 숨김
    if (plan.status == OrderStatus.PLANNED && s.parked) {
        OutlinedButton(
            onClick = vm::cancelPlan,
            modifier = Modifier.height(Size.touch).widthIn(min = 160.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, cs.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
        ) { T("알림 취소", Size.secondary, weight = FontWeight.SemiBold) }
    }
}

@Composable
private fun OrderBigNumber(s: UiState, plan: OrderPlan) {
    val (label, value, color) = when (plan.status) {
        OrderStatus.PLANNED -> when {
            !s.started -> Triple("주문 알림", "출발하면 계산", ext.sub2)
            s.decision is Decision.Hold -> Triple("주문 알림", "목적지를 정해 주세요", ext.sub2)
            s.alertAtSec != null -> Triple("주문 알림까지", formatMinutes((s.alertAtSec!! - s.clockSec).toInt().coerceAtLeast(0)), ext.amberText)
            else -> Triple("계산 중", "…", ext.sub2)
        }
        OrderStatus.ALERTED -> Triple("가족 폰에", "지금 주문하세요!", ext.amberText)
        OrderStatus.ORDERED -> Triple(if (s.arrived) "${s.eta?.destinationName ?: "목적지"} 도착!" else "같이 도착해요", "음식 ${plan.foodArrivalClockSec?.let { formatClock(it) } ?: "-"}", ext.amberText)
        OrderStatus.ARRIVED -> Triple("완료", "맛있게 드세요", cs.tertiary)
    }
    Column {
        T(label, Size.secondary, cs.onSurfaceVariant)
        T(value, if (value.length <= 4) Size.heroShort else Size.hero, color, FontWeight.ExtraBold)
    }
}

@Composable
private fun Timeline(s: UiState, plan: OrderPlan) {
    val now = s.clockSec
    val alertAt = plan.orderedClockSec ?: s.alertAtSec
    val me = s.arrivalSec
    val food = s.foodArrivalSec
    val end = listOfNotNull(me, food).maxOrNull()
    val start = alertAt?.coerceAtMost(now) ?: now
    val progress = if (end != null && end > start) ((now - start).toFloat() / (end - start)).coerceIn(0.03f, 1f) else 0.03f
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(cs.outlineVariant)) {
            Box(Modifier.fillMaxWidth(progress).height(10.dp).clip(RoundedCornerShape(999.dp)).background(cs.primary))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeCell("지금", formatClock(now), cs.onSurface, Modifier.weight(1f))
            TimeCell(if (plan.orderedClockSec != null) "주문" else "알림", alertAt?.let { formatClock(it) } ?: "-", ext.amberText, Modifier.weight(1f))
            TimeCell("내 도착", if (s.arrived) "도착" else me?.let { formatClock(it) } ?: "-", cs.onSurface, Modifier.weight(1f))
            TimeCell("음식", food?.let { formatClock(it) } ?: "-", cs.onSurface, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeCell(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        T(label, Size.secondary, cs.onSurfaceVariant)
        T(value, Size.primary, color, FontWeight.Bold)
    }
}

// ---------------- ② 가족 부탁 ----------------

@Composable
private fun ErrandCard(s: UiState, vm: MainViewModel, modifier: Modifier) {
    val newOnes = s.errands.filter { it.status == ErrandStatus.NEW }
    val current = newOnes.firstOrNull()
    val done = s.errands.filter { it.status == ErrandStatus.ACCEPTED || it.status == ErrandStatus.REJECTED }
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
            .then(if (current != null) Modifier.border(3.dp, cs.secondary, RoundedCornerShape(24.dp)) else Modifier)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(Size.gap),
    ) {
        CardTitle("② 가족 부탁", cs.secondary) {
            if (newOnes.isNotEmpty()) Pill("새 부탁 ${newOnes.size}", cs.secondary, cs.onSecondary, bold = true)
        }

        if (current == null) {
            T(if (done.isEmpty()) "받은 부탁이 없어요" else "새 부탁이 없어요", Size.primary, ext.sub2, FontWeight.Bold)
            T(s.autoRejectKm?.let { "+${fmtKm(it)}km 넘게 돌아가면 자동 거절" } ?: "자동 거절 꺼짐", Size.secondary, cs.onSurfaceVariant)
            if (s.parked) T("가족이 경로 근처 가게를 골라 보내면 여기에 떠요", Size.secondary, cs.onSurfaceVariant)
        } else {
            ErrandDetail(s, vm, current)
        }

        // 처리 기록은 정차 중에만 (주행 중에는 판단에 필요 없는 정보)
        if (done.isNotEmpty() && s.parked) {
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("처리한 부탁", Size.secondary, cs.onSurfaceVariant)
                done.takeLast(if (current == null) 3 else 2).forEach { e ->
                    when {
                        e.status == ErrandStatus.ACCEPTED -> IconLine({ CheckIcon(cs.secondary, Size.icon) }, "${e.item} → ${e.storeName}", cs.secondary)
                        e.autoRejected -> IconLine({ CloseIcon(cs.error, Size.icon) }, "자동 거절 · ${e.item} (+${e.extraKm?.let { fmtKm(it) } ?: "-"}km)", cs.error)
                        else -> IconLine({ CloseIcon(cs.onSurfaceVariant, Size.icon) }, "거절 · ${e.item}", cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrandDetail(s: UiState, vm: MainViewModel, e: Errand) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            T(e.from.take(1), 28.sp, cs.secondary, FontWeight.Bold)
        }
        Spacer(Modifier.width(18.dp))
        Column {
            T("${e.from} · ${e.receivedAtSec?.let { formatClock(it) } ?: ""}", Size.secondary, cs.onSurfaceVariant)
            T("\"${e.item} 사 와\"", Size.primary, weight = FontWeight.Bold)
        }
    }
    val newArrival = s.arrivalSec?.let { formatClock(it + e.extraMin * 60) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceVariant).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(e.storeName, 28.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            T("+${e.extraMin}분", Size.primary, cs.secondary, FontWeight.ExtraBold)
        }
        T("들르면 ${newArrival ?: "-"} 도착${e.extraKm?.let { " · +${fmtKm(it)}km" } ?: ""}", Size.secondary, ext.sub2)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Size.gap)) {
        BigButton(
            text = if (s.parked) "수락" else "정차 시 수락",
            bg = cs.secondary, fg = cs.onSecondary, enabled = s.parked && e.storePoint != null,
            onClick = { vm.acceptErrand(e.id) }, modifier = Modifier.weight(2f),
        )
        OutlinedButton(
            onClick = { vm.rejectErrand(e.id) }, enabled = s.parked,
            modifier = Modifier.weight(1f).height(Size.touch),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, cs.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
        ) { T("거절", 28.sp, weight = FontWeight.Bold) }
    }
    if (s.parked) T("누르면 가족에게 새 도착 시각이 가요", Size.secondary, cs.onSurfaceVariant)
    else LockLine("주행 중 잠김 · 정차하면 눌러요")
}

@Composable
private fun SettingsDialog(s: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainer,
        title = { T("설정", Size.primary, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                T("가족 부탁 자동 거절", 28.sp, weight = FontWeight.Bold)
                T("들르면 이만큼 넘게 돌아가는 부탁은 묻지 않고 거절해요", Size.secondary, ext.sub2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Size.gap), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf<Double?>(null, 1.0, 2.0, 3.0, 5.0).forEach { km ->
                        val selected = s.autoRejectKm == km
                        FilterChip(
                            selected = selected, enabled = s.parked,
                            onClick = { vm.setAutoRejectKm(km) },
                            modifier = Modifier.height(Size.touch).widthIn(min = 96.dp),
                            shape = RoundedCornerShape(18.dp),
                            label = { T(km?.let { "${fmtKm(it)}km" } ?: "끄기", Size.secondary, if (selected) cs.onSecondary else ext.sub2, FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = cs.surfaceVariant, selectedContainerColor = cs.secondary),
                            border = null,
                        )
                    }
                }
                T("거리 기준: GPS 직선거리로 계산한 늘어나는 거리", Size.secondary, cs.onSurfaceVariant)
                if (!s.parked) LockLine("정차 중에만 바꿀 수 있어요")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(Size.touch).widthIn(min = 120.dp)) {
                T("닫기", Size.secondary, cs.secondary, FontWeight.Bold)
            }
        },
    )
}

// ---------------- 아이콘 (글자 기호 대신 벡터로 그림, 같은 굵기로 통일) ----------------

@Composable
private fun CheckIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size).semantics { contentDescription = "완료" }) {
    val w = this.size.width
    val p = Path().apply { moveTo(w * .2f, w * .53f); lineTo(w * .42f, w * .74f); lineTo(w * .8f, w * .3f) }
    drawPath(p, color, style = Stroke(w * .13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun CloseIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size).semantics { contentDescription = "거절" }) {
    val w = this.size.width
    val st = Stroke(w * .13f, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(w * .27f, w * .27f); lineTo(w * .73f, w * .73f); moveTo(w * .73f, w * .27f); lineTo(w * .27f, w * .73f) }, color, style = st)
}

@Composable
private fun LockIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size).semantics { contentDescription = "잠김" }) {
    val w = this.size.width
    val st = Stroke(w * .11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * .2f, w * .45f), size = androidx.compose.ui.geometry.Size(w * .6f, w * .42f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = st)
    drawPath(Path().apply { moveTo(w * .33f, w * .45f); lineTo(w * .33f, w * .32f); cubicTo(w * .33f, w * .1f, w * .67f, w * .1f, w * .67f, w * .32f); lineTo(w * .67f, w * .45f) }, color, style = st)
}

@Composable
private fun IconLine(icon: @Composable () -> Unit, text: String, color: Color, size: TextUnit = Size.secondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        T(text, size, color)
    }
}

@Composable
private fun LockLine(text: String) = IconLine({ LockIcon(ext.amberText, Size.icon) }, text, ext.sub2)

@Composable
private fun BigButton(text: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = Size.touch) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = fg,
            disabledContainerColor = cs.surfaceVariant, disabledContentColor = cs.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
}

// ---------------- 데모 컨트롤 ----------------

@Composable
private fun DemoPanel(s: UiState, vm: MainViewModel, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(cs.surface).padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        T("데모 컨트롤", 20.sp, weight = FontWeight.Bold)
        ToggleRow("실제 내비 사용", s.mode == EtaMode.NAVI) { vm.setMode(if (it) EtaMode.NAVI else EtaMode.SIMULATION) }
        ToggleRow("정차 중 (수락 가능)", s.parked, vm::setParked)
        ToggleRow("가족에게 위치 공유", s.shareOn, vm::setShareOn)
        if (s.mode == EtaMode.SIMULATION) {
            DemoButton(if (s.started) "일시정지" else "출발 (가족과 공유 시작)", accent = !s.started, onClick = vm::toggleStarted)
            T("시간 배속 (1초 = ${s.simSpeed}초)", 15.sp, cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 30, 60, 120).forEach { v ->
                    FilterChip(
                        selected = s.simSpeed == v, onClick = { vm.setSimSpeed(v) }, modifier = Modifier.height(48.dp),
                        label = { T("x$v", 14.sp, if (s.simSpeed == v) cs.background else ext.sub2) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = cs.surfaceVariant, selectedContainerColor = ext.sub2),
                        border = null,
                    )
                }
            }
            DemoButton("남은 시간 60분으로") { vm.simSetRemainMinutes(60) }
            DemoButton("정체 +10분") { vm.simTrafficJam(10) }
            DemoButton("목적지 바꾸기 (지금: ${s.eta?.destinationName ?: "-"})", onClick = vm::simNextDestination)
        }
        DemoButton("샘플 부탁 넣기 (가족 대신)", accent = true, onClick = vm::receiveSampleErrand)
        DemoButton("처음부터", onClick = vm::resetDemo)

        Spacer(Modifier.height(6.dp))
        T("가족 연결 서버", 16.sp, weight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (s.syncConnected) cs.tertiary else cs.error))
            Spacer(Modifier.width(8.dp))
            T(if (s.syncConnected) "실시간 연결됨" else "연결 안 됨 (화면만 동작)", 14.sp, cs.onSurfaceVariant)
        }
        var url by remember(s.syncUrl) { mutableStateOf(s.syncUrl) }
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = cs.secondary, unfocusedBorderColor = cs.outline, cursorColor = cs.secondary),
            modifier = Modifier.fillMaxWidth(),
        )
        DemoButton("이 주소로 연결") { vm.setSyncUrl(url) }

        Spacer(Modifier.height(6.dp))
        T("기록", 16.sp, weight = FontWeight.Bold)
        s.log.take(12).forEach { T(it, 13.sp, cs.onSurfaceVariant) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label, 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = cs.secondary, checkedThumbColor = cs.onSecondary),
        )
    }
}

@Composable
private fun DemoButton(text: String, accent: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, if (accent) cs.secondary else cs.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (accent) cs.secondary else ext.sub2),
    ) { T(text, 15.sp, if (accent) cs.secondary else ext.sub2) }
}
