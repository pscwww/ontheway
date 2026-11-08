package com.ontheway.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 오는 길에 — 테마
 *
 * 색은 Material 3 표준 색 체계(ColorScheme)에 넣는다. 화면 코드에서는
 * MaterialTheme.colorScheme.primary 같은 역할 이름으로만 쓰고 색 값을 직접 적지 않는다.
 *
 *   역할                 쓰임                                  밤          낮
 *   primary              주황 면 (주문 버튼, 진행 막대, 강조 테두리)   #FFB547     #FFB547
 *   secondary            청록 (수락 버튼, 부탁 카드, 스위치)          #4FD8C6     #0F7F72
 *   tertiary             초록 (연결됨, 완료)                        #5BD6A0     #15803D
 *   error                빨강 (자동 거절, 늦음)                      #FF8A65     #C2410C
 *   background           화면 바탕                                #0B0E12     #E4E8EE
 *   surface              큰 패널                                  #12161C     #F1F3F6
 *   surfaceContainer     카드                                    #1A2029     #FFFFFF
 *   surfaceVariant       카드 안 칸, 꺼진 버튼                      #232A35     #EEF1F5
 *   onSurface            본문 글자                                #F2F4F7     #12161C
 *   onSurfaceVariant     보조 글자                                #9AA4B2     #556070
 *   outline              테두리 (카드 위 3:1 이상)                  #6B7789     #7A8597
 *
 * 표준 역할에 없는 두 가지는 ExtraColors로 따로 둔다.
 *   amberText  주황 글자·아이콘. 낮에는 흰 바탕에서 읽히도록 진하게 (#9A5700, 대비 5.6:1)
 *   sub2       본문과 보조 사이 밝기의 설명 글자
 *
 * 낮/밤은 앱이 시계를 보지 않고 차 시스템 설정(uiMode)을 따른다 — isSystemInDarkTheme().
 * 그래서 터널에서 차 화면이 밤 모드가 되면 이 앱도 같이 바뀐다.
 */

private val NightColors = darkColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFF1A1206),
    secondary = Color(0xFF4FD8C6), onSecondary = Color(0xFF06201D),
    secondaryContainer = Color(0xFF233A3A), onSecondaryContainer = Color(0xFF4FD8C6),
    tertiary = Color(0xFF5BD6A0), onTertiary = Color(0xFF06201D),
    error = Color(0xFFFF8A65), onError = Color(0xFF2A0E05),
    background = Color(0xFF0B0E12), onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF12161C), onSurface = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFF1A2029), surfaceContainerHigh = Color(0xFF1A2029),
    surfaceVariant = Color(0xFF232A35), onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF6B7789), outlineVariant = Color(0xFF2A323D),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFF2A1A00),
    secondary = Color(0xFF0F7F72), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF1EE), onSecondaryContainer = Color(0xFF0B5E54),
    tertiary = Color(0xFF15803D), onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFC2410C), onError = Color(0xFFFFFFFF),
    background = Color(0xFFE4E8EE), onBackground = Color(0xFF12161C),
    surface = Color(0xFFF1F3F6), onSurface = Color(0xFF12161C),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F5), onSurfaceVariant = Color(0xFF556070),
    outline = Color(0xFF7A8597), outlineVariant = Color(0xFFD5DBE3),
)

@Immutable
data class ExtraColors(val amberText: Color, val sub2: Color)

private val NightExtra = ExtraColors(amberText = Color(0xFFFFB547), sub2 = Color(0xFFC3CAD4))
private val DayExtra = ExtraColors(amberText = Color(0xFF9A5700), sub2 = Color(0xFF364150))

private val LocalExtraColors = staticCompositionLocalOf { NightExtra }

object OnTheWayTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

@Composable
fun OnTheWayTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) NightExtra else DayExtra) {
        MaterialTheme(colorScheme = if (dark) NightColors else DayColors, content = content)
    }
}
