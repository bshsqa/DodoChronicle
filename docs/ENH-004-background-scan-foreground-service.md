# ENH-004: 초기화 스캔 백그라운드 지속 실행 (Foreground Service 도입)

## 1. 개요

현재 초기화 마법사의 사진 스캔(`performScan`)은 `InitViewModel.startScanning()`에서
`viewModelScope.launch(Dispatchers.IO)`로 실행됩니다.

`viewModelScope`는 ViewModel이 살아있는 동안 이론적으로 취소되지 않지만,
앱이 **백그라운드로 이동하면 실제로는 스캔이 멈춥니다.** 원인은 다음과 같습니다.

| 원인 | 설명 |
|---|---|
| **Foreground Service 없음** | Android 8.0(API 26)+ 에서 Foreground Service 없이 실행되는 백그라운드 앱의 CPU 사용은 OS가 적극적으로 제한 |
| **WakeLock 없음** | 화면이 꺼지면 CPU가 절전 상태로 진입하여, ML Kit 얼굴 감지 / TFLite 추론과 같은 CPU 집중 작업이 사실상 중단됨 |
| **프로세스 우선순위 하락** | 백그라운드 앱은 낮은 우선순위로 강등되어, OS가 메모리·CPU 자원을 회수할 수 있음 |

현재 `AndroidManifest.xml`에는 `FOREGROUND_SERVICE` 권한 선언과 Service 컴포넌트가 없어
구조적으로 백그라운드 지속 실행이 불가능한 상태입니다.

이 ENH는 **Foreground Service**를 도입하여, 사용자가 스캔 도중 앱을 백그라운드로 내려도
스캔이 계속 진행되도록 합니다. 스캔 진행 상태는 상태 알림(Persistent Notification)으로
표시되며, ViewModel과의 통신은 공유 `StateFlow`를 통해 이루어집니다.

---

## 2. 요구사항

### 2.1. 백그라운드 스캔 지속 실행

- 스캔 진행 중 앱이 백그라운드로 이동해도 스캔이 중단 없이 계속되어야 합니다.
- 스캔 완료 후 UI가 포그라운드 상태일 경우 자동으로 클러스터 선택 단계(`ClusterSelect`)로 전환합니다.
- 스캔 완료 후 UI가 백그라운드 상태일 경우, 알림 탭 시 앱을 열어 클러스터 선택 단계를 표시합니다.

### 2.2. 스캔 진행 상태 알림

- 스캔 시작 시 **상태 알림(Persistent Notification)** 을 표시합니다.
- 알림에는 현재 진행률(`N / 전체 M 장`)과 퍼센트를 표시합니다.
- 스캔 완료 시 알림 내용을 "사진 분류 완료" 메시지로 교체하고, 탭하면 앱으로 복귀합니다.
- 사용자가 UI에서 [취소] 버튼을 누르면 서비스가 중단되고 알림이 사라집니다.

### 2.3. 스캔 취소 지원

- 사용자가 스캔 중 [취소] 버튼을 누르면 서비스가 즉시 중단되어야 합니다.
- 서비스 중단 시 진행 중이던 임베딩 데이터는 폐기하고, UI는 `ChildInfo` 단계로 복귀합니다.

---

## 3. 구현 계획

### 3.1. `ScanStateHolder.kt` 신규 생성 (Hilt Singleton)

ViewModel과 Service 간 상태 공유를 위한 단일 진실 공급원(Single Source of Truth).

```kotlin
// di/ 또는 ml/ 패키지
@Singleton
class ScanStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun emit(state: ScanState) { _state.value = state }
}

sealed class ScanState {
    object Idle : ScanState()
    data class Running(val processed: Int, val total: Int) : ScanState()
    data class Done(val clusters: List<FaceCluster>) : ScanState()
    data class Failed(val message: String) : ScanState()
    object Cancelled : ScanState()
}
```

### 3.2. `ScanForegroundService.kt` 신규 생성

스캔 로직(`performScan`)을 `InitViewModel`에서 이 서비스로 이전합니다.

- `LifecycleService`를 상속하여 `lifecycleScope`를 사용합니다.
- `startForeground(NOTIFICATION_ID, buildNotification(...))` 호출로 Foreground 상태를 유지합니다.
- `ScanStateHolder`에 진행 상태를 emit하며, ViewModel은 이를 observe합니다.
- `Intent`로 `ACTION_START` / `ACTION_CANCEL`을 전달하여 시작·취소를 제어합니다.
- 스캔 완료 또는 취소 시 `stopSelf()`를 호출하여 서비스를 종료합니다.

```
// 핵심 흐름
onStartCommand(intent) {
    if (ACTION_START)  → startForeground() → lifecycleScope.launch { performScan() }
    if (ACTION_CANCEL) → scanJob?.cancel() → stopSelf()
}
```

