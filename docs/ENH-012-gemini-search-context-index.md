# ENH-012: Gemini 검색 보조 인덱스 기반 문맥 검색

## 목표 (Objective)
ONNX 기반 온디바이스 임베딩 모델을 앱에 포함하지 않고도, 사용자가 체감할 수 있는 문맥 검색을 제공합니다.

기존 검색 다이얼로그의 UX는 유지합니다. 검색 모드는 지금처럼 하단의 `문맥 포함` 토글 하나로만 전환합니다.

- `문맥 포함` OFF: 일반 키워드 검색
- `문맥 포함` ON: Gemini가 미리 생성한 검색 보조 데이터를 포함하는 문맥 검색

문맥 검색 결과의 기본 정렬은 기존 타임라인 UX와 동일하게 날짜순을 유지하되, 문맥 검색 상태에서만 사용자가 `날짜순 / 관련도순`을 전환할 수 있도록 합니다.

---

## 배경 (Background)
ENH-010/ENH-011에서는 텍스트 임베딩 기반의 의미 검색을 목표로 했지만, ONNX MiniLM 자산과 런타임 의존성은 앱 용량을 크게 증가시킵니다.

현재 확인된 주요 자산만 보아도:

- `model_qint8_arm64.onnx`: 약 118MB
- `tokenizer.json`: 약 9MB
- `sentencepiece.bpe.model`: 약 5MB

따라서 장기적으로 문맥 검색을 유지하려면, 앱 내부에 대형 임베딩 모델을 싣는 방식보다 기존 Gemini 이벤트 생성 파이프라인을 활용해 검색용 보조 인덱스를 미리 만들어두는 방식이 더 적합합니다.

---

## 주요 요구사항 (Requirements)

### 1. 기존 검색 UX 유지
- 검색 진입은 기존 `TimelineScreen`의 검색 다이얼로그를 유지합니다.
- 검색 다이얼로그의 `문맥 포함 (의미 기반 검색)` 토글을 유지합니다.
- 별도의 `키워드 검색` 토글은 추가하지 않습니다.
- `문맥 포함` OFF는 일반 키워드 검색, ON은 문맥 검색으로 동작합니다.

### 2. 일반 키워드 검색 동작 유지
`문맥 포함` OFF일 때는 기존 동작을 유지합니다.

- 검색 대상:
  - `content`
  - `longContent`
  - `rawExcerpt`
- 띄어쓰기 기준으로 검색어를 토큰화합니다.
- 모든 토큰이 검색 대상 텍스트에 포함되어야 하는 AND 조건으로 검색합니다.
- 따옴표로 감싼 검색어는 기존처럼 정확 문구 검색으로 처리합니다.
- 사진 이벤트(`EventCategory.PHOTO`)는 검색 대상에서 제외합니다.
- 결과 정렬은 날짜순을 유지합니다.

### 3. Gemini 검색 보조 데이터 생성
이벤트 생성 시 Gemini가 사용자 검색을 돕기 위한 보조 데이터를 추가로 생성합니다.

검색 보조 데이터는 일반 키워드 검색에는 사용하지 않고, `문맥 포함` ON일 때만 사용합니다.

생성 대상 예시:

- 검색용 짧은 요약
- 핵심 태그
- 사용자가 검색할 법한 자연어 별칭
- 관련 키워드
- 장소, 사람, 감정, 행동, 상황 키워드
- 원문에는 없지만 합리적으로 추론 가능한 유의어

검색 보조 데이터는 검색 recall을 높이기 위한 최소한의 인덱스입니다. 원문에 없는 사실을 풍부하게 생성하는 용도가 아니므로, 각 필드는 짧게 제한합니다.

권장 제한:

- `searchSummary`: 1문장, 80자 이내
- `searchTags`: 최대 10개
- `searchAliases`: 최대 5개
- `relatedKeywords`: 최대 12개
- 각 태그/키워드는 가능하면 12자 이내의 짧은 한국어 명사/구로 생성
- 생성할 근거가 부족한 필드는 빈 문자열 또는 빈 배열 허용

예시:

```json
{
  "searchSummary": "아이가 고열로 컨디션이 좋지 않아 병원 진료가 필요했던 기록",
  "searchTags": ["고열", "병원", "진료", "아픔", "컨디션", "감기"],
  "searchAliases": ["아이가 아팠던 날", "열났던 날", "병원 간 날"],
  "relatedKeywords": ["응급", "간호", "걱정", "몸살", "진찰"]
}
```

### 4. 문맥 검색 동작
`문맥 포함` ON일 때는 기존 이벤트 텍스트와 Gemini 검색 보조 데이터를 함께 사용합니다.

