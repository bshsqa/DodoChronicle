# ENH-015: 긴 타임라인 빠른 이동과 원본 삭제 사진 처리

## 목표 (Objective)
타임라인 데이터가 길어졌을 때 사용자가 원하는 날짜로 빠르게 이동할 수 있게 하고, 이미 앱에 등록된 사진의 원본이 갤러리에서 삭제된 경우에도 화면과 모델 상태가 깨지지 않도록 정리합니다.

이 작업은 다음 두 문제를 함께 다룹니다.

- #35: 날짜가 늘어날수록 일반 스크롤만으로 위/아래 이동이 오래 걸리는 문제
- 원본 삭제된 사진 처리: 앱이 MediaStore URI만 저장하고 있어 원본 사진 삭제 시 썸네일/전체화면 이미지가 깨질 수 있는 문제

---

## 배경 (Background)
현재 타임라인은 날짜별 카드가 세로로 쌓이는 구조입니다. 데이터가 적을 때는 일반 스크롤로 충분하지만, 몇 달/몇 년 단위로 기록이 늘어나면 과거 날짜로 이동하거나 최신으로 돌아오는 동작이 피곤해집니다.

또한 사진 이벤트는 원본 이미지를 앱 내부에 복사하지 않고 MediaStore URI를 저장합니다. 이 방식은 저장 공간을 아끼지만, 사용자가 갤러리에서 원본 사진을 삭제하면 앱 DB에는 사진 이벤트와 embedding이 남아 있는데 실제 이미지는 열리지 않는 상태가 될 수 있습니다.

---

## 관련 이슈
- #35 스크롤바 혹은 아무튼 위아래로 빨리 갈 수 있는 방법
- TODO: 원본 삭제된 사진 처리

---

## 주요 요구사항 (Requirements)

### 1. 긴 타임라인 빠른 이동 UX
타임라인 화면에서 날짜가 많아졌을 때 빠르게 이동할 수 있는 보조 UI를 제공합니다.

초기 구현 권장:

- 우측에 얇은 빠른 스크롤 핸들을 표시합니다.
- 핸들은 터치/드래그 가능해야 합니다.
- 드래그 중에는 현재 이동 대상 날짜를 작은 floating label로 표시합니다.
- 핸들을 드래그하는 동안에는 날짜 그룹 인덱스에 맞춰 `LazyListState.scrollToItem(index)`로 즉시 이동합니다.
- 상단/하단 빠른 이동 버튼처럼 discrete action은 `animateScrollToItem(index)`를 사용할 수 있습니다.
- 상단/하단 빠른 이동 버튼을 함께 제공합니다.
- 날짜 그룹이 적을 때는 UI가 오히려 번잡해지므로, 기본적으로 날짜 그룹이 8개 이상일 때만 fast scroll UI를 표시합니다.

권장 UI:

```text
┌─────────────────────────────┐
│ 타임라인                    │
│ ...                         │
│ 날짜 카드                   │  ┃ ← 우측 얇은 fast scroll track
│ 날짜 카드                   │  ● ← draggable thumb
│ ...                         │
└─────────────────────────────┘
```

### 2. 빠른 이동 동작
빠른 이동은 날짜별 그룹을 기준으로 동작합니다.

- 전체 이벤트가 아니라 날짜 그룹 단위로 이동합니다.
- 드래그 위치 0%는 가장 위 날짜 그룹입니다.
- 드래그 위치 100%는 가장 아래 날짜 그룹입니다.
- 날짜순/검색 결과/관련도순 상태에 따라 현재 화면에 표시 중인 그룹 순서를 그대로 사용합니다.
- 검색 결과가 표시 중이면 검색 결과 내에서만 이동합니다.
- 검색 결과가 없거나 날짜 그룹이 fast scroll 표시 기준보다 적으면 fast scroll UI는 숨깁니다.

### 3. 날짜 점프 보조
이번 ENH에서는 날짜 선택 다이얼로그를 만들지 않습니다.

초기 구현 범위:

- 우측 fast scroll 드래그 중 날짜 label 표시
- 상단/하단 빠른 이동 버튼

제외:

- 날짜 선택 다이얼로그
- 월/연 단위 인덱스
- 검색어로 날짜를 직접 입력해 이동하는 기능

### 4. 원본 삭제된 사진 감지
앱이 저장한 `PhotoRecord.localUri`가 실제로 열리는지 확인할 수 있어야 합니다.

