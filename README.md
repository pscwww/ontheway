# 오는 길에 (On the Way)

내비 도착 시간을 목적지의 가족과 실시간으로 나누는 Pleos Connect 차량 앱입니다.
기다리는 가족에게 "지금 배달을 시키면 도착에 딱 맞는" 순간을 알려주고(주문은 가족이 평소 쓰는 배달앱으로),
가족이 지도에서 고른 "오는 길에 사 올 가게"를 운전자가 정차 중 한 번에 수락해 경유지로 넣습니다.
목적지는 집이 아니어도 됩니다 (부모님 댁, 캠핑장 등). 가게 제휴 없이 동작하며, 자동 주문·조리 지연은 제휴 후 2단계입니다.

## 구성

- `app/` — Pleos 차량 앱 (Kotlin, Compose)
- `web/` — 가족 웹페이지 (앱 설치 없이 링크로 열기)
- `relay/` — 데모용 중계 서버. Firebase Realtime Database와 같은 방식(REST + 실시간 스트림)이라 주소만 바꾸면 Firebase로 옮겨져요.

## 실행 방법

1. **중계 서버 켜기** (Node 18 이상, 설치할 패키지 없음)
   ```
   node relay/server.js
   ```
   창에 PC 주소와 폰 주소가 나와요. 처음 실행할 때 Windows 방화벽 창이 뜨면 '허용'을 눌러야 폰에서 들어올 수 있어요.
2. **가족 화면 열기**: PC 브라우저에서 `http://localhost:8787`, 같은 와이파이의 폰에서는 창에 나온 `http://<PC IP>:8787`.
3. **차 앱 실행**: Android Studio에서 이 폴더를 열고, Device Manager에서 `Pleos Connect v2.1.0` AVD를 **Cold boot**로 켠 뒤 ▶ Run 'app'.
   차 앱은 기본으로 `http://10.0.2.2:8787`(에뮬레이터에서 본 PC)에 붙어요. 오른쪽 데모 패널 아래 '가족 연결 서버'에서 바꿀 수 있어요.
4. **시연 순서**
   - 가족 화면: ① 배달 타이밍에 메뉴·가게·보통 걸리는 시간을 적고 "도착에 맞춰 알려주세요"
   - 차 데모 패널: "출발 (가족과 공유 시작)" → 가족 화면에 도착 시각과 지도가 떠요
   - 가족 화면: ② 부탁하기에서 경로 근처 가게를 골라 보내기 (+분 표시)
   - 차 데모 패널: "정차 중" 켜기 → 부탁 카드에서 [수락] → 경유지 추가, 가족 화면에 "○○ 들러서 19:34 도착"
   - 주문 알림 시각이 되면 가족 화면에 "지금 주문하세요!"(소리·진동) → [주문했어요] → 차 화면에 주문 완료
   - "정체 +10분" → 주문 전이면 알림 시각이 밀리고, 주문 후면 가족에게 "늦어요" 안내
   - "목적지 바꾸기" → 집·부모님 댁·캠핑장 어디든 같은 방식
   - 설정에서 자동 거절 기준(예: 2km)을 정하면, 들르면 그보다 많이 돌아가는 부탁은 묻지 않고 거절돼요
   - 낮/밤 화면 전환: `adb shell cmd uimode night yes` / `no` (차 시스템 설정을 그대로 따라요)

### Firebase로 옮기기

1. Firebase 콘솔에서 Realtime Database를 만들어요 (데모는 테스트 모드 규칙).
2. 차 앱 데모 패널의 '가족 연결 서버'에 `https://<프로젝트>-default-rtdb.<지역>.firebasedatabase.app` 입력 → "이 주소로 연결".
3. 가족 웹페이지는 Firebase Hosting 등에 올리고 주소 뒤에 `?db=<같은 주소>`를 붙여 열어요.

명령줄 빌드:

```
gradlew testDebugUnitTest assembleDebug
gradlew assembleRelease   # app/build/outputs/apk/release/
```

## 테스트 환경

- Android Studio, JDK 17 이상 (Android Studio 내장 JBR 사용 가능)
- Pleos Connect Emulator 2.1.0 (`system-images;android-34;pleos_car_emulator;x86_64`)
- minSdk 28, targetSdk 35, Kotlin 1.9.22, AGP 8.8.0, Gradle 8.10.2

## 화면 설계

- **차 화면**: Google 차량 UI 가이드(Design for Driving)를 따랐어요. 보조 글자 24dp, 중요한 글자 32dp 이상, 터치 영역 76×76dp 이상, 버튼 간격 24dp, 글자 대비 4.5:1 이상.
  주행 중에는 판단에 필요한 정보만 보이고, 수락·거절 같은 조작은 정차 중에만 돼요.
- **낮/밤**: 앱이 시계를 보지 않고 차 시스템의 낮/밤 설정(`isSystemInDarkTheme()`)을 따라요. 색은 Material 3 테마(`ui/theme/Theme.kt`)에 역할별로 모았어요.
- **가족 화면**: 역 안내판 느낌의 Wayfind 테마([Hallmark](https://github.com/nutlope/hallmark)). 포인트 색(주황·청록)은 차 화면과 같아요. 폰이 다크 모드면 자동으로 어두워져요.

## 구조

| 경로 | 내용 |
| --- | --- |
| `engine/TimingEngine.kt` | 주문 알림 시점 계산 (알림까지 = 남은 시간 + 여유 − 배달 시간) |
| `engine/EtaSource.kt` | 도착 시간·위치 소스 인터페이스, 데모용 시뮬레이션 |
| `navi/NaviHelperEtaSource.kt` | Pleos NaviHelper SDK 연동 (목적지 이름·주소·좌표, 남은 시간, 현재 위치, 경유지 추가) |
| `sync/RtdbClient.kt` | 실시간 DB 클라이언트 (Firebase REST + 스트림 방식, 구글 Play 서비스 불필요) |
| `errand/Errands.kt` | 가족 부탁 (가족이 고른 가게, 수락·거절) |
| `delivery/DeliveryApi.kt` | 2단계 가게 연동 자리 (지금은 메뉴 예시) |
| `engine/Detour.kt` | 들르면 늘어나는 거리 계산(GPS 직선거리)과 자동 거절 판단 |
| `ui/` | 차 화면(Compose)과 상태 관리 |
| `ui/theme/Theme.kt` | 낮/밤 색 테마 (Material 3) |
| `web/index.html` | 가족 화면: 도착·지도, 배달 타이밍 알림, 부탁 보내기 |
| `relay/server.js` | 데모용 중계 서버 |

## 흉내로 대신한 부분

- 경로 근처 가게 목록 (예시 가게. 실제 장소 검색으로 바꿀 자리). '+분'과 자동 거절은 GPS 좌표로 실제 계산해요
- 가게 자동 주문·조리 지연 (2단계, 제휴 후)
- 주차·정차 판단 (데모 스위치. 실제로는 AAOS `GEAR_SELECTION`)

## 사용한 라이브러리

- AndroidX, Jetpack Compose, Kotlin Coroutines — Apache License 2.0
- 가족 웹페이지·중계 서버는 외부 라이브러리 없이 작성
- 글꼴 Chivo Mono, 나눔고딕코딩 — SIL Open Font License 1.1 (`web/fonts/OFL-*.txt`)
- JUnit 4 — Eclipse Public License 1.0
- Pleos NaviHelper SDK 3.0.5 — Pleos Connect SDK License (42dot)