- 검색 대상:
  - `content`
  - `longContent`
  - `rawExcerpt`
  - `searchSummary`
  - `searchTags`
  - `searchAliases`
  - `relatedKeywords`
- 띄어쓰기 기준 토큰 검색은 OR 조건을 허용합니다.
- 다만 결과 품질을 위해 단순 OR 필터만 사용하지 않고, 관련도 점수를 함께 계산합니다.
- 최소 1개 이상의 토큰이 기존 텍스트나 검색 보조 데이터에 매칭된 이벤트만 결과에 포함합니다.
- 사진 이벤트(`EventCategory.PHOTO`)는 계속 검색 대상에서 제외합니다.

OR 조건은 결과 후보를 넓히기 위한 장치입니다. 최종 결과 품질은 필드별 점수와 최소 점수 기준으로 제어합니다.

초기 필터 기준:

- 검색 토큰이 1개인 경우: 1개 이상 매칭되면 후보에 포함
- 검색 토큰이 2개 이상인 경우: 1개 이상 매칭되되, 관련도 점수가 최소 기준 이상이어야 함
- 권장 초기 최소 점수: `3`
- `relatedKeywords`만 1개 매칭되어 점수 `1`인 이벤트는 결과에서 제외
- 원문(`content`, `longContent`, `rawExcerpt`) 또는 별칭(`searchAliases`)에 매칭된 이벤트는 우선 후보로 인정

### 5. 문맥 검색 관련도 점수
문맥 검색 결과에는 내부 관련도 점수를 부여합니다.

초기 점수 예시:

- `content` 직접 매칭: +5
- `longContent` / `rawExcerpt` 매칭: +3
- `searchSummary` 매칭: +3
- `searchTags` 매칭: +2
- `searchAliases` 매칭: +3
- `relatedKeywords` 매칭: +1
- 검색어 전체 문구가 포함됨: +4
- 모든 검색 토큰이 어딘가에 매칭됨: +3

점수는 UI에 직접 노출하지 않습니다. 문맥 검색의 `관련도순` 정렬과 디버그 로그에만 사용합니다.

점수 계산 시 같은 토큰이 같은 필드 안에서 여러 번 등장해도 무한히 가산하지 않습니다. 초기 구현은 `토큰 x 필드` 단위로 한 번만 가산합니다. 이렇게 해야 긴 원문이나 긴 검색 보조 데이터가 과도하게 유리해지는 문제를 줄일 수 있습니다.

관련도순 정렬의 tie-breaker는 기존 타임라인 정렬과 동일한 날짜순을 사용합니다.

---

## 토큰 및 비용 관리

### 기본 판단
이 방식은 기존 Gemini 이벤트 추출 요청에 검색 보조 데이터를 추가하는 구조이므로, 입력 토큰이 크게 늘기보다는 출력 토큰이 증가합니다.

앱에 ONNX 모델을 포함하는 방식과 비교하면 APK/AAB 용량 부담은 크게 줄어듭니다. 대신 이벤트 생성과 백필 시 Gemini 출력 토큰이 늘어나므로, 생성량 제한이 필요합니다.

### 출력 토큰 제한
검색 보조 데이터는 다음 범위 안에서 생성합니다.

```text
searchSummary: 짧은 1문장
searchTags: 0~10개
searchAliases: 0~5개
relatedKeywords: 0~12개
```

Gemini 프롬프트에는 다음 규칙을 명시합니다.

- 모든 필드를 억지로 채우지 않습니다.
- 근거가 부족하면 빈 배열을 반환합니다.
- 같은 의미의 표현을 반복하지 않습니다.
- 너무 일반적인 단어는 피합니다. 예: `아이`, `일상`, `기록`, `오늘`
- 검색에 실질적으로 도움이 되는 표현만 포함합니다.

### 백필 비용 관리
기존 이벤트에 검색 보조 인덱스를 생성하는 백필은 Gemini API 사용량을 늘릴 수 있습니다.

현재 앱은 아직 배포 전이며, 기능 완료 후 배포할 예정입니다. 따라서 ENH-012 구현 시점에는 검색 보조 데이터가 없는 기존 사용자 이벤트를 자동으로 마이그레이션할 필요가 없습니다.

기본 전략:

- 앱 시작 시 기존 이벤트 전체 백필을 자동 실행하지 않습니다.
- 신규 이벤트부터 검색 보조 데이터를 생성합니다.
- 검색 보조 데이터가 없는 과거 이벤트가 있더라도 일반 검색과 원문 기반 문맥 검색 후보 매칭은 계속 동작해야 합니다.

업데이트 이후를 위한 수동 전략:

