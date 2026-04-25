# 변경계획 문서 — 초기화 플로우 개선

> 요청일: 2026-04-25 | 대상 브랜치: `claude/enhance-child-setup-flow-tjizM`

---

## 개요

세 가지 기능 개선 + 한 가지 AI 프롬프트 개선을 다룬다.

| # | 변경 주제 | 관련 요구사항 |
|---|---|---|
| 1 | 사진 스캔 중 취소 버튼 추가 | INIT-08, NFR-01 수정 |
| 2 | 클러스터 선택 시 전체 표시 (참조 사진 기반 추측 금지) | INIT-04 수정 |
| 3 | 아이 정보에 성별 필드 추가 | INIT-09 신규 |
| 4 | LLM이 애칭·호칭도 아이로 인식하도록 프롬프트 개선 | KAKO-07 신규 |

---

## 변경 1 — 사진 스캔 중 취소 버튼

### 현재 동작
- 스캔 시작 후 전체 화면 오버레이가 모든 제스처를 차단
- 취소 수단이 없어 수천 장 스캔이 끝날 때까지 강제 대기
- `performScan()`은 `viewModelScope.launch(Dispatchers.IO)`로 실행되며 취소 지점이 없음

### 변경 후 동작
- 스캔 화면 하단에 "취소" 버튼이 항상 표시됨
- 취소 클릭 시: 스캔 코루틴 즉시 중단 → 수집된 임베딩/클러스터 데이터 폐기 → 1단계(아이 정보 입력) 화면으로 복귀
- 기존 입력값(이름, 생년월일, 성별, 사진)은 복귀 후에도 유지됨

### 영향 받는 파일

#### `presentation/init/InitViewModel.kt`

```kotlin
// 추가: scan Job 레퍼런스
private var scanJob: Job? = null

// 변경: startScanning()에서 Job 저장
fun startScanning() {
    ...
    _uiState.update { it.copy(step = InitStep.Scanning, error = null) }
    scanJob = viewModelScope.launch(Dispatchers.IO) { performScan() }
}

// 추가: 취소 함수
fun cancelScanning() {
    scanJob?.cancel()
    scanJob = null
    _rawClusters = emptyList()
    _uiState.update { state ->
        state.copy(
            step = InitStep.ChildInfo,
            scannedCount = 0,
            totalCount = 0,
            clusters = emptyList(),
            selectedClusterIds = emptySet(),
            error = null
        )
        // childName, birthDate, gender, referencePhotoUri는 유지
    }
}

// 변경: performScan() 루프 내 취소 체크 추가
private suspend fun performScan() {
    ...
    for ((uri, takenAt) in photoUris) {
        currentCoroutineContext().ensureActive()  // ← 추가: 취소 시 CancellationException 발생
        ...
    }
}
```

#### `presentation/init/InitScreen.kt`

```kotlin
// 변경: ScanningStep에 vm 파라미터 추가
private fun ScanningStep(state: InitUiState, vm: InitViewModel) { ... }

// 변경: 기존 별도 오버레이 제거 후 ScanningStep 자체에 통합
// ScanningStep Column 하단에 취소 버튼 추가:
OutlinedButton(
    onClick = vm::cancelScanning,
    modifier = Modifier.padding(top = 16.dp)
) {
    Icon(Icons.Default.Close, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text("취소")
}

// 변경: Box 내 별도 gesture-blocking overlay 제거
// (ScanningStep 자체가 fillMaxSize + pointerInput으로 블락)
```

### 주의사항
- `ensureActive()`는 `kotlinx.coroutines`의 확장 함수로 import 필요
- `CancellationException`은 코루틴 내부에서 정상 종료로 처리되므로 별도 catch 불필요
- `scanJob?.cancel()` 호출 후 `_rawClusters` 초기화 순서를 지켜야 함 (race condition 방지)

---

## 변경 2 — 클러스터 전체 표시 (참조 사진 기반 추측 금지)

### 현재 동작
현재 코드는 이미 모든 클러스터를 표시하고 참조 사진을 사전 선택에 사용하지 않는다.
`referencePhotoUri`는 `Child` 모델의 프로필 사진으로만 저장될 뿐 클러스터링·필터링에 관여하지 않는다.

### 변경 사항
**코드 변경 없음** — 현재 동작이 이미 요구사항에 부합한다.

요구사항 문서(INIT-04)에 이 동작을 명시적으로 기술하여 향후 코드에서도 이 원칙이 지켜지도록 보장한다.

