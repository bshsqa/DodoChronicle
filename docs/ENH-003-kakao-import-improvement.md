# ENH-003: 카카오톡 대화 임포트 개선

## 1. 개요

현재 카카오톡 대화 임포트 기능은 다음과 같은 문제를 안고 있습니다.

1. **파싱 실패**: `KakaoParser`가 PC 내보내기 포맷(첫 줄 `"방이름 N 님과 카카오톡 대화"`)을 기준으로 방 이름을 파싱하므로, **모바일 내보내기**(첫 줄이 날짜+시간으로 시작)에서는 방 이름이 항상 `"알 수 없는 방"`으로 등록되어 중복 방지 로직이 무력화됩니다.
2. **업데이트 무반응**: LLM(Gemini) 호출이 실패하거나 응답이 없어도 사용자에게 아무런 피드백이 없고, 성공하더라도 타임라인 UI가 갱신된다는 느낌을 받기 어렵습니다.
3. **방 관리 부재**: 같은 방의 대화를 나중에 다시 임포트할 때, 기존 방을 식별하는 수단(별명)이 없어 매번 중복 처리가 불완전합니다.
4. **이벤트 상세 정보 부족**: 이벤트 카드에 짧은 요약만 표시되고, 해당 이벤트와 관련된 원본 대화 발췌문을 볼 수 없습니다.
5. **LLM 토큰 낭비**: 모든 메시지를 통째로 LLM에 전달하므로 불필요한 토큰이 소모됩니다.

이 ENH는 위 문제를 전면 해결하여 카카오톡 임포트를 실용적으로 사용할 수 있는 수준으로 개선합니다.

---

## 2. 요구사항

### 2.1. 모바일 내보내기 포맷 파싱 수정

카카오톡 **모바일** 내보내기 파일의 포맷은 다음과 같습니다.

```
2019년 1월 20일 오전 12:46
2019년 1월 20일 오전 12:46, 백다래‥ : 크 액자 많다
2019년 1월 20일 오전 12:47, 엄마 : 아빤
눈때문에 억지 감금 상태고
난 파마했다
```

- **첫 줄**: 방 이름이 아닌 날짜+시간 헤더
- **메시지 줄**: `날짜+시간, 발신자명 : 내용` 형태
- **다중행 메시지**: 발신자 없이 이어지는 줄은 직전 메시지의 연속으로 처리
- **미디어 메시지**: `사진`, `동영상`, `이모티콘` 등 단일 단어만 있는 경우

`KakaoParser`는 모바일 포맷을 올바르게 파싱할 수 있도록 수정되어야 하며, 방 이름은 **사용자가 직접 지정하는 별명**으로 대체합니다.

### 2.2. 방 별명(Room Alias) 관리

- 임포트 시 사용자가 해당 대화방의 **별명을 직접 입력**할 수 있습니다.
- 이전에 임포트한 적 있는 방 목록(DB에 저장된 방 별명 리스트)을 임포트 다이얼로그 하단에 표시하며, 이 중 하나를 **선택**하면 "해당 방의 업데이트된 대화"로 처리합니다.
- 새 방이면 별명을 직접 입력하고, 기존 방이면 목록에서 선택합니다. (둘 중 하나만 활성화)

### 2.3. 중복 메시지 제거 (타임스탬프 기반)

- 기존 방을 선택한 경우, 해당 방에 마지막으로 임포트된 메시지의 `sentAt` 타임스탬프를 기준으로 그 이후 메시지만 처리합니다.
- 같은 타임스탬프라도 내용이 다를 수 있으므로 `contentHash` 기반 추가 중복 체크를 유지합니다.

### 2.4. 이벤트 원본 발췌(Raw Excerpt) 저장

- LLM이 이벤트를 추출할 때, **짧은 요약(content)** 외에 해당 이벤트와 직접 관련된 원본 대화 발췌문(`rawExcerpt`)과 조금 더 긴 요약(`long content`)를 함께 반환하도록 프롬프트를 수정합니다.
- `rawExcerpt`는 이벤트와 관련된 실제 메시지 3~7줄 내외로, LLM이 판단하여 발췌합니다. (원본 텍스트를 직접 가공하지 않고 LLM에게 위임)
- `Event` 도메인 모델과 DB에 `rawExcerpt: String?` 필드를 추가합니다.

### 2.5. 이벤트 카드 및 상세 뷰 개선

- **타임라인 카드(DailyEventCard)**: 텍스트 이벤트는 기존과 동일하게 한 줄 요약(`content`)으로 표시합니다.
- **이벤트 상세 뷰**: 카드 내 텍스트 이벤트를 탭하면 상세 뷰가 열립니다.
  - 상단: 이벤트의 짧은 요약(`content`)을 굵은 글자로 표시
  - 중간 : 조금 더 긴 요약(`long content`)을 표시
  - 하단: 원본 발췌문(`rawExcerpt`)을 인용 스타일로 표시 (카드 또는 다이얼로그)
  - 날짜 및 카테고리 표시