- 옵션 화면에 `문맥 업데이트` 액션을 제공합니다.
- `문맥 업데이트`는 앱 전체의 최신 완료 검색 인덱스 버전을 확인한 뒤 실행 여부를 결정합니다.
- 최신 완료 검색 인덱스 버전이 현재 앱의 `SEARCH_CONTEXT_INDEX_VERSION`과 같으면 아무 작업도 수행하지 않습니다.
- 최신 완료 검색 인덱스 버전이 현재 앱의 `SEARCH_CONTEXT_INDEX_VERSION`보다 낮으면, 오래된 문맥 컨텐츠를 가진 텍스트 이벤트를 batch 단위로 업데이트합니다.
- 백필은 한 번에 전체를 처리하지 않고 batch 단위로 나눕니다.
- batch 기반 처리는 이벤트 1개당 Gemini 요청 1번을 보내는 방식이 아닙니다.
- 여러 이벤트를 하나의 요청에 묶어 보내고, Gemini가 이벤트 id별 검색 보조 데이터를 배열로 반환하도록 합니다.
- 성공한 이벤트는 현재 검색 인덱스 버전으로 갱신합니다.
- 모든 대상 이벤트가 성공적으로 갱신된 경우에만 앱 전체의 최신 완료 검색 인덱스 버전을 현재 버전으로 저장합니다.
- 사용자가 취소할 수 있도록 합니다.
- 실패한 이벤트는 재시도 대상으로 남기되, 일반 검색 기능은 계속 동작해야 합니다.

권장 batch 크기:

- 초기값: 요청당 이벤트 10개
- 이벤트 원문이 긴 경우: 요청당 3~5개로 축소
- Gemini 응답 파싱 실패 또는 토큰 초과가 발생하면 batch 크기를 줄여 재시도

batch 응답 예시:

```json
{
  "items": [
    {
      "eventId": "event-1",
      "searchSummary": "아이의 고열과 병원 진료에 관한 기록",
      "searchTags": ["고열", "병원", "진료"],
      "searchAliases": ["아이가 아팠던 날", "병원 간 날"],
      "relatedKeywords": ["열", "간호", "걱정"]
    },
    {
      "eventId": "event-2",
      "searchSummary": "새 음식을 처음 먹어본 기록",
      "searchTags": ["이유식", "식사", "첫 시도"],
      "searchAliases": ["처음 먹은 음식", "새 음식 먹은 날"],
      "relatedKeywords": ["입맛", "반응", "먹기"]
    }
  ]
}
```

---

## 정렬 UX

### 기본 원칙
검색 결과는 타임라인 앱의 성격을 유지하기 위해 기본적으로 날짜순을 사용합니다.

단, 문맥 검색은 OR 기반 확장 검색으로 결과 범위가 넓어질 수 있으므로, 사용자가 필요할 때 관련도순으로 전환할 수 있어야 합니다.

### 노출 조건
정렬 토글은 UX를 깨지 않도록 제한적으로 노출합니다.

- 검색어가 비어 있으면 표시하지 않습니다.
- `문맥 포함` OFF인 일반 키워드 검색에서는 표시하지 않습니다.
- `문맥 포함` ON이고 검색 결과가 표시되는 동안에만 표시합니다.

### 배치 제안
기존 검색 결과 안내 영역 근처에 작은 segmented control을 배치합니다.

현재 검색 중 표시가 있다면:

```text
검색 중: "병원" (문맥) · 12건
```

그 아래 또는 같은 행의 우측에 다음 토글을 작게 배치합니다.

```text
[날짜순] [관련도순]
```

권장 기본값:

- 기본: `날짜순`
- 사용자가 문맥 검색 중에만 `관련도순` 선택 가능
- 검색을 종료하면 정렬 상태는 기본값인 `날짜순`으로 되돌립니다.

주의:

- 검색 다이얼로그 안에는 정렬 토글을 넣지 않습니다.
- 검색을 실행하기 전 설정 항목처럼 보이게 만들지 않습니다.
- 검색 결과를 보고 나서 전환하는 상단 보조 컨트롤로 취급합니다.

---

## 데이터 모델 설계

초기 구현은 구조화 필드를 권장합니다.

`EventEntity` 후보 필드:

```kotlin
val searchSummary: String = "",
val searchTagsJson: String = "[]",
val searchAliasesJson: String = "[]",
val relatedKeywordsJson: String = "[]",
val searchContextVersion: Int = SEARCH_CONTEXT_INDEX_VERSION
```

모든 텍스트 이벤트는 생성 시점부터 문맥 컨텐츠를 가져야 합니다.

- 카카오 가져오기 이벤트
- 실패 chunk 재시도 이벤트
- 수동 텍스트 이벤트