감지 대상:

- 타임라인 사진 썸네일 표시 시
- 전체화면 사진 표시 시
- 신규 사진 로딩 또는 사진 정리 작업 시
- 설정 메뉴의 수동 `사진 원본 확인/정리` 실행 시

초기 구현 권장:

- 화면 렌더링 실패만으로 즉시 DB를 수정하지 않습니다.
- 별도 정리 함수에서 `ContentResolver.openInputStream(uri)` 가능 여부를 확인합니다.
- URI 확인은 UI thread가 아니라 IO dispatcher에서 수행합니다.
- 열리지 않는 사진은 missing 상태로 표시합니다.
- 화면 렌더링 중 일시적으로 이미지 로딩이 실패한 경우에는 임시 placeholder만 표시하고, `isMissing` DB 상태는 바꾸지 않습니다.

### 5. Missing 사진 상태 저장
원본이 사라진 사진은 DB에 상태를 저장합니다.

`PhotoRecordEntity` 후보 필드:

```kotlin
val isMissing: Boolean = false
val lastSeenAt: Long = 0L
val missingCheckedAt: Long = 0L
```

정책:

- URI를 열 수 있으면 `isMissing = false`, `lastSeenAt = now`, `missingCheckedAt = now`
- URI를 열 수 없으면 `isMissing = true`, `missingCheckedAt = now`
- missing 사진은 화면에서 `원본 사진을 찾을 수 없음` 상태로 표시합니다.
- missing 사진의 embedding은 모델 업데이트 대상에서 제외합니다.

### 6. Missing 사진 UI
원본 삭제 사진이 타임라인에 남아 있을 때 사용자가 이해할 수 있어야 합니다.

표시 정책:

- 날짜 카드의 사진 썸네일 위치에는 회색 placeholder를 표시합니다.
- 상세 다이얼로그/전체화면에서는 `원본 사진을 찾을 수 없음` 메시지를 표시합니다.
- 초기 구현에서는 missing 사진을 전체화면 뷰어로 열지 않고, 클릭 시 안내 메시지를 표시합니다.
- missing 사진은 선택 후 삭제할 수 있어야 합니다.
- 자동으로 조용히 삭제하지 않습니다.

### 7. 설정 메뉴 정리 액션
설정 메뉴에 사진 원본 확인/정리 액션을 추가합니다.

예시:

```text
사진 원본 확인
```

동작:

1. 저장된 PhotoRecord 목록을 순회합니다.
2. 각 URI가 열리는지 확인합니다.
3. missing 상태를 DB에 반영합니다.
4. 완료 후 Snackbar로 결과를 표시합니다.

예시 메시지:

```text
원본을 찾을 수 없는 사진 3장을 표시했습니다.
```

### 8. 모델 업데이트 정책
원본이 사라진 사진은 아이 얼굴 embedding 업데이트에 사용하지 않습니다.

- `PhotoRecord.isMissing == true`는 `isExcludedFromModel == true`와 유사하게 모델 업데이트 대상에서 제외합니다.
- `PhotoRecordDao.getLatest50Embeddings(...)` 계열 쿼리는 `isExcludedFromModel = 0`뿐 아니라 `isMissing = 0`도 만족하는 사진만 반환해야 합니다.
- 사용자가 missing 사진을 삭제하면 관련 Event/PhotoRecord도 함께 삭제합니다.
- missing 상태를 해제할 수 있는 경우는 URI가 다시 열리는 경우뿐입니다.

---

## 구현 설계

### 1. Fast scroll 상태 연결
`GroupedTimelineContent`는 이미 날짜 그룹 목록을 생성합니다. 이 목록을 fast scroll UI와 공유합니다.

후보:

```kotlin
val grouped: List<Map.Entry<LocalDate, List<Event>>>
val listState = rememberLazyListState()
```

fast scroll composable 후보:

```kotlin
@Composable
private fun TimelineFastScroller(
    groupCount: Int,
    currentIndex: Int,
    labelForIndex: (Int) -> String,
    onJumpToIndex: (Int) -> Unit
)
```

구현 메모:

- 현재 화면의 `grouped` 순서를 source of truth로 사용합니다.
- `currentIndex`는 `LazyListState.firstVisibleItemIndex`를 날짜 그룹 범위로 clamp해서 계산합니다.
- 드래그 중 label은 `labelForIndex(index)`로 현재 대상 날짜를 표시합니다.

