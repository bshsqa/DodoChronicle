# ENH-016: 이벤트 Export/URL Import

## 목표 (Objective)
앱에 이미 생성된 이벤트를 하나의 파일로 내보내고, 다른 사용자가 해당 파일의 다운로드 가능한 링크를 입력하거나 로컬 파일을 선택하면 같은 이벤트를 자신의 앱으로 가져올 수 있게 합니다.

사용자에게 보이는 메뉴명은 단순하게 `이벤트 내보내기 / 이벤트 가져오기`로 합니다.

단, 초기 구현 범위는 사진 이벤트를 제외한 텍스트 이벤트 스냅샷입니다. 이 기능은 카카오톡 원본 대화를 다시 분석하는 기능이 아니라, DodoChronicle이 이미 만든 텍스트 이벤트를 공유/복원하는 기능입니다.

대상 이슈:

- #31 이벤트 데이터를 하나의 공유 파일로 만들고 업로딩/임포팅 가능하도록

---

## 배경 (Background)
현재 앱은 카카오톡 대화를 Gemini로 분석해 날짜별 텍스트 이벤트를 생성합니다. 이 과정은 API 키, 토큰 비용, 분석 시간, 실패 chunk 처리 같은 부담이 있습니다.

반면 이미 생성된 텍스트 이벤트는 구조화된 데이터이므로, 이를 파일로 내보낸 뒤 다른 앱 인스턴스에서 다시 import하면 카카오톡 재분석 없이 같은 타임라인을 재현할 수 있습니다.

공유 흐름 예시:

1. 사용자 A가 `이벤트 내보내기`를 실행합니다.
2. 앱이 `.json` 파일을 생성합니다.
3. 사용자 A가 파일을 Google Drive 등에 업로드하고 링크 공유를 켭니다.
4. 사용자 B가 앱의 `이벤트 가져오기`에서 링크를 입력하거나 저장된 파일을 직접 선택합니다.
5. 앱이 파일을 읽고 텍스트 이벤트를 import합니다.

---

## 주요 원칙

### 1. 텍스트 이벤트만 포함
Export 대상은 사진 이벤트를 제외한 텍스트 이벤트입니다.

포함:

- `SAID`
- `DID`
- `OTHER`

제외:

- `PHOTO`
- `PhotoRecord`
- `PendingPhoto`
- `RejectedPhoto`
- 얼굴 embedding
- MediaStore URI
- 앱 내부 상태성 데이터

사진은 기기 로컬 URI와 얼굴 embedding에 의존하므로, 공유 파일에 포함하지 않습니다.

UI에서는 `이벤트`라는 넓은 이름을 쓰지만, v1 export 파일에는 `PHOTO`가 포함되지 않는다는 점을 구현과 문서에 명확히 둡니다.

### 2. 검색 보조 인덱스 포함
Export 파일에는 ENH-012의 Gemini 검색 보조 데이터를 포함합니다.

포함 필드:

- `searchSummary`
- `searchTags`
- `searchAliases`
- `relatedKeywords`
- `searchContextVersion`

이렇게 하면 import 후에도 문맥 검색이 바로 동작합니다.

단, 앱의 현재 `SEARCH_CONTEXT_INDEX_VERSION`보다 export 파일의 `searchContextVersion`이 낮으면, 이후 `문맥 업데이트` 대상이 될 수 있습니다.

### 3. 원본 카카오톡 데이터는 포함하지 않음
Export 파일에는 `rawExcerpt`를 포함할 수 있지만, 카카오톡 전체 원문이나 메시지 DB는 포함하지 않습니다.

포함 후보:

- 이벤트별 `rawExcerpt`
- 이벤트별 `longContent`

제외:

- 전체 카카오톡 메시지 원문
- KakaoRoom/KakaoMessage 테이블
- RetryChunk

이유:

- 공유 파일 크기를 줄입니다.
- 개인정보 노출 범위를 줄입니다.
- import 목적은 재분석이 아니라 타임라인 복원입니다.

---

## Export 파일 형식

초기 구현은 JSON 파일을 사용합니다.

권장 확장자:

```text
dodochronicle-events.json
```

최상위 구조 예시:

```json
{
  "format": "dodochronicle.events",
  "schemaVersion": 1,
  "exportedAt": 1760000000000,
  "appVersion": "1.0.0",
  "child": {
    "name": "도도",
    "birthDate": "2024-01-01"
  },
  "events": [
    {
      "stableKey": "2024-05-01|SAID|오늘 처음으로 엄마라고 말함",
      "date": "2024-05-01",
      "category": "SAID",
      "content": "오늘 처음으로 엄마라고 말함",
      "longContent": "아이가 엄마라는 말을 또렷하게 말한 기록입니다.",
      "rawExcerpt": "엄마라고 했어!",
      "isFavorite": false,
      "isHidden": false,
      "createdAt": 1760000000000,
      "searchSummary": "처음으로 엄마라고 말한 기록",
      "searchTags": ["엄마", "말", "첫말"],
      "searchAliases": ["처음 엄마라고 한 날"],
      "relatedKeywords": ["언어", "성장"],
      "searchContextVersion": 1
    }
  ]
}
```