### 확인된 동작 원칙
- 참조 사진은 `Child.referencePhotoUri`에 저장되어 타임라인 등에서 프로필 이미지로만 사용
- 클러스터링은 ML Kit + FaceNet 기반 순수 유사도 기반으로만 동작
- `ClusterSelectStep`은 `_rawClusters` 전체를 `ClusterUiModel`로 변환하여 표시

---

## 변경 3 — 아이 정보에 성별 필드 추가

### 현재 동작
아이 정보 입력 시: 대표 사진, 이름, 생년월일 (3개 필드)

### 변경 후 동작
아이 정보 입력 시: 대표 사진, 이름, 생년월일, **성별** (4개 필드)  
성별 미선택 시 "사진 분류 시작" 버튼 비활성화

### 영향 받는 파일

#### `domain/model/Child.kt` (신규 enum + 필드)

```kotlin
enum class Gender { MALE, FEMALE }

data class Child(
    val id: String,
    val name: String,
    val birthDate: LocalDate,
    val gender: Gender,            // ← 추가
    val referencePhotoUri: String,
    val faceEmbeddings: List<FloatArray> = emptyList()
)
```

#### `data/local/db/entity/ChildEntity.kt`

```kotlin
@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: String,
    val name: String,
    val birthDate: Long,
    val gender: String,            // ← 추가 ("MALE" / "FEMALE")
    val referencePhotoUri: String,
    val faceEmbeddingsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)
```

#### `data/local/db/DodoDatabase.kt`

```kotlin
// version 1 → 2로 올리고 migration 추가
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE children ADD COLUMN gender TEXT NOT NULL DEFAULT 'MALE'"
        )
    }
}

// Room builder에 addMigrations(MIGRATION_1_2) 추가
```

> **기존 앱 데이터 영향**: 이미 설치된 앱은 migration 실행 후 기존 아이 레코드의 gender가 'MALE'로 설정됨. 설정 화면에서 변경 가능하도록 추후 MGT-04 범위에서 처리 권장.

#### `data/repository/ChildRepositoryImpl.kt`

```kotlin
// ChildEntity → Child 매핑에 gender 추가
gender = Gender.valueOf(entity.gender)

// Child → ChildEntity 매핑에 gender 추가
gender = child.gender.name
```

#### `presentation/init/InitViewModel.kt`

```kotlin
data class InitUiState(
    ...
    val gender: Gender? = null,    // ← 추가
    ...
)

fun setGender(gender: Gender) = _uiState.update { it.copy(gender = gender) }

// startScanning() 유효성 검사에 gender 추가
if (state.childName.isBlank() || state.birthDate == null
    || state.referencePhotoUri.isBlank() || state.gender == null) { ... }

// confirmClusters()에서 Child 생성 시 gender 포함
val child = Child(
    id = UUID.randomUUID().toString(),
    name = state.childName,
    birthDate = state.birthDate!!,
    gender = state.gender!!,       // ← 추가
    referencePhotoUri = state.referencePhotoUri,
    faceEmbeddings = embeddings
)
```

#### `presentation/init/InitScreen.kt`

```kotlin
// ChildInfoStep에 성별 선택 UI 추가 (생년월일 아래)
// Material 3 SingleChoiceSegmentedButtonRow 사용
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    SegmentedButton(
        selected = state.gender == Gender.MALE,
        onClick = { vm.setGender(Gender.MALE) },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
    ) { Text("남아") }
    SegmentedButton(
        selected = state.gender == Gender.FEMALE,
        onClick = { vm.setGender(Gender.FEMALE) },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
    ) { Text("여아") }
}

// 버튼 활성화 조건에 gender 추가
enabled = state.childName.isNotBlank()
    && state.birthDate != null
    && state.referencePhotoUri.isNotBlank()
    && state.gender != null
```

### 동작 변경 요약
| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 아이 정보 입력 필드 수 | 3개 | 4개 |
| DB schema version | 1 | 2 |
| 버튼 활성화 조건 | 사진+이름+생년월일 | 사진+이름+생년월일+성별 |
| Child 도메인 모델 | gender 없음 | gender: Gender 필드 포함 |

---

## 변경 4 — LLM 애칭·호칭 인식 프롬프트 개선

### 현재 동작
`GeminiEventClassifier.buildPrompt()`에서 아이 이름(`childName`)만 기준으로 이벤트를 필터링한다.
채팅에서 "우리애기", "왕자님", "공주님" 등으로 불리는 경우 AI가 아이와 연결하지 못할 수 있다.

### 질문에 대한 답변

> "LLM을 쓰니까 뉘앙스는 잘 파악하지? 그러니까 이름을 명시하지 않고 '우리애기', '왕자님' 이렇게 쓰는 것도 전후맥락을 이해해서 누구한테 하는 말인지 구분되지?"

