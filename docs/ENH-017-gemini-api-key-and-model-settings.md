# ENH-017: Gemini API 키 및 모델 선택 설정

## 목표 (Objective)
배포 빌드에 Gemini API 키를 포함하지 않고, 사용자가 앱 안에서 직접 Gemini API 키를 입력하고 사용할 모델을 선택할 수 있게 합니다.

초기 구현 범위는 Gemini API만 대상으로 합니다. OpenAI, Anthropic, 로컬 모델, 서버 프록시 등 다른 제공자는 포함하지 않습니다.

관련 이슈:

- #30 Gemini API 키 입력

---

## 배경 (Background)
현재 앱은 `local.properties`의 `GEMINI_API_KEY`를 `BuildConfig.GEMINI_API_KEY`로 주입해 Gemini 요청에 사용합니다.

이 방식은 개발 중에는 편하지만, 배포 관점에서는 문제가 있습니다.

- APK/AAB 안에 개발자 키가 포함될 수 있습니다.
- 사용자가 자기 Gemini API 키를 사용할 수 없습니다.
- 키를 바꾸려면 앱을 다시 빌드해야 합니다.
- 키가 없는 상태와 잘못된 키 상태를 UX에서 명확히 안내하기 어렵습니다.

따라서 Gemini 키와 모델은 런타임 설정으로 이동합니다.

---

## 사용자 동작

### 1. 설정 진입
설정 메뉴에 다음 항목을 추가합니다.

```text
Gemini API 설정
```

권장 배치:

```text
카카오톡 대화 가져오기
대화 분석 재시도
Gemini API 설정
문맥 업데이트
신규 사진 로딩
사진 원본 확인
이벤트 내보내기
이벤트 가져오기
숨김 아이템
앱 데이터 초기화
```

Gemini API 설정은 카카오톡 import, 대화 분석 재시도, 문맥 업데이트의 선행 조건에 가깝기 때문에 Gemini 관련 작업 근처에 배치합니다.

구현 중 기존 옵션 순서가 어색하면 함께 개편해도 됩니다.

권장 그룹:

- 대화/AI: `카카오톡 대화 가져오기`, `대화 분석 재시도`, `Gemini API 설정`, `문맥 업데이트`
- 사진: `신규 사진 로딩`, `사진 원본 확인`, `확인 필요한 사진`
- 데이터: `이벤트 내보내기`, `이벤트 가져오기`
- 관리: `숨김 아이템`, `앱 데이터 초기화`

초기 구현은 실제 섹션 헤더를 넣지 않아도 됩니다. 다만 항목 순서는 위 그룹의 흐름을 따르는 것이 좋습니다.

### 2. API 키 입력
`Gemini API 설정`을 누르면 다이얼로그 또는 전용 설정 화면을 표시합니다.

초기 구현은 다이얼로그로 충분합니다.

구성:

- API 키 입력 필드
- 저장 버튼
- 삭제 버튼
- 모델 목록 새로고침 버튼
- 모델 선택 dropdown
- 현재 설정 상태 표시

키 입력 필드는 기본적으로 마스킹합니다.

예시:

```text
Gemini API 키
[********************************____]

모델
[gemini-...                         v]

[모델 목록 불러오기] [삭제] [저장]
```

### 3. 모델 목록 조회
사용자가 API 키를 입력한 뒤 `모델 목록 불러오기`를 누르면 Gemini API의 모델 목록을 조회합니다.

원칙:

- 모델명은 앱에 고정하지 않습니다.
- 입력된 API 키로 접근 가능한 모델만 보여줍니다.
- 텍스트 생성에 사용할 수 있는 모델만 우선 노출합니다.
- 사용할 수 없는 모델이나 embedding 전용 모델은 제외하거나 비활성 처리합니다.

필터링 기준 후보:

- `supportedGenerationMethods`에 `generateContent`가 포함된 모델
- 모델 이름이 `models/`로 시작하면 UI에서는 앞의 `models/`를 제거해 표시
- 정렬은 가벼운/빠른 모델이 위에 오도록 할 수 있으나, 초기 구현은 API 응답 순서를 유지해도 됩니다.

모델 목록 조회 실패 시:

- 네트워크 오류: `모델 목록을 불러오지 못했습니다. 네트워크를 확인해주세요.`
- 인증 오류: `Gemini API 키를 확인해주세요.`
- 사용 가능한 모델 없음: `사용 가능한 Gemini 생성 모델이 없습니다.`