### 2. 상단/하단 이동 버튼
화면 우하단 또는 fast scroll 근처에 작은 icon button을 배치합니다.

- 맨 위 이동
- 맨 아래 이동

단, 하단 FAB와 겹치지 않도록 배치합니다.

### 3. PhotoRecord DB 마이그레이션
Room version을 올리고 `photo_records`에 필드를 추가합니다.

```sql
ALTER TABLE photo_records ADD COLUMN isMissing INTEGER NOT NULL DEFAULT 0;
ALTER TABLE photo_records ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE photo_records ADD COLUMN missingCheckedAt INTEGER NOT NULL DEFAULT 0;
```

### 4. DAO/Repository 확장
필요한 DAO 메서드:

```kotlin
@Query("UPDATE photo_records SET isMissing = :isMissing, lastSeenAt = :lastSeenAt, missingCheckedAt = :checkedAt WHERE id = :id")
suspend fun updateMissingState(...)
```

또는 batch 업데이트를 고려합니다.

Repository 후보:

```kotlin
suspend fun updatePhotoMissingState(photoRecordId: String, isMissing: Boolean, checkedAt: Long, lastSeenAt: Long?)
suspend fun getAllPhotoRecords(): List<PhotoRecord>
```

### 5. Missing 검사 UseCase
새 use case 후보:

```kotlin
class CheckMissingPhotosUseCase
```

역할:

- 모든 PhotoRecord 또는 child별 PhotoRecord 조회
- ContentResolver로 URI 열기 시도
- missing 상태 업데이트
- 결과 count 반환

### 6. UI 표시
사진 UI에서 `PhotoRecord`를 알 수 있는 곳은 missing 상태를 반영합니다.

- `GroupedTimelineContent`와 `DailyEventCard`에 `photoRecordsByEventId`를 전달해 날짜 카드에서도 missing 상태를 알 수 있게 합니다.
- `DailyEventCard`: event id로 `photoRecordsByEventId[event.id]` 조회
- `DailyDetailDialog`: 상세 사진 grid에서 missing placeholder 표시
- `FullscreenPhotoViewer`: 초기 구현에서는 missing 사진을 전체화면 진입 전에 차단합니다.

---

## 수용 기준 (Acceptance Criteria)

### 빠른 이동
- 날짜 그룹이 충분히 많을 때 fast scroll UI가 표시된다.
- fast scroll 핸들을 드래그하면 해당 위치의 날짜 그룹으로 이동한다.
- 드래그 중 이동 대상 날짜가 표시된다.
- 검색 결과 중에도 현재 결과 그룹 범위 안에서 이동한다.
- 날짜 그룹이 fast scroll 표시 기준보다 적으면 fast scroll UI는 표시되지 않는다.
- 상단/하단 빠른 이동 버튼으로 맨 위/맨 아래로 이동할 수 있다.
- 날짜 선택 다이얼로그 없이도 긴 목록에서 빠르게 위/아래와 중간 날짜로 이동할 수 있다.

### 원본 삭제 사진
- 저장된 사진 URI를 열 수 없는 경우 missing 상태로 표시된다.
- missing 사진은 회색 placeholder와 안내 문구로 표시된다.
- missing 사진은 모델 업데이트 대상에서 제외된다.
- 사용자는 missing 사진 이벤트를 삭제할 수 있다.
- 설정 메뉴에서 사진 원본 확인/정리를 실행할 수 있다.
- 원본 사진이 없어도 앱이 crash하지 않는다.
- 렌더링 실패만으로는 DB의 missing 상태가 즉시 바뀌지 않는다.
- 수동 원본 확인/정리에서 URI가 다시 열리면 missing 상태가 해제된다.

---

## 리스크 (Risks)

- fast scroll 드래그와 기존 LazyColumn 스크롤이 충돌할 수 있습니다.
- 우측 fast scroll UI가 사진 카드나 FAB와 겹칠 수 있습니다.
- MediaStore URI 접근 권한이 Android 버전별로 다르게 동작할 수 있습니다.
- 원본 URI가 일시적으로만 열리지 않는 경우를 바로 missing 처리하면 false positive가 생길 수 있습니다.
- missing 상태 저장을 위해 Room migration이 필요합니다.

---

## 향후 확장

- 월/연 단위 section index
- missing 사진 일괄 삭제
- 원본 사진 백업/앱 내부 복사 옵션
- 스크롤 위치 기억 및 앱 재시작 후 복원