### 2.6. LLM 날짜별 청킹(Chunking) 전처리

- 모든 메시지를 단일 LLM 호출로 전달하는 대신, **날짜별로 메시지를 묶어** 청크(chunk)를 생성합니다.
- 청크 하나의 최대 메시지 수는 설정 가능하게 유지합니다. (기본값: 하루치)
- 각 청크를 순차적으로 LLM에 전달하며, API 제한(분당 15회) 대응을 위해 청크 간 딜레이를 적용합니다. (기본값: 4초)
- 현재는 테스트이므로 리퀘스트 횟수가 제한적이므로, 여러 날자를 하나의 청크로 묶어 처리합니다. (하루 리퀘스트 500회 제한, 데이터는 10년치이므로 제약이 큼)
- 하나의 청크에 여러 이벤트가 포함될 수 있으므로, LLM은 하나의 답변에 여러 이벤트를 포함해서 보낼 수 있어야 함.

### 2.7. 임포트 중 로딩 UI

- 임포트 진행 중에 로딩 인디케이터(프로그레스 오버레이 또는 배너)를 표시합니다.
- 로딩 상태에서는 카카오 임포트 버튼이 비활성화됩니다.
- 완료 시 처리된 메시지 수 및 추출된 이벤트 수를 Snackbar로 표시합니다. (기존 로직 유지 및 개선)

### 2.8. Gemini 모델 교체 용이성 확보

- Gemini 모델명은 `@Named("gemini_model")` 로 주입되어 있으며, `local.properties`의 `GEMINI_MODEL` 키로 설정합니다.
- 현재 모델: `gemini-3.1-flash-lite-preview`
- 코드 변경 없이 모델명만 교체 가능하도록 현행 DI 구조를 유지합니다.

---

## 3. 구현 계획

### 3.1. `KakaoParser.kt` 수정

- **방 이름 파싱 제거**: `roomNamePattern` 제거. 방 이름은 UseCase 호출부(UI)에서 별명으로 주입합니다.
- **모바일 포맷 메시지 패턴 수정**: 첫 줄 날짜 헤더를 무시하고, `날짜+시간, 발신자 : 내용` 패턴으로 메시지를 파싱합니다.
- **다중행 메시지 연결 유지**: 현재 `currentContent.append('\n').append(line)` 방식 유지.
- **`ParsedResult`에서 `roomName` 제거**: `ParsedResult(val messages: List<KakaoMessage>)`로 단순화.

### 3.2. `KakaoMessage`, `KakaoRoom` 도메인 모델 확인 및 수정

- `KakaoRoom`에 `alias: String` 필드가 없으면 추가합니다. (`roomName`이 사용자가 입력한 별명으로 대체될 수 있도록)
- `KakaoRepository.getAllRooms(): List<KakaoRoom>` 인터페이스 메서드가 없으면 추가합니다.

### 3.3. Event 모델 및 DB 마이그레이션

- `Event` 도메인 모델에 `longContent: String? = null`, `rawExcerpt: String? = null` 필드 추가.
- `EventEntity`에 `long_content`, `raw_excerpt` 컬럼 추가.
- Room DB 버전을 **4**로 올리고 `MIGRATION_3_4` 구현:
  ```sql
  ALTER TABLE events ADD COLUMN long_content TEXT;
  ALTER TABLE events ADD COLUMN raw_excerpt TEXT;
  ```

### 3.4. ExtractedEvent 및 GeminiEventClassifier.kt 수정

- `ExtractedEvent`에 `longContent: String?`, `rawExcerpt: String?` 필드 추가.
- **프롬프트(`buildPrompt`) 수정**: JSON 응답 포맷에 `longContent`, `rawExcerpt` 필드 추가:
  ```json
  [{"date":"YYYY-MM-DD","category":"SAID|DID|OTHER","content":"짧은 요약","longContent":"조금 더 긴 요약","rawExcerpt":"관련 원본 대화 발췌 (3~7줄)"}]
  ```
- **다수 날짜 통합 청킹 구현**: 리퀘스트 횟수 최소화를 위해 여러 날짜의 메시지를 하나의 청크로 묶어 LLM에 전달.
- 하나의 청크에 대해 `List<ExtractedEvent>`를 반환하도록 처리.
- `RawEvent`에 `longContent`, `rawExcerpt` 필드 추가 및 `parseResponse()` 반영.

### 3.5. `ImportKakaoUseCase.kt` 수정

- `invoke()` 파라미터에 `roomAlias: String` 추가.
- 기존 방 탐색 로직 변경: `kakaoRepository.getRoomByName(roomAlias)` → 별명 기반 조회.
- `Event` 생성 시 `rawExcerpt = extracted.rawExcerpt` 포함.
- 임포트 진행 중 진행 상태를 `Flow<ImportProgress>`로 방출하여 UI가 실시간으로 진행상황을 표시할 수 있도록 변경. (선택적, 우선은 `isLoading` 상태로도 충분)

