# ENH-007: 수동 사진 로딩 (#3) + 사진 전체화면 (#4)

## 상태 범례
- ✅ 구현 완료
- 🔲 미구현 (계획)

---

## 선행 분석 요약

### #5 알림 — 이미 구현됨
`ScanForegroundService.showCompletedNotification()`, `KakaoImportService.showCompletedNotification()` 모두 존재하며 호출됨.  
`POST_NOTIFICATIONS` 런타임 권한 요청도 `MainActivity`에서 앱 시작 시 처리됨 (Android 13+).  
→ **TODO #5 는 그냥 체크만 하면 됨. 구현 불필요.**

---

## 1. 수동 사진 로딩 (#3)

### 현재 상태
- `ScanForegroundService`: 사진 스캔 전체 파이프라인 완성 (진행 알림, 완료 알림 포함)
- 현재 트리거: `InitViewModel.startScanning()` — 앱 초기 세팅 시에만 호출됨
- `SettingsMenuDialog` (TimelineScreen.kt:1272): 항목 2개 (재시도, 초기화). 수동 스캔 버튼 없음

### 변경 내용

#### `TimelineScreen.kt` — SettingsMenuDialog 시그니처에 `onScan` 추가

```
현재:
  SettingsMenuDialog(pendingRetryCount, onRetry, onReset, onDismiss)

변경:
  SettingsMenuDialog(pendingRetryCount, onRetry, onScan, isScanRunning, onReset, onDismiss)
```

버튼 위치: 재시도 버튼 아래, 초기화 버튼 위  
버튼 텍스트: `"신규 사진 로딩"`  
아이콘: `Icons.Default.PhotoLibrary`  
스캔 진행 중일 때(`isScanRunning == true`): 버튼 비활성화 + 텍스트 `"사진 분석 중..."` 로 변경

HorizontalDivider로 초기화 버튼과 구분.

#### `TimelineViewModel.kt` — `startManualScan()` 추가

```kotlin
fun startManualScan() {
    val intent = Intent(context, ScanForegroundService::class.java).apply {
        action = ScanForegroundService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}
```

`isScanRunning` 상태는 `ScanStateHolder` 를 observe해서 노출 (이미 ViewModel에서 import 상태 observe하는 패턴과 동일).

#### 호출부 (TimelineScreen.kt:265)

```kotlin
SettingsMenuDialog(
    ...
    onScan = viewModel::startManualScan,
    isScanRunning = state.isScanRunning,
    ...
)
```

### 변경 파일
| 파일 | 변경 내용 |
|------|-----------|
| `TimelineScreen.kt` | SettingsMenuDialog에 `onScan`, `isScanRunning` 파라미터 추가 + 버튼 UI |
| `TimelineViewModel.kt` | `startManualScan()` + `isScanRunning` state 노출 |
| `TimelineUiState.kt` (또는 State 정의 위치) | `isScanRunning: Boolean` 필드 추가 |

---

## 2. 사진 전체화면 + 슬라이드 (#4)

### 현재 상태
- `DailyEventCard` (TimelineScreen.kt:754): 카드 전체 클릭 → `DailyDetailDialog` 열림. 개별 사진에 클릭 핸들러 없음
- `DailyDetailDialog` (TimelineScreen.kt:855): 3열 그리드, 개별 사진 `combinedClickable` 있으나 전체화면 없음
- 전체화면 뷰어: 없음

### 변경 내용

#### `FullscreenPhotoViewer` composable 신규 추가 (TimelineScreen.kt 하단)

```
┌──────────────────────────────────────────┐
│ ×                              2 / 5     │  ← 닫기(좌상단) / 인덱스(우상단)
│                                          │
│                                          │
│          [사진 꽉차게 표시]              │
│                                          │
│                                          │
└──────────────────────────────────────────┘
```

- 배경: `Color.Black`
- `HorizontalPager` (Compose Foundation) — 좌우 스와이프로 같은 날 사진 이동
- 이미지: `AsyncImage` + `ContentScale.Fit` (비율 유지)
- 상단 좌: 닫기 버튼 (`Icons.Default.Close`, 흰색)
- 상단 우: `"N / 전체"` 텍스트 (흰색)
- 외부 영역 클릭 시 닫기 없음 (닫기 버튼만으로 종료)

파라미터:
```kotlin
@Composable
fun FullscreenPhotoViewer(
    photos: List<String>,   // URI 목록 (Event.content)
    initialIndex: Int,
    onDismiss: () -> Unit
)
```

#### `DailyEventCard` — 개별 사진 탭 연결

현재 카드의 `clickable(onClick = onClick)` 은 상세 다이얼로그용으로 유지.  
개별 사진 `Box`에 `.clickable { onPhotoClick(idx) }` 추가 (이벤트 전파 차단: `stopPropagation`).

`DailyEventCard` 시그니처에 `onPhotoClick: (index: Int) -> Unit` 추가.

#### `DailyDetailDialog` — 그리드 사진 탭도 전체화면 연결

기존 `combinedClickable`의 `onClick`을 전체화면 오픈으로 변경 (선택 모드 아닐 때).

#### 상태 관리 (TimelineScreen)

```kotlin
var fullscreenPhotos by remember { mutableStateOf<List<String>?>(null) }
var fullscreenIndex by remember { mutableStateOf(0) }
```

사진 탭 시 → `fullscreenPhotos = dayPhotoUris`, `fullscreenIndex = tappedIdx`  
`FullscreenPhotoViewer` → `fullscreenPhotos != null` 일 때 표시.

### 변경 파일
| 파일 | 변경 내용 |
|------|-----------|
| `TimelineScreen.kt` | `FullscreenPhotoViewer` composable 추가, `DailyEventCard` 사진 클릭 핸들러 추가, `DailyDetailDialog` 사진 탭 전체화면 연결, 상태 변수 추가 |

---

## 3. 구현 순서

1. `FullscreenPhotoViewer` composable 작성 (독립적, 먼저 완성 가능)
2. `DailyEventCard` 개별 사진 클릭 + 상태 연결
3. `DailyDetailDialog` 사진 탭 연결
4. `TimelineViewModel.startManualScan()` + `isScanRunning` 상태 추가
5. `SettingsMenuDialog` UI 수정 + 호출부 연결
6. TODO.md: #3, #4, #5 체크