위 경로에서 생성되는 모든 텍스트 이벤트는 현재 `SEARCH_CONTEXT_INDEX_VERSION` 기준의 검색 보조 데이터를 저장합니다.

대안으로 검색 전용 통합 필드를 둘 수도 있습니다.

```kotlin
val aiSearchText: String = ""
```

권장:

- 저장은 구조화 필드로 합니다.
- 검색 시에는 구조화 필드를 합쳐 `generatedSearchText` 형태로 사용합니다.
- 향후 UI에서 태그를 노출하거나 재생성 상태를 관리할 가능성을 고려해 단일 문자열만 저장하는 방식은 피합니다.

---

## Gemini 프롬프트 방향

Gemini 이벤트 추출 프롬프트에 검색 보조 데이터 생성을 추가합니다.

중요 규칙:

- 원문에서 합리적으로 추론 가능한 내용만 생성합니다.
- 새로운 사건, 장소, 질병명, 인물명을 지어내지 않습니다.
- 사용자가 나중에 검색할 법한 한국어 표현을 포함합니다.
- 태그는 짧고 검색 친화적인 명사/구 중심으로 생성합니다.
- 별칭은 자연어 검색 문장 형태를 허용합니다.
- 민감하거나 확정할 수 없는 의학적 진단명은 단정하지 않습니다.
- 모든 필드를 반드시 채우려고 하지 않습니다.
- 검색 보조 데이터는 많을수록 좋은 것이 아니라 정확할수록 좋습니다.
- 일반적이고 의미가 약한 단어는 제외합니다.
- 질병명, 장소명, 인물명은 원문에 명확한 근거가 있을 때만 포함합니다.
- 증상 표현은 단정적 진단명이 아니라 관찰 가능한 표현을 우선합니다. 예: `열`, `기침`, `컨디션 저하`

반환 JSON 예시:

```json
{
  "content": "고열로 병원 방문",
  "longContent": "아이가 밤새 열이 나고 컨디션이 좋지 않아 병원에 다녀온 기록입니다.",
  "searchSummary": "아이의 고열과 병원 진료에 관한 기록",
  "searchTags": ["고열", "병원", "진료", "아픔", "컨디션"],
  "searchAliases": ["아이가 아팠던 날", "열났던 날", "병원 간 날"],
  "relatedKeywords": ["감기", "간호", "걱정", "진찰"]
}
```

프롬프트 지시문 예시:

```text
다음 이벤트를 사용자가 나중에 검색할 때 찾을 수 있도록 검색 보조 데이터를 생성하세요.

규칙:
- 원문에서 합리적으로 추론 가능한 표현만 포함하세요.
- 새로운 사실, 장소, 인물, 질병명을 만들어내지 마세요.
- 확정할 수 없는 의학적 진단명은 넣지 마세요.
- 근거가 부족한 필드는 빈 배열로 두세요.
- 검색에 도움 되지 않는 일반 단어는 제외하세요.
- 같은 의미의 표현을 반복하지 마세요.
- 한국어 중심으로 작성하세요.

길이 제한:
- searchSummary: 80자 이내 1문장
- searchTags: 최대 10개
- searchAliases: 최대 5개
- relatedKeywords: 최대 12개
```

---

## 구현 설계

### 1. 임베딩 기반 문맥 검색 제거
ENH-012에서는 ONNX/임베딩 기반 문맥 검색을 더 이상 사용하지 않습니다. 기존 임베딩 기반 구현과 리소스는 깔끔하게 제거합니다.

제거 대상:

- `app/src/main/assets/minilm/model_qint8_arm64.onnx`
- `app/src/main/assets/minilm/tokenizer.json`
- `app/src/main/assets/minilm/tokenizer_config.json`
- `app/src/main/assets/minilm/sentencepiece.bpe.model`
- ONNX Runtime Android 의존성
- DJL HuggingFace Tokenizers 의존성
- Android tokenizer native runtime 의존성
- `TextEmbeddingEngine`의 ONNX/tokenizer/session 기반 구현
- 앱 시작 시 실행되는 임베딩 self-test
- 앱 시작 시 실행되는 텍스트 임베딩 backfill
- 검색 시 query embedding을 생성하고 cosine similarity를 계산하는 경로
- 신규 이벤트 생성 시 `textEmbeddingJson`을 생성하는 경로

정리 원칙:

- 문맥 검색의 기본 경로는 Gemini 검색 보조 인덱스 기반 텍스트 검색으로 교체합니다.
- 앱 패키지에 대형 텍스트 임베딩 모델을 포함하지 않습니다.
- 사용되지 않는 ONNX/DJL 의존성은 `build.gradle.kts`와 버전 카탈로그에서 제거합니다.
- `textEmbeddingJson`은 새 문맥 검색에서 사용하지 않습니다.
- DB 컬럼 제거는 Room 마이그레이션 부담을 고려해 별도 정리 작업으로 분리할 수 있지만, 신규 코드에서는 더 이상 읽거나 쓰지 않습니다.