### 4. 모델 선택
모델 목록이 로드되면 사용자가 dropdown에서 모델을 선택합니다.

저장되는 값은 API 호출에 그대로 사용할 수 있는 모델 id입니다.

예시:

```text
models/gemini-...
```

UI 표시명은 `models/`를 제거할 수 있지만, 저장값은 원본 id를 유지합니다.

### 5. 저장
사용자가 `저장`을 누르면 다음 값을 저장합니다.

- Gemini API 키
- 선택된 Gemini 모델 id
- 마지막 모델 목록 조회 시각
- 마지막으로 조회된 모델 목록 캐시

모델 목록 캐시는 선택 dropdown을 빠르게 열기 위한 용도입니다. 캐시가 오래되었거나 키가 바뀌면 다시 조회할 수 있어야 합니다.

---

## 저장 방식

### 1. API 키 저장
초기 구현은 DataStore 저장을 허용합니다.

다만 API 키는 민감 정보이므로 장기적으로는 Android Keystore 기반 암호화 저장을 검토합니다.

권장 단계:

1. v1: Preferences DataStore에 저장
2. v2: EncryptedSharedPreferences 또는 Keystore 기반 저장으로 교체

초기 구현에서 DataStore를 쓰는 이유:

- 현재 앱이 이미 DataStore를 사용합니다.
- 구현 범위가 작습니다.
- 개인 배포/테스트 단계에서는 충분히 단순합니다.

주의:

- 로그에 API 키 전체를 출력하지 않습니다.
- UI에도 전체 키를 다시 노출하지 않습니다.
- 오류 메시지에 키 값을 포함하지 않습니다.

### 2. 저장 키 후보
`AppPrefsKeys` 후보:

```kotlin
val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
val GEMINI_MODEL_ID = stringPreferencesKey("gemini_model_id")
val GEMINI_MODEL_LIST_JSON = stringPreferencesKey("gemini_model_list_json")
val GEMINI_MODEL_LIST_FETCHED_AT = longPreferencesKey("gemini_model_list_fetched_at")
```

### 3. BuildConfig 키 제거
배포 경로에서는 `BuildConfig.GEMINI_API_KEY`를 사용하지 않습니다.

선택지:

- 완전 제거
- debug fallback으로만 유지

권장:

- 런타임 저장 키를 우선 사용합니다.
- debug 빌드에서만 저장 키가 없을 때 `BuildConfig.GEMINI_API_KEY` fallback을 허용할 수 있습니다.
- release 빌드에서는 fallback을 사용하지 않습니다.

이렇게 하면 개발 편의성은 유지하면서 배포 빌드에 키가 박히는 문제를 피할 수 있습니다.

---

## Gemini 호출부 변경

### 현재 구조
현재 `GeminiEventClassifier`는 생성자에서 고정 API 키와 모델을 주입받습니다.

```kotlin
@Named("gemini_api_key") private val apiKey: String
@Named("gemini_model") private val model: String
```

이 구조는 앱 실행 중 키/모델 변경을 반영하기 어렵습니다.

### 변경 방향
Gemini 호출 직전에 현재 설정을 읽어옵니다.

후보 구조:

```kotlin
data class GeminiSettings(
    val apiKey: String,
    val modelId: String
)

interface GeminiSettingsRepository {
    val settingsFlow: Flow<GeminiSettings>
    suspend fun getSettings(): GeminiSettings
    suspend fun saveApiKeyAndModel(apiKey: String, modelId: String)
    suspend fun clear()
}
```

`GeminiEventClassifier`는 `GeminiSettingsRepository`를 주입받고, 요청 직전에 `getSettings()`를 호출합니다.

```kotlin
val settings = geminiSettingsRepository.getSettings()
if (settings.apiKey.isBlank()) {
    return apiKeyMissing result
}
val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.modelId}:generateContent?key=${settings.apiKey}"
```

모델 id가 `models/gemini-...` 형태로 저장되어 있다면 URL 생성 시 `models/` 중복을 피해야 합니다.

권장 helper:

```kotlin
private fun modelPath(modelId: String): String =
    if (modelId.startsWith("models/")) modelId else "models/$modelId"
```

---

## API 키 없음 처리

Gemini API 키가 없으면 Gemini가 필요한 작업은 실행하지 않습니다.

대상:

- 카카오톡 대화 가져오기
- 실패 chunk 재시도
- 문맥 업데이트
- 모델 목록 조회

사용자 메시지:

```text
Gemini API 키를 먼저 설정해주세요.
```

가능하면 snackbar만 띄우기보다, 설정 다이얼로그로 바로 이동할 수 있게 합니다.

초기 구현:

- snackbar 표시
- 설정 메뉴에서 사용자가 직접 `Gemini API 설정` 진입

후속 개선:

- Gemini 키가 필요한 버튼을 누르면 API 설정 다이얼로그를 바로 표시

---

## 모델 목록 조회 API

Gemini 모델 목록 조회는 다음 흐름으로 처리합니다.

```text
GET https://generativelanguage.googleapis.com/v1beta/models?key=<API_KEY>
```

응답에서 `models` 배열을 읽습니다.

필요 DTO 후보:

```kotlin
@Serializable
data class GeminiModelsResponse(
    val models: List<GeminiModelInfo> = emptyList()
)

@Serializable
data class GeminiModelInfo(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)
```

필터:

```kotlin
models.filter { "generateContent" in it.supportedGenerationMethods }
```

저장:

- 원본 `name`을 model id로 저장합니다.
- UI label은 `displayName ?: name.removePrefix("models/")`를 사용합니다.

---

## UI 상태

`TimelineUiState` 또는 설정 전용 state 후보:

```kotlin
val geminiApiKeyConfigured: Boolean = false
val selectedGeminiModelLabel: String = ""
val isGeminiModelLoading: Boolean = false
val geminiModelOptions: List<GeminiModelOption> = emptyList()
```

추천은 `TimelineUiState`에 너무 많은 설정 상태를 넣지 않고, 설정 다이얼로그 내부 state와 repository flow를 조합하는 방식입니다.

---

## 수용 기준 (Acceptance Criteria)

### 기능 기준
- 설정 메뉴에서 `Gemini API 설정`을 열 수 있다.
- 사용자가 Gemini API 키를 입력하고 저장할 수 있다.
- 저장된 키가 앱 재시작 후에도 유지된다.
- 키 삭제가 가능하다.
- 입력된 키로 Gemini 모델 목록을 불러올 수 있다.
- `generateContent` 가능한 모델만 선택지로 표시된다.
- 사용자가 모델을 선택하고 저장할 수 있다.
- Gemini 요청은 저장된 API 키와 모델을 사용한다.
- API 키가 없으면 카카오톡 import, 실패 chunk 재시도, 문맥 업데이트를 실행하지 않는다.

### UX 기준
- API 키 전체가 화면에 평문으로 계속 노출되지 않는다.
- 모델 목록 조회 중 loading 상태를 표시한다.
- 모델 목록 조회 실패 이유를 사용자에게 이해 가능한 문구로 보여준다.
- 저장 성공/실패를 snackbar로 안내한다.

### 보안/품질 기준
- API 키를 로그에 출력하지 않는다.
- release 빌드에서 개발자 API 키 fallback을 사용하지 않는다.
- 모델 id URL 조합 시 `models/models/...` 같은 중복 경로가 생기지 않는다.
- 잘못된 키나 네트워크 오류에서 앱이 crash하지 않는다.

---

## 작업 범위 (Scope)

포함:

- Gemini API 설정 UI
- API 키 저장/삭제
- Gemini 모델 목록 조회
- 모델 선택/저장
- Gemini 호출부의 런타임 설정 사용 전환
- 키 없음 상태에서 Gemini 작업 차단

제외:

- OpenAI/Anthropic 등 다른 LLM 제공자
- 서버 프록시
- API 키 암호화 저장의 완성형 구현
- 모델별 가격/속도/품질 설명
- 사용자별 토큰 사용량 통계

---

## 리스크 (Risks)

- Gemini 모델 목록 API 응답 구조나 모델명이 바뀔 수 있습니다.
- 사용자가 API 키 권한이나 결제 설정을 완료하지 않은 경우 모델 목록은 보이지만 실제 generate 요청이 실패할 수 있습니다.
- DataStore 저장은 완전한 보안 저장소가 아니므로, 장기적으로 암호화 저장을 검토해야 합니다.
- 모델을 너무 자유롭게 선택하게 하면 앱 프롬프트와 맞지 않는 모델을 고를 수 있습니다.

---

## 향후 확장

- 모델별 추천 배지 표시
- 마지막 사용 모델 자동 선택
- API 키 유효성 테스트 요청
- Gemini 사용량/실패 로그 요약
- API 키 암호화 저장
- 서버 프록시 기반 키 없는 사용 모드