### 3.6. `TimelineViewModel.kt` 수정

- `importKakao(uri: Uri, roomAlias: String)` — 파라미터 추가.
- `isLoading = true` 상태 진입 시점 확인 및 UI 반영 보장.
- 완료 시 Snackbar 메시지 포맷 개선.

### 3.7. `TimelineScreen.kt` UI 수정

#### 3.7.1. 카카오 임포트 다이얼로그 개선
- 기존: 파일 선택 확인 다이얼로그 (텍스트만)
- 변경:
  - **방 별명 입력 TextField** (새 방인 경우)
  - **기존 방 목록** — DB에서 `KakaoRoom` 리스트를 가져와 하단에 선택 가능한 칩(Chip) 또는 라디오 버튼으로 표시
  - 새 별명 입력과 기존 방 선택 중 하나만 활성화 (택일 UX)
  - [파일 선택] 버튼 → 선택 완료 후 별명과 함께 `viewModel.importKakao(uri, alias)` 호출

#### 3.7.2. 로딩 오버레이
- `state.isLoading == true` 일 때 타임라인 위에 반투명 로딩 오버레이(또는 LinearProgressIndicator) 표시.
- 로딩 중 카카오 import 버튼 비활성화.

#### 3.7.3. 텍스트 이벤트 상세 뷰
- `DailyDetailDialog` 내 텍스트 이벤트 항목을 탭하면 `EventDetailBottomSheet` 또는 `AlertDialog` 표시:
  - 상단: 날짜, 카테고리 칩, `content` (굵은 글씨, 큰 폰트)
  - 중간: `longContent`가 있을 경우 표시 (보통 크기)
  - 하단: `rawExcerpt`가 있을 경우 인용 블록(`│` 스타일 또는 배경색 구분)으로 원본 발췌 표시

---

## 4. 고려사항

### 4.1. API 호출 제한 대응
- Gemini API 제한: 분당 15회, 분당 25만 토큰, 하루 500회.
- **다수 날짜 통합 청킹**: 하루 500회 리퀘스트 제한을 고려하여, 10년치 데이터를 효율적으로 처리하기 위해 여러 날짜의 대화를 하나의 리퀘스트(청크)로 묶어 처리합니다.
- 토큰 사용량과 리퀘스트 횟수 사이의 최적 균형점을 찾아 청크 크기를 조절합니다. (예: 메시지 100~200개 단위)
- 청크 간 4초 딜레이(`delay(4000)`) 적용.
- 에러 발생 시 해당 청크를 건너뛰고 계속 진행하며, 처리 실패한 내역을 Snackbar에 표시.

### 4.2. 방 별명 중복
- 같은 별명으로 새 방 등록 시도 시, 기존 방 업데이트로 자동 처리합니다 (별명을 PK 대리로 사용).

### 4.3. 개인정보 보호 (Chat_Sample.txt)
- `Chat_Sample.txt`는 개인정보가 포함되어 있으므로 `.gitignore`에 추가하여 git 추적에서 제외합니다.

### 4.4. 기존 임포트 데이터 호환
- `rawExcerpt` 필드는 Nullable(`String?`)이므로, 기존 이벤트(이 ENH 이전에 임포트된 데이터)는 `null`로 유지되며 UI에서 해당 필드가 없을 경우 상세 발췌 섹션을 숨깁니다.

---

## 5. 파일 변경 목록 요약

| 파일 | 변경 유형 | 주요 변경 내용 |
|------|-----------|---------------|
| `KakaoParser.kt` | 수정 | 모바일 포맷 파싱, `roomName` 제거 |
| `KakaoRoom.kt` (도메인 모델) | 수정(확인) | `alias` 필드 확인/추가 |
| `KakaoRepository.kt` (인터페이스) | 수정 | `getAllRooms()` 추가 |
| `KakaoRepositoryImpl.kt` | 수정 | `getAllRooms()` 구현 |
| `Event.kt` (도메인 모델) | 수정 | `longContent`, `rawExcerpt` 추가 |
| `EventEntity.kt` | 수정 | `long_content`, `raw_excerpt` 컬럼 추가 |
| `DodoDatabase.kt` | 수정 | 버전 4, `MIGRATION_3_4` |
| `ExtractedEvent.kt` (data class) | 수정 | `longContent`, `rawExcerpt` 추가 |
| `GeminiEventClassifier.kt` | 수정 | 프롬프트 수정, 통합 청킹, 다수 이벤트 추출 대응 |
| `ImportKakaoUseCase.kt` | 수정 | `roomAlias` 파라미터, `longContent`/`rawExcerpt` 전달 |
| `TimelineViewModel.kt` | 수정 | `importKakao(uri, alias)` 시그니처 변경 |
| `TimelineScreen.kt` | 수정 | 임포트 다이얼로그 개선, 로딩 UI, 이벤트 상세 뷰 |
| `.gitignore` | 수정 | `Chat_Sample.txt` 추가 |