### 2. DB 마이그레이션
- `EventEntity`에 검색 보조 필드를 추가합니다.
- 검색 보조 인덱스 버전 필드를 추가합니다.
- 기존 이벤트는 빈 값으로 마이그레이션합니다.
- 앱 시작 시 기존 이벤트용 검색 보조 데이터를 자동 생성하지 않습니다.
- 향후 필요 시 옵션의 `문맥 업데이트` 액션으로 수동 생성합니다.
- 앱 전체의 최신 완료 검색 인덱스 버전은 DataStore 등에 별도로 저장합니다.

전역 버전 저장 후보:

```kotlin
val SEARCH_CONTEXT_INDEX_COMPLETED_VERSION =
    intPreferencesKey("search_context_index_completed_version")
```

의미:

- `SEARCH_CONTEXT_INDEX_VERSION`: 현재 앱 코드가 요구하는 검색 인덱스 버전
- `searchContextVersion`: 개별 이벤트가 가진 문맥 컨텐츠 버전
- `SEARCH_CONTEXT_INDEX_COMPLETED_VERSION`: 전체 텍스트 이벤트에 대해 마지막으로 성공 완료된 검색 인덱스 버전

### 3. 이벤트 생성 경로 업데이트
다음 경로에서 검색 보조 데이터를 생성/저장합니다.

- 카카오 가져오기 이벤트 생성
- 실패 chunk 재시도 이벤트 생성
- 수동 텍스트 이벤트 추가

최초 문맥 컨텐츠 생성은 카카오톡 임포팅 과정에서 수행합니다.

카카오톡 임포팅 흐름:

1. 카카오 메시지 chunk를 Gemini에 전달합니다.
2. Gemini가 이벤트를 추출합니다.
3. 각 텍스트 이벤트에 대해 `content`, `longContent`, `rawExcerpt`와 함께 검색 보조 데이터를 생성합니다.
4. 앱은 이벤트 저장 시 `searchSummary`, `searchTagsJson`, `searchAliasesJson`, `relatedKeywordsJson`, `searchContextVersion`을 함께 저장합니다.

즉, 정상적인 신규 카카오톡 임포팅 이벤트는 저장되는 순간부터 현재 버전의 문맥 컨텐츠를 가지고 있어야 합니다.

수동 이벤트는 Gemini 호출 비용과 지연을 고려해 다음 중 하나를 선택할 수 있습니다.

- 초기 버전: 수동 이벤트는 `content` 기반 간단 태그만 로컬 생성
- 확장 버전: 수동 이벤트 저장 후 백그라운드에서 Gemini 검색 인덱스 생성

ENH-012의 기본 전제는 모든 텍스트 이벤트가 문맥 컨텐츠를 가진다는 것이므로, 수동 텍스트 이벤트도 최소한의 문맥 컨텐츠를 가져야 합니다.

권장 초기 정책:

- 카카오/재시도 이벤트: Gemini 응답에서 검색 보조 데이터를 함께 생성
- 수동 텍스트 이벤트: 저장 시 로컬 fallback 검색 보조 데이터를 생성
- 수동 이벤트의 로컬 fallback:
  - `searchSummary = content`
  - `searchTagsJson = []`
  - `searchAliasesJson = []`
  - `relatedKeywordsJson = []`
  - `searchContextVersion = SEARCH_CONTEXT_INDEX_VERSION`

이렇게 하면 수동 이벤트도 현재 버전의 문맥 컨텐츠를 가진 것으로 취급할 수 있고, 향후 더 좋은 수동 이벤트 인덱싱 정책이 생기면 `SEARCH_CONTEXT_INDEX_VERSION`을 올려 `문맥 업데이트` 대상으로 만들 수 있습니다.

### 4. Gemini 응답 구조와 실패 격리
현재 Gemini 이벤트 추출은 JSON 배열을 파싱해 `ExtractedEvent` 목록으로 변환합니다. ENH-012에서는 응답 스키마를 확장해야 합니다.

`ExtractedEvent` 후보 필드:

```kotlin
data class ExtractedEvent(
    val date: LocalDate,
    val category: EventCategory,
    val content: String,
    val longContent: String? = null,
    val rawExcerpt: String? = null,
    val searchSummary: String = "",
    val searchTags: List<String> = emptyList(),
    val searchAliases: List<String> = emptyList(),
    val relatedKeywords: List<String> = emptyList()
)
```

