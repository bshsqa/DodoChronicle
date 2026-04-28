# ENH-006: 이벤트 품질 & 타임라인 UX 개선

rawExcerpt 길이 조정(#9) · 날짜카드 콘텐츠 뱃지(#11) · 실패 청크 재시도(#7) 세 가지를 하나의 작업으로 묶음.

## 상태 범례
- ✅ 구현 완료
- 🔲 미구현 (계획)

---

## 1. 날짜카드 콘텐츠 뱃지 (#11)

### 1.1. ✅ 날짜 헤더에 카테고리 pill 뱃지 표시

- **현재**: `DailyEventCard` 날짜 텍스트만 있고 콘텐츠 유형 파악 불가
- **변경**: 날짜 텍스트 오른쪽에 해당 날 포함된 카테고리를 작은 colored pill로 표시

```
2025년 3월 5일   ──────  [사진] [한 일]
```

**비주얼 스펙**:
- 아이콘 없음, 글자만 있는 pill
- `categoryColor().copy(alpha = 0.12f)` 배경 + `categoryColor().copy(alpha = 0.3f)` 1dp 테두리
- `labelSmall` 텍스트, `categoryColor()`로 색상
- `RoundedCornerShape(50.dp)`, padding = horizontal 6dp / vertical 2dp
- 기존 `AssistChip`(DailyDetailDialog)과 동일한 색·알파 체계 → 시각적 통일감

**표시 순서**: 사진 → 한 일 → 한 말 → 기타 (없는 카테고리는 생략)

**변경 파일**: `TimelineScreen.kt` — `DailyEventCard`, `CategoryPill` composable 추가

---

## 2. rawExcerpt 길이 조정 (#9)

### 2.1. ✅ 원문 대화 발췌 길이 상향 + 무관 내용 배제 지침

- **현재**: `buildPrompt()` 내 `rawExcerpt: 해당 이벤트와 관련된 원본 대화 3~7줄 발췌`
- **변경**: 5~10줄로 상향 + 무관 대화 제외 지침 추가

**변경 파일**: `GeminiEventClassifier.kt` — `buildPrompt()` 내 rawExcerpt 설명 1줄

---

## 3. 실패한 청크 재시도 (#7)

### 배경

카카오 import 시 Gemini API 호출이 실패한 청크는 **메시지는 DB에 저장되지만 이벤트 추출이 누락**된다.
실패 원인: rate limit(429), 응답 파싱 실패, 네트워크 에러.

메시지가 이미 DB(`kakao_messages`)에 존재하므로, 재시도 엔티티에는 **어느 방의 어느 시간 범위가 실패했는지만 저장**하면 된다. 재시도 시 해당 범위의 메시지를 DB에서 꺼내 Gemini에 재전송.

### 3.1. ✅ DB 테이블: `retry_chunks`

```kotlin
@Entity(tableName = "retry_chunks")
data class RetryChunkEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val roomAlias: String,   // 화면 표시용
    val sentAtStart: Long,   // 청크 첫 메시지 sentAt
    val sentAtEnd: Long,     // 청크 마지막 메시지 sentAt
    val dateRange: String,   // "2024-03-01 ~ 2024-03-15" 표시용
    val createdAt: Long = System.currentTimeMillis()
)
```

`DodoDatabase` version 4 → 5, `MIGRATION_4_5` 추가.

### 3.2. ✅ Import 실패 시 RetryChunk 저장

`ImportKakaoUseCase`에서 청크 실패(예외 + soft failure) 시:

```kotlin
// catch 블록 및 soft failure 분기 모두에서
retryChunkRepository.save(listOf(RetryChunk(
    id = UUID.randomUUID().toString(),
    roomId = room.id,
    roomAlias = roomAlias,
    sentAtStart = chunk.first().sentAt,
    sentAtEnd = chunk.last().sentAt,
    dateRange = "${chunkDates.first()} ~ ${chunkDates.last()}"
)))
```

앱이 꺼져도 `retry_chunks` 테이블에 남아 언제든 재시도 가능.

### 3.3. ✅ 즉시 재시도 (ImportDoneOverlay 버튼)

import 완료 직후 `ImportDoneOverlay`에서 `failedChunks > 0`일 때 버튼 표시:

```
┌────────────────────────────┐
│  ✅ 가져오기 완료           │
│  실패한 청크  2개  ← 빨간색│
│  [실패한 청크 2개 재시도]  │  ← NEW
│  [        확인        ]    │
└────────────────────────────┘
```

### 3.4. ✅ 설정 메뉴 확장 (재시도 영구 보관)

앱 재시작 후에도 미완료 재시도는 설정에서 접근 가능.

**설정 아이콘 → 선택 메뉴**:

```
┌──────────────────────────────┐
│  설정                         │
│  ────────────────────────    │
│  🔄 대화 분석 재시도 (N건)   │  ← 대기 중일 때만 활성
│  ⚠️  앱 데이터 초기화         │
└──────────────────────────────┘
```

재시도 선택 → 방 목록 다이얼로그:

```
┌──────────────────────────────┐
│  재시도할 대화방 선택         │
│  ──────────────────────────  │
│  ○ 가족 단톡방  (청크 2개)   │
│  ○ 육아 일기   (청크 1개)   │
│  [취소]    [재시도 시작]      │
└──────────────────────────────┘
```

### 3.5. ✅ RetryFailedChunksUseCase

- `retryChunkRepository.getByRoom(roomId)` 로 실패 청크 목록 조회
- 각 청크에 대해 `kakaoRepository.getMessagesInRange(roomId, sentAtStart, sentAtEnd)` 로 메시지 재조회
- `geminiClassifier.processChunk()` 재호출
- 성공 → 이벤트 저장 + `RetryChunkEntity` 삭제
- 재실패 → `RetryChunkEntity` 유지 (다음 재시도 가능)
- 청크 간 12초 delay 유지 (rate limit 동일 적용)

**제약**: ViewModel의 viewModelScope에서 실행 (Foreground Service 아님). 재시도는 사용자가 앱 포그라운드에서 명시적으로 시작하는 작업이므로 허용.

### 3.6. ✅ resetApp() 연동

`resetApp()` 시 `retryChunkRepository.deleteAll()` 포함.

---

## 4. 파일 변경 목록

| 파일 | 상태 | 변경 내용 |
|------|------|-----------|
| `entity/RetryChunkEntity.kt` | ✅ | 신규 |
| `dao/RetryChunkDao.kt` | ✅ | 신규 |
| `domain/model/RetryChunk.kt` | ✅ | 신규 |
| `domain/repository/RetryChunkRepository.kt` | ✅ | 신규 |
| `data/repository/RetryChunkRepositoryImpl.kt` | ✅ | 신규 |
| `domain/usecase/RetryFailedChunksUseCase.kt` | ✅ | 신규 |
| `DodoDatabase.kt` | ✅ | v4→v5, RetryChunkEntity 추가, MIGRATION_4_5 |
| `di/DatabaseModule.kt` | ✅ | RetryChunkDao 제공, MIGRATION_4_5 등록 |
| `di/RepositoryModule.kt` | ✅ | RetryChunkRepository 바인딩 |
| `dao/KakaoMessageDao.kt` | ✅ | `getInRange()` 추가 |
| `domain/repository/KakaoRepository.kt` | ✅ | `getMessagesInRange()` 추가 |
| `data/repository/KakaoRepositoryImpl.kt` | ✅ | `getMessagesInRange()` 구현 |
| `domain/usecase/ImportKakaoUseCase.kt` | ✅ | 실패 시 RetryChunk DB 저장, RetryChunkRepository 주입 |
| `ai/GeminiEventClassifier.kt` | ✅ | rawExcerpt 지침 수정 (3~7줄 → 5~10줄) |
| `presentation/timeline/TimelineViewModel.kt` | ✅ | pendingRetryRooms, retryRoom(), retryImmediate() |
| `presentation/timeline/TimelineScreen.kt` | ✅ | 날짜카드 pill 뱃지, 설정 선택 메뉴, 재시도 방 다이얼로그, ImportDoneOverlay 재시도 버튼 |

---

## 5. 구현 순서

1. 데이터 레이어 (Entity → DAO → Repository → UseCase)
2. DB 마이그레이션 + DI 등록
3. ImportKakaoUseCase 수정
4. GeminiEventClassifier 프롬프트 수정
5. ViewModel 수정
6. UI (TimelineScreen) 수정