### ID 정책
Export 파일에는 앱 내부 `event.id`를 그대로 신뢰하지 않습니다.

권장:

- Export 시 `stableKey`를 생성합니다.
- Import 시 `stableKey` 기반으로 중복을 판단합니다.
- 실제 DB 저장 시에는 새 `UUID`를 발급합니다.

초기 `stableKey` 후보:

```text
date|category|normalizedContent
```

필요하면 후속 버전에서 hash를 추가합니다.

---

## Import 동작

### 1. 가져오기 진입
설정 메뉴에 다음 항목을 추가합니다.

```text
이벤트 내보내기
이벤트 가져오기
```

`이벤트 가져오기`를 누르면 URL 입력과 로컬 파일 선택을 함께 제공하는 다이얼로그를 표시합니다.

제공 액션:

- `파일 선택`: Android 파일 선택기로 export JSON을 직접 고릅니다.
- `링크 가져오기`: 다운로드 가능한 HTTPS URL 또는 Google Drive 공유 링크를 입력해 가져옵니다.

예시:

```text
공유 링크 입력
[ https://drive.google.com/...                 ]
```

### 2. 로컬 파일 선택
앱은 Android `OpenDocument`를 사용해 사용자가 export JSON 파일을 직접 선택할 수 있게 합니다.

특징:

- 네트워크 연결이 필요 없습니다.
- Google Drive 공유 권한 문제를 피할 수 있습니다.
- 선택한 파일의 내용을 읽은 뒤 URL import와 동일한 JSON 검증/중복 제거/저장 경로를 사용합니다.

### 3. URL 다운로드
앱은 입력된 URL에서 JSON 파일을 다운로드합니다.

지원 범위:

- 직접 다운로드 가능한 HTTPS URL
- Google Drive 공유 링크 best-effort 변환

Google Drive 공유 링크는 일반 웹 페이지 링크일 수 있으므로, 다음 형태를 인식해 다운로드 URL로 변환합니다.

```text
https://drive.google.com/file/d/{fileId}/view?usp=sharing
https://drive.google.com/open?id={fileId}
```

변환 후보:

```text
https://drive.google.com/uc?export=download&id={fileId}
```

주의:

- 대용량 파일, 바이러스 검사 확인 페이지, 권한 없는 링크는 실패할 수 있습니다.
- 초기 구현은 Google Drive 링크를 best-effort로 지원하고, 실패 시 사용자가 직접 다운로드 가능한 링크를 넣도록 안내합니다.

### 3. 검증
다운로드한 JSON은 저장 전에 검증합니다.

검증 항목:

- `format == "dodochronicle.events"`
- 지원 가능한 `schemaVersion`
- `events` 배열 존재
- 각 이벤트의 `date`, `category`, `content` 유효성
- `category != PHOTO`
- 날짜 파싱 가능

잘못된 이벤트는 전체 import를 중단하기보다 가능한 범위에서 skip하는 방식을 권장합니다.

초기 정책:

- 파일 자체가 잘못됨: import 실패
- 일부 이벤트가 잘못됨: 해당 이벤트 skip, 완료 메시지에 skip 수 표시

### 4. 저장 정책
Import된 이벤트는 현재 앱의 첫 번째 child에 저장합니다.

초기 구현 전제:

- 앱은 현재 단일 child 중심 UX입니다.
- import 대상 child를 별도로 고르는 UI는 만들지 않습니다.

저장 필드:

- `childId`: 현재 child id
- `date`
- `category`
- `content`
- `longContent`
- `rawExcerpt`
- `isFavorite`
- `isHidden`
- `source`
- `createdAt`
- `searchSummary`
- `searchTags`
- `searchAliases`
- `relatedKeywords`
- `searchContextVersion`

`source` 정책:

- 초기 구현은 `EventSource.MANUAL` 또는 신규 source 없이 기존 enum 안에서 처리합니다.
- 권장 초기값: `EventSource.MANUAL`
- 후속으로 `EventSource.IMPORTED`가 필요하면 DB/source migration을 별도 ENH로 둡니다.

### 5. 중복 처리
Import 시 동일 이벤트가 중복으로 들어가지 않아야 합니다.

중복 판단 후보:

```text
date + category + normalized content
```

초기 구현:

- 현재 child의 기존 텍스트 이벤트를 조회합니다.
- 기존 이벤트와 `stableKey`가 같으면 skip합니다.
- 새 이벤트만 insert합니다.

완료 메시지 예시:

```text
이벤트 142개를 가져왔습니다. 중복 12개, 오류 1개는 제외했습니다.
```

---

## Export 동작

### 1. 파일 생성
설정 메뉴의 `이벤트 내보내기`를 누르면 현재 child의 텍스트 이벤트를 JSON으로 직렬화합니다.

파일명 후보:

```text
dodochronicle-events-2026-05-05.json
```