Gemini 응답 파싱 원칙:

- 이벤트 필수 필드(`date`, `category`, `content`)가 없으면 해당 이벤트는 버립니다.
- 검색 보조 필드가 없거나 파싱에 실패해도 이벤트 전체를 버리지 않습니다.
- 검색 보조 필드가 깨진 경우 로컬 fallback 문맥 컨텐츠를 넣습니다.
- 필드별 최대 개수와 최대 길이는 파싱 후 후처리에서도 한 번 더 강제합니다.
- 중복 태그/키워드는 제거합니다.
- 빈 문자열은 제거합니다.

fallback 정책:

```text
searchSummary = content
searchTags = []
searchAliases = []
relatedKeywords = []
searchContextVersion = SEARCH_CONTEXT_INDEX_VERSION
```

이 정책은 “검색 보조 데이터 생성 실패가 이벤트 생성 전체 실패로 이어지지 않는다”는 수용 기준을 만족하기 위한 필수 구현입니다.

### 5. 문맥 검색 로직 교체
`TimelineViewModel`의 문맥 검색 경로를 임베딩 기반에서 텍스트 확장 인덱스 기반으로 교체합니다.

흐름:

1. 검색어 토큰화
2. 각 이벤트의 원본 검색 텍스트와 Gemini 검색 보조 텍스트 구성
3. 토큰 OR 매칭
4. 관련도 점수 계산
5. 정렬 모드에 따라 날짜순 또는 관련도순으로 결과 반환

초기 구현 예시:

```kotlin
data class ContextSearchCandidate(
    val event: Event,
    val score: Int
)

private fun shouldIncludeContextResult(
    tokenCount: Int,
    score: Int,
    matchedPrimaryOrAlias: Boolean
): Boolean {
    if (score <= 0) return false
    if (tokenCount <= 1) return score >= 2 || matchedPrimaryOrAlias
    return score >= 3 || matchedPrimaryOrAlias
}
```

여기서 `matchedPrimaryOrAlias`는 다음 필드 중 하나에 검색 토큰이 매칭된 경우 `true`로 둡니다.

- `content`
- `longContent`
- `rawExcerpt`
- `searchAliases`

`relatedKeywords` 단독 매칭은 후보 확장에는 기여하지만, 낮은 점수 때문에 결과 오염을 만들지 않도록 합니다.

### 6. 옵션의 문맥 업데이트 액션
옵션 화면에 `문맥 업데이트` 액션을 추가할 수 있도록 설계합니다.

동작:

1. DataStore 등에 저장된 `SEARCH_CONTEXT_INDEX_COMPLETED_VERSION`을 확인합니다.
2. 완료 버전이 현재 `SEARCH_CONTEXT_INDEX_VERSION`과 같으면 작업하지 않고 종료합니다.
3. 완료 버전이 현재 버전보다 낮으면, `searchContextVersion < SEARCH_CONTEXT_INDEX_VERSION`인 텍스트 이벤트를 조회합니다.
4. 대상 이벤트를 batch 단위로 묶습니다.
5. 한 Gemini 요청에 여러 이벤트를 포함합니다.
6. 응답은 이벤트 id별 검색 보조 데이터 배열로 받습니다.
7. 성공한 이벤트만 DB에 반영하고 해당 이벤트의 `searchContextVersion`을 현재 버전으로 저장합니다.
8. 모든 대상 이벤트가 성공하면 `SEARCH_CONTEXT_INDEX_COMPLETED_VERSION`을 현재 버전으로 저장합니다.
9. 실패한 batch가 있거나 사용자가 취소하면 전역 완료 버전은 올리지 않고, 다음 실행에서 남은 이벤트를 다시 대상으로 잡습니다.

주의:

- 이 액션은 ENH-012 최초 배포를 위한 필수 마이그레이션이 아닙니다.
- 앱 업데이트 이후 검색 보조 필드가 없는 이벤트가 생겼을 때 사용자가 직접 실행하는 유지보수 기능입니다.
- 사용자가 취소할 수 있어야 합니다.
- 진행률은 `처리한 이벤트 수 / 대상 이벤트 수` 기준으로 표시합니다.
- 기본 전제는 모든 텍스트 이벤트가 항상 문맥 컨텐츠를 가진다는 것입니다.
- 따라서 정상 상태에서는 전역 완료 버전이 현재 버전과 같으면 이벤트별 검사를 반복하지 않습니다.

검색 인덱스 버전:

```kotlin
const val SEARCH_CONTEXT_INDEX_VERSION = 1
```

`EventEntity` 후보 필드:

```kotlin
val searchContextVersion: Int = 0
```

대상 조건:

```text
category != PHOTO
AND searchContextVersion < SEARCH_CONTEXT_INDEX_VERSION
```

프롬프트, 필드 구성, 점수 정책, 생성 제한이 바뀌어 기존 검색 보조 데이터를 다시 만들어야 할 때는 `SEARCH_CONTEXT_INDEX_VERSION`을 올립니다. 그러면 `문맥 업데이트`가 오래된 이벤트를 다시 대상으로 잡을 수 있습니다.

전역 완료 버전 조건:

```text
if SEARCH_CONTEXT_INDEX_COMPLETED_VERSION == SEARCH_CONTEXT_INDEX_VERSION:
    skip
else:
    update stale text events in batches
```

전체 batch 업데이트가 성공한 뒤에만:

```text
SEARCH_CONTEXT_INDEX_COMPLETED_VERSION = SEARCH_CONTEXT_INDEX_VERSION
```

### 7. Repository/DAO 구현 체크리스트
ENH-012 구현 시 도메인 모델, DB 엔티티, DAO, Repository 매핑을 함께 갱신합니다.

수정 대상:

- `Event`
- `EventEntity`
- `EventRepository`
- `EventRepositoryImpl`
- `EventDao`
- `DodoDatabase`
- `AppPrefsKeys`

필요한 DAO 후보:

```kotlin
@Query("""
    SELECT * FROM events
    WHERE category != 'PHOTO'
    AND searchContextVersion < :currentVersion
    ORDER BY date ASC
""")
suspend fun getEventsNeedingSearchContextUpdate(currentVersion: Int): List<EventEntity>

@Query("""
    UPDATE events
    SET searchSummary = :searchSummary,
        searchTagsJson = :searchTagsJson,
        searchAliasesJson = :searchAliasesJson,
        relatedKeywordsJson = :relatedKeywordsJson,
        searchContextVersion = :searchContextVersion
    WHERE id = :id
""")
suspend fun updateSearchContext(...)
```

Room 마이그레이션:

- 현재 DB version은 7입니다.
- ENH-012에서는 version 8로 올립니다.
- `MIGRATION_7_8`에서 검색 보조 필드와 `searchContextVersion`을 추가합니다.
- `textEmbeddingJson` 컬럼은 신규 코드에서 사용하지 않지만, 즉시 제거하지 않아도 됩니다.

마이그레이션 후보:

```sql
ALTER TABLE events ADD COLUMN searchSummary TEXT NOT NULL DEFAULT '';
ALTER TABLE events ADD COLUMN searchTagsJson TEXT NOT NULL DEFAULT '[]';
ALTER TABLE events ADD COLUMN searchAliasesJson TEXT NOT NULL DEFAULT '[]';
ALTER TABLE events ADD COLUMN relatedKeywordsJson TEXT NOT NULL DEFAULT '[]';
ALTER TABLE events ADD COLUMN searchContextVersion INTEGER NOT NULL DEFAULT 0;
```

DataStore:

- `TEXT_EMBEDDING_MODEL_VERSION`은 제거 후보입니다.
- `SEARCH_CONTEXT_INDEX_COMPLETED_VERSION`을 추가합니다.
- 앱 초기화/리셋 시 DataStore clear 경로에서 함께 초기화됩니다.

### 8. 정렬 상태 추가
UI 상태에 문맥 검색 정렬 모드를 추가합니다.

예시:

```kotlin
enum class ContextSearchSort {
    DATE,
    RELEVANCE
}
```

`TimelineUiState` 후보:

```kotlin
val contextSearchSort: ContextSearchSort = ContextSearchSort.DATE
```

검색 종료 또는 일반 키워드 검색 전환 시 `DATE`로 초기화합니다.

---

## 작업 범위 (Scope)

포함:

- Gemini 검색 보조 데이터 스키마 정의
- DB 필드 및 마이그레이션 추가
- Gemini 이벤트 생성 응답 구조 확장
- 문맥 검색 로직을 확장 텍스트 인덱스 기반으로 교체
- 문맥 검색 한정 `날짜순 / 관련도순` 정렬 토글 추가
- ONNX/임베딩 기반 문맥 검색 경로 제거
- ONNX/DJL 관련 앱 리소스 및 의존성 제거
- 옵션의 `문맥 업데이트` 수동 batch 액션 설계
- 검색 보조 인덱스 버전 관리
- 앱 전체 최신 완료 검색 인덱스 버전 관리

제외:

- 서버 임베딩
- 벡터 DB
- 온디바이스 대형 임베딩 모델
- 검색 결과 별도 페이지
- 사진 이미지 내용 기반 검색
- 앱 시작 시 기존 이벤트 전체 자동 백필

---