**원칙적으로는 가능하다.** Gemini 같은 LLM은 문맥 추론 능력이 있어서 이러한 호칭을 아이를 지칭하는 것으로 이해할 수 있다. 다만:
- 현재 프롬프트가 `$childName 이(가) 직접 한 말, 행동...`으로만 명시되어 있어 LLM이 **이름 기반 매칭**에만 집중할 가능성이 있다
- 명시적으로 "다양한 호칭도 포함" 지침을 주면 정확도가 크게 올라간다
- 복수의 아이가 있는 채팅방에서는 맥락 추론이 어려울 수 있으므로 완벽하진 않다

### 변경 후 동작
`buildPrompt()`에 호칭 인식 지침을 추가하여 LLM이 다음을 처리하도록 유도:
- 이름 외 애칭/호칭 (우리애기, 왕자님, 공주님, 아가, 아이 등)
- 대화 흐름·문맥을 통해 아이를 지칭하는 표현 인식
- 발화자와 수신자 관계를 통한 추론

### 영향 받는 파일

#### `ai/GeminiEventClassifier.kt`

```kotlin
private fun buildPrompt(messages: String, childName: String) = """
당신은 가족 카카오톡 대화에서 아이의 성장 관련 이벤트를 추출하는 전문가입니다.

아이 이름: $childName

다음 규칙을 따르세요:
- $childName 이(가) 직접 한 말, 행동, 성취, 신체 변화를 이벤트로 추출합니다
- $childName 을(를) 지칭하는 다양한 표현도 아이로 인식하세요:
  "우리애기", "애기", "왕자님", "공주님", "아가", "아이", "우리 아이", "얘" 등
  대화 문맥(발신자, 답변 흐름, 주어 생략)을 통해 아이를 가리키는지 판단하세요
- 카테고리: SAID(아이가 한 말), DID(아이가 한 행동/성취), OTHER(기타 아이 관련)
- 아이와 무관한 대화는 무시합니다
- 이벤트 내용은 아이 관점에서 간결하게 서술합니다 (예: "처음으로 뒤집기 성공")
- date는 해당 메시지의 날짜를 사용합니다

대화 내용:
$messages

JSON 배열로만 응답하세요 (다른 텍스트 없이):
[{"date":"YYYY-MM-DD","category":"SAID|DID|OTHER","content":"이벤트 내용"}]

이벤트가 없으면 []
""".trimIndent()
```

### 변경 전/후 비교 예시

| 대화 예시 | 변경 전 | 변경 후 |
|---|---|---|
| "우리 왕자님 오늘 처음 걸었어!" | 이름 불일치로 미추출 가능 | "처음 걷기 성공" 추출 |
| "애기가 엄마라고 했대요" | 미추출 가능 | "처음으로 '엄마' 발화" 추출 |
| "지훈이 밥 잘 먹었어" (이름 있는 경우) | 정상 추출 | 정상 추출 (변경 없음) |

---

## 전체 변경 파일 목록

| 파일 | 변경 유형 | 변경 1 | 변경 2 | 변경 3 | 변경 4 |
|---|---|:---:|:---:|:---:|:---:|
| `domain/model/Child.kt` | 수정 | | | ✓ | |
| `data/local/db/entity/ChildEntity.kt` | 수정 | | | ✓ | |
| `data/local/db/DodoDatabase.kt` | 수정 (migration) | | | ✓ | |
| `data/repository/ChildRepositoryImpl.kt` | 수정 | | | ✓ | |
| `presentation/init/InitViewModel.kt` | 수정 | ✓ | | ✓ | |
| `presentation/init/InitScreen.kt` | 수정 | ✓ | | ✓ | |
| `ai/GeminiEventClassifier.kt` | 수정 | | | | ✓ | |
| `docs/REQUIREMENTS.md` | 수정 | ✓ | ✓ | ✓ | ✓ |

---

## 리스크 및 고려사항

| 항목 | 내용 |
|---|---|
| DB Migration | 기존 설치 앱은 gender가 'MALE' 기본값으로 설정됨. 신규 설치는 문제 없음 |
| 취소 후 재시작 | 취소 후 입력값 유지는 편의성 측면에서 긍정적이나, UX 검토 필요 (사진만 초기화하고 싶을 수도 있음) |
| LLM 정확도 | 호칭 인식 개선은 false positive(다른 아이나 어른을 아이로 오인) 가능성도 소폭 증가할 수 있음 |
| Gender enum | 현재 MALE/FEMALE 2종. 추후 UNKNOWN 추가 시 UI만 변경으로 대응 가능 |

---

*문서 작성: 2026-04-25*