`performScan()` 내부 로직은 기존 `InitViewModel`의 코드를 그대로 이전하되,
진행 상황을 `ScanStateHolder.emit(ScanState.Running(processed, total))`으로 방출합니다.

### 3.3. `NotificationHelper.kt` 신규 생성 (또는 `DodoApp.kt` 수정)

- 알림 채널(`SCAN_CHANNEL_ID`)을 앱 시작 시 생성합니다.
- `DodoApp.onCreate()`에서 `NotificationChannel` 등록 로직을 추가합니다.
- `ScanForegroundService`에서 사용할 `buildScanNotification(processed, total)` 함수를 제공합니다.

### 3.4. `InitViewModel.kt` 수정

스캔 실행 주체를 `viewModelScope` 코루틴에서 `ScanForegroundService`로 변경합니다.

- `startScanning()`: `viewModelScope.launch { performScan() }` 대신
  `context.startService(Intent(ACTION_START))` 호출.
- `cancelScanning()`: `scanJob?.cancel()` 대신
  `context.startService(Intent(ACTION_CANCEL))` 호출.
- `performScan()` 함수를 ViewModel에서 제거합니다.
- `ScanStateHolder.state`를 `viewModelScope`에서 collect하여 `_uiState`를 업데이트합니다.

```kotlin
// InitViewModel 내 observe 예시
init {
    viewModelScope.launch {
        scanStateHolder.state.collect { scanState ->
            when (scanState) {
                is ScanState.Running -> _uiState.update {
                    it.copy(scannedCount = scanState.processed, totalCount = scanState.total)
                }
                is ScanState.Done   -> handleDone(scanState.clusters)
                is ScanState.Failed -> _uiState.update { it.copy(step = InitStep.ChildInfo, error = scanState.message) }
                else -> Unit
            }
        }
    }
}
```

### 3.5. `AndroidManifest.xml` 수정

```xml
<!-- 권한 추가 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- application 블록 내 서비스 추가 -->
<service
    android:name=".service.ScanForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

---

## 4. 고려사항

### 4.1. Android 버전별 Foreground Service 권한

- **Android 8.0 (API 26)+**: `FOREGROUND_SERVICE` 권한 필수.
- **Android 14 (API 34)+**: `foregroundServiceType`을 명시해야 하며,
  데이터 처리 목적이므로 `dataSync` 타입과 `FOREGROUND_SERVICE_DATA_SYNC` 권한을 사용합니다.

### 4.2. WakeLock 획득 시점

`ScanForegroundService.startForeground()` 호출 직후 `PowerManager.PARTIAL_WAKE_LOCK`을 획득하고,
`stopSelf()` 전에 반드시 해제합니다. 스캔 취소/실패 경로에서도 WakeLock이 해제되도록
`try/finally` 블록으로 감쌉니다.

### 4.3. ViewModel과 Service 생명주기 불일치

사용자가 앱을 완전히 종료(Activity 파괴)하면 `InitViewModel`이 cleared 되지만,
서비스는 계속 실행될 수 있습니다. 이 경우:

- `ScanStateHolder`의 상태는 프로세스에 남아 있음.
- 앱을 다시 열면 `InitViewModel`이 재생성되어 `ScanStateHolder.state`를 다시 collect하므로,
  진행 중인 스캔 상태를 자연스럽게 복원합니다.
- 프로세스 자체가 종료된 경우에는 서비스도 함께 종료되므로 불완전한 상태가 남지 않습니다.

### 4.4. 기존 PhotoSyncWorker와의 충돌 방지

`PhotoSyncWorker`(6시간 주기)는 초기화 완료 후에만 등록됩니다(`initialized = true` 이후).
초기화 스캔 서비스와는 실행 시점이 겹치지 않으므로 별도 동기화 처리는 불필요합니다.

### 4.5. 알림 채널 중복 등록 방지

`NotificationChannel` 생성은 `DodoApp.onCreate()`에서 한 번만 등록합니다.
Android는 이미 등록된 채널 ID를 재등록해도 무시하므로 멱등성이 보장됩니다.

---

## 5. 파일 변경 목록 요약

| 파일 | 변경 유형 | 주요 변경 내용 |
|---|---|---|
| `service/ScanForegroundService.kt` | **신규** | 스캔 Foreground Service, `performScan` 로직 이전 |
| `service/ScanStateHolder.kt` | **신규** | ViewModel-Service 간 공유 `StateFlow` |
| `service/ScanState.kt` | **신규** | `Idle / Running / Done / Failed / Cancelled` sealed class |
| `DodoApp.kt` | 수정 | 알림 채널(`SCAN_CHANNEL_ID`) 등록 |
| `presentation/init/InitViewModel.kt` | 수정 | `performScan` 제거, 서비스 시작·취소로 교체, `ScanStateHolder` observe |
| `AndroidManifest.xml` | 수정 | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` 권한 추가, 서비스 선언 |
| `di/AppModule.kt` | 수정 | `ScanStateHolder` Singleton 바인딩 추가 |