## 수용 기준 (Acceptance Criteria)

### 기능 기준
- `문맥 포함` OFF에서는 기존 일반 키워드 검색이 유지된다.
- `문맥 포함` ON에서는 Gemini 검색 보조 데이터가 검색 대상에 포함된다.
- 일반 키워드 검색에서는 Gemini 검색 보조 데이터가 사용되지 않는다.
- 문맥 검색은 OR 기반 확장 매칭으로 동작한다.
- 문맥 검색 결과는 기본 날짜순으로 표시된다.
- 문맥 검색 결과 화면에서만 `날짜순 / 관련도순` 정렬 토글이 표시된다.
- `관련도순` 선택 시 내부 점수가 높은 결과가 먼저 표시된다.
- 사진 이벤트는 일반 검색과 문맥 검색 모두에서 제외된다.

### UX 기준
- 검색 다이얼로그의 기본 구조는 유지된다.
- 정렬 토글은 검색 다이얼로그 내부가 아니라 검색 결과 표시 영역 근처에 노출된다.
- 일반 키워드 검색 중에는 정렬 토글이 보이지 않는다.
- 검색 종료 시 정렬은 날짜순으로 초기화된다.

### 품질 기준
- `아이가 아팠던 날` 검색 시 `고열`, `병원`, `컨디션`, `진료` 등으로 인덱싱된 이벤트가 문맥 검색에서 발견된다.
- `병원 밥 안먹음`처럼 여러 토큰이 있는 검색어는 일부 토큰만 맞아도 문맥 검색 결과에 포함될 수 있지만, 더 많은 토큰이 맞은 이벤트가 관련도순에서 더 위에 표시된다.
- Gemini가 생성한 검색 보조 데이터는 원문에서 합리적으로 추론 가능한 범위를 넘지 않는다.
- `relatedKeywords` 하나만 약하게 매칭된 이벤트가 대량으로 노출되지 않는다.
- 같은 검색어에서 날짜순과 관련도순 전환 시 결과 집합은 유지되고 순서만 바뀐다.
- 검색 보조 데이터가 비어 있는 기존 이벤트도 기존 원문 텍스트 매칭으로 문맥 검색 후보가 될 수 있다.
- 정상 생성된 모든 텍스트 이벤트는 현재 버전의 문맥 컨텐츠를 가진다.
- 문맥 업데이트가 전체 성공하면 앱 전체 최신 완료 검색 인덱스 버전이 현재 버전으로 저장된다.

### 비용 기준
- 검색 보조 데이터 생성은 필드별 최대 개수를 지킨다.
- 신규 이벤트 생성 시 검색 보조 데이터 때문에 응답 JSON이 과도하게 길어지지 않는다.
- 기존 이벤트 백필은 앱 시작 시 자동 실행되지 않는다.
- 옵션의 `문맥 업데이트`는 앱 전체 최신 완료 검색 인덱스 버전이 현재 버전과 같으면 실행되지 않는다.
- 옵션의 `문맥 업데이트`는 검색 인덱스 버전이 오래된 이벤트를 대상으로 한다.
- 옵션의 `문맥 업데이트`는 여러 이벤트를 한 요청으로 묶는 batch 기반으로 실행된다.
- 검색 보조 데이터 생성 실패가 이벤트 생성 전체 실패로 이어지지 않는다.

---

## 리스크 (Risks)

- Gemini가 검색 태그를 누락하면 문맥 검색 recall이 낮아질 수 있습니다.
- OR 기반 검색은 결과가 넓어질 수 있으므로 관련도 점수와 정렬 토글이 중요합니다.
- 기존 이벤트 백필에는 Gemini API 비용과 시간이 발생합니다.
- 검색 보조 데이터가 과도하면 엉뚱한 검색 결과가 늘어날 수 있습니다.
- 의료/건강 관련 표현은 Gemini가 확정적 진단명으로 과장하지 않도록 프롬프트 제약이 필요합니다.

완화 방안:

- 필드별 최대 길이와 최대 개수를 강제합니다.
- 근거가 부족한 필드는 빈 배열을 허용합니다.
- 너무 일반적인 단어는 프롬프트와 후처리에서 제거합니다.
- 최소 관련도 점수를 두어 약한 OR 매칭을 걸러냅니다.
- 디버그 빌드에서 query, score, matched field를 로그로 남겨 검색 품질을 확인합니다.

---

## 향후 확장

- 검색 보조 인덱스 수동 재생성
- 이벤트별 검색 보조 데이터 생성 상태 표시
- 태그 기반 필터 UI
- 서버 임베딩 기반 관련도 재랭킹
- 로컬 키워드 검색 + 서버 문맥 검색 하이브리드