초기 구현은 Android `CreateDocument`를 사용해 사용자가 저장 위치를 선택하게 합니다.

장점:

- 외부 저장소 권한 부담이 적습니다.
- 사용자가 바로 Drive/Downloads 등에 저장할 수 있습니다.

### 2. 내보내기 대상
현재 child의 이벤트 중 `PHOTO`가 아닌 것만 포함합니다.

숨김 이벤트:

- `isHidden`도 함께 export합니다.
- import 후에도 숨김 상태가 유지됩니다.

즐겨찾기:

- `isFavorite`도 함께 export합니다.

---

## UI/UX

### 설정 메뉴
설정 메뉴 안에 다음 항목을 추가합니다.

권장 배치:

```text
카카오톡 대화 가져오기
대화 분석 재시도
신규 사진 로딩
사진 원본 확인
문맥 업데이트
이벤트 내보내기
이벤트 가져오기
숨김 아이템
앱 데이터 초기화
```

### Import 진행 상태
Import 중에는 짧은 loading overlay 또는 기존 `isLoading` overlay를 재사용합니다.

문구 예시:

```text
이벤트 가져오는 중...
```

사용자-facing 문구는 가능하면 `이벤트 가져오는 중...`을 사용합니다.

### 실패 메시지
예시:

```text
파일을 다운로드할 수 없습니다.
지원하지 않는 export 파일입니다.
가져올 수 있는 텍스트 이벤트가 없습니다.
```

---

## 구현 설계

### 1. Export DTO
새 파일 후보:

```text
domain/model/EventArchive.kt
```

또는 presentation/data 경계에 가까운 DTO로 둡니다.

후보:

```kotlin
@Serializable
data class EventArchive(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val child: ExportedChild,
    val events: List<ExportedEvent>
)
```

### 2. UseCase
후보:

```kotlin
class ExportTextEventsUseCase
class ImportTextEventsUseCase
```

역할:

- export: 현재 child의 텍스트 이벤트 조회 및 JSON 생성
- import: URL 다운로드, JSON 파싱, 검증, 중복 제거, DB 저장

### 3. Repository 확장
필요 메서드:

```kotlin
suspend fun getAllTextEvents(childId: String): List<Event>
suspend fun insertAll(events: List<Event>)
```

`getAllTextEvents`와 `insertAll`은 이미 존재하므로 재사용 가능성이 높습니다.

### 4. URL 다운로드 / 로컬 파일 읽기
기존 OkHttp DI를 재사용합니다.

주의:

- HTTPS만 허용하는 것을 권장합니다.
- 응답 크기 제한을 둡니다.
- JSON이 너무 큰 경우 실패 처리합니다.

초기 제한 후보:

```text
최대 10MB
```

텍스트 이벤트 수천 개 수준이면 충분합니다.

---

## 수용 기준 (Acceptance Criteria)

### Export
- 설정에서 이벤트를 JSON 파일로 내보낼 수 있다.
- 사진 이벤트는 export 파일에 포함되지 않는다.
- 검색 보조 인덱스 필드가 포함된다.
- 숨김/즐겨찾기 상태가 보존된다.

### Import
- 사용자가 URL을 입력해 export JSON을 가져올 수 있다.
- 직접 다운로드 가능한 URL에서 import가 동작한다.
- 로컬 JSON 파일 선택으로도 import가 동작한다.
- Google Drive 공유 링크를 best-effort로 처리한다.
- import 후 타임라인에 텍스트 이벤트가 날짜별로 표시된다.
- 일반 검색/문맥 검색에서 import된 이벤트가 검색된다.
- 동일 파일을 두 번 import해도 이벤트가 중복 생성되지 않는다.
- 잘못된 이벤트 일부는 skip되고, 결과 메시지에 skip 수가 표시된다.

### 안전성
- 사진 URI/얼굴 embedding은 export/import하지 않는다.
- 잘못된 URL이나 잘못된 파일에서 앱이 crash하지 않는다.
- 지원하지 않는 schemaVersion은 명확한 메시지로 실패한다.

---

## 리스크 (Risks)

- Google Drive 공유 링크는 권한/파일 크기/확인 페이지 때문에 직접 다운로드가 실패할 수 있습니다.
- export 파일에는 아이의 기록이 포함되므로 개인정보 공유 위험이 있습니다.
- stableKey 기반 중복 판단은 같은 날 같은 내용이 반복된 경우 일부 이벤트를 중복으로 오판할 수 있습니다.
- schemaVersion이 올라갈수록 하위 호환 정책이 필요합니다.
- import된 이벤트의 source를 `MANUAL`로 둘 경우, UI에서 원본 출처를 구분하기 어렵습니다.

---

## 향후 확장

- 파일 직접 선택 import
- Drive/Dropbox 등 provider별 안정적인 다운로드 처리
- export 파일 암호화
- import 전 미리보기
- child 선택 import
- `EventSource.IMPORTED` 추가
- 사진 이벤트 export/import는 별도 백업 기능으로 분리
