# ENH-021: 사진 날짜 결정 및 백그라운드 초기 분류

## 목표
사진 날짜 결정 로직, 초기 사진 분류 백그라운드화, 대량 얼굴 그룹 선택 UX를 하나의 사진 분류 파이프라인으로 재설계합니다.

관련 이슈:

- #37 사진 이벤트 날짜 결정 로직 개선
- #39 초기 사진 분류를 생년월일 기준 백그라운드 작업으로 전환
- #40 대량 사진 그룹 선택 UX 개선

핵심 목표:

- `1970-01-01`처럼 잘못된 사진 날짜 배치를 막습니다.
- 전체 갤러리 사진을 날짜만 먼저 판정합니다.
- 아이 생년월일 1년 전 이후 사진만 초기 얼굴 분석 대상으로 삼습니다.
- 초기 세팅 완료 후 사용자가 사진 분석 완료를 기다리지 않고 앱에 진입합니다.
- 백그라운드 초기 분류 진행률과 완료 상태를 상단 배너로 표시합니다.
- 분류 완료 후 얼굴 그룹을 여러 번 나누어 적용할 수 있게 합니다.

---

## 배경
4만장 이상의 사진을 초기 세팅 화면에서 모두 얼굴 분석하도록 기다리는 UX는 현실적이지 않습니다.

또한 일부 사진이 `1970-01-01`에 배치되는 문제가 확인되었습니다. 이는 실제 촬영일이라기보다 `DATE_TAKEN = 0` 같은 값이 epoch로 해석된 결과일 가능성이 큽니다. 갤러리 앱에는 1970년 사진이 보이지 않으므로, 갤러리 앱 역시 여러 metadata를 조합해 합리적인 날짜를 선택하고 있을 가능성이 높습니다.

초기 사진 분류는 신규 사진 로딩과 성격도 다릅니다.

- 초기 사진 분류:
  - 기존 갤러리 전체에서 아이 사진 그룹을 찾는 작업
  - 날짜 판정 후 기준 기간 내 사진만 얼굴 분석
  - 클러스터링/그룹 선택 UX가 필요
  - 오래 걸려도 앱 사용을 막지 않아야 함

- 신규 사진 로딩:
  - 앱 사용 이후 새로 추가된 사진을 child embedding 기준으로 비교
  - 자동 추가/확인 필요/무시 흐름
  - 날짜 cursor와 pending 처리가 별도로 필요

따라서 날짜 결정 helper는 공통화하되, 초기 분류와 신규 사진 로딩은 별도 레인으로 분리합니다.

---

## 전체 동작 요약

### 1. 초기 세팅
사용자가 다음 정보를 입력합니다.

- 아이 이름
- 생년월일
- 성별
- 대표 사진

초기 세팅 화면에서 전체 사진 분석 완료를 기다리지 않습니다.

초기 세팅 완료 시:

1. `Child` 정보를 저장합니다.
2. 앱 초기화 상태를 완료로 표시합니다.
3. 타임라인 화면으로 진입합니다.
4. 백그라운드 초기 사진 분류 세션을 시작합니다.

### 2. 날짜 판정 및 대상 선정
전체 MediaStore 이미지 row를 한 번 훑습니다.

각 사진마다 공통 날짜 결정 helper로 날짜를 먼저 확정합니다.

```text
for each MediaStore image row:
    resolvedDate = resolvePhotoDate(row, uri)
    if resolvedDate >= child.birthDate.minusYears(1):
        save as initial classification item
    else:
        skip
```

중요:

- MediaStore 쿼리에서 기준 기간으로 먼저 자르지 않습니다.
- `DATE_TAKEN=0`이지만 EXIF 촬영일만 정상인 사진을 놓치지 않기 위해 전체 row를 날짜 판정합니다.
- EXIF 날짜 확인은 얼굴 분석보다 훨씬 가벼우므로 전체 사진 대상으로 수행 가능하다고 봅니다.
- 얼굴 분석은 기준 기간 안에 들어온 사진에만 수행합니다.

### 3. 백그라운드 얼굴 분석
대상 item만 얼굴 분석합니다.

- 500장 checkpoint 단위로 DB 저장
- 앱이 백그라운드로 내려가도 foreground service로 계속 진행
- 프로세스가 죽으면 다음 앱 실행 시 같은 세션에서 자동 resume
- `PROCESSING` 상태였던 item은 재시작 시 `PENDING`으로 되돌림
- `PROCESSED / NO_FACE / FAILED` item은 다시 처리하지 않음

### 4. 상단 배너
타임라인 상단에 초기 분류 배너를 표시합니다.

진행 중:

```text
사진 분류 중 1,970 / 12,430
```

완료 후:

```text
분류된 사진 그룹을 확인해주세요
```

배너 터치:

- 진행 중이면 상세 진행 상태 또는 안내 표시
- 완료 후이면 그룹 선택창 열기

### 5. 그룹 선택창
분류 완료 후 얼굴 그룹을 표시합니다.

버튼:

- `추가`
- `닫기`
- `완료`

#### 추가
현재 선택한 그룹만 사진 이벤트로 등록합니다.

동작:

1. 선택된 cluster의 사진들을 `EventCategory.PHOTO` 이벤트로 추가
2. `PhotoRecord` 저장
3. child embedding 갱신
4. 적용된 cluster와 해당 item을 그룹 선택용 DB/cache에서 삭제
5. 선택창에는 남은 그룹만 계속 표시
6. 남은 그룹이 있으면 배너 유지

#### 닫기
선택창만 닫습니다.

동작:

- DB/cache를 비우지 않음
- 배너 유지
- 나중에 배너를 눌러 다시 선택 가능

#### 완료
남은 그룹을 더 이상 선택하지 않겠다는 의미입니다.

동작:

1. 확인 팝업 표시
2. 사용자가 확인하면 남은 그룹 선택 cache 삭제
3. 초기 분류 배너 제거
4. 초기 분류 세션 상태를 최종 완료/적용 완료로 표시

확인 팝업 예시:

```text
아직 추가하지 않은 그룹은 다시 선택할 수 없습니다.
모두 추가했나요?
```

---

## 사진 날짜 결정 정책 (#37)

### 문제
일부 사진 이벤트가 `1970-01-01`에 배치됩니다.

원인 가능성:

- `DATE_TAKEN`이 0인데 epoch millis로 해석함
- `DATE_ADDED`/`DATE_MODIFIED`는 seconds 값인데 millis처럼 다룸
- EXIF 촬영일이 있는데 MediaStore `DATE_TAKEN`만 사용함
- 클라우드/메신저/편집 이미지에서 특정 날짜 필드가 비어 있음

### 공통 helper
사진 날짜를 만드는 모든 경로에서 같은 helper를 사용합니다.

후보 이름:

```kotlin
class PhotoDateResolver
```

후보 입력:

```kotlin
data class PhotoDateSource(
    val uri: Uri,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long?,
    val dateModifiedSeconds: Long?
)
```

후보 출력:

```kotlin
data class ResolvedPhotoDate(
    val takenAtMillis: Long,
    val source: PhotoDateSourceType
)

enum class PhotoDateSourceType {
    MEDIASTORE_DATE_TAKEN,
    EXIF_DATE_TIME_ORIGINAL,
    EXIF_DATE_TIME_DIGITIZED,
    MEDIASTORE_DATE_ADDED,
    MEDIASTORE_DATE_MODIFIED,
    FILE_LAST_MODIFIED
}
```

날짜를 전혀 결정할 수 없으면 `null`을 반환하거나 실패 결과를 반환합니다. 현재 시각으로 꾸며내지 않습니다.

### 우선순위
사진 날짜는 사진 파일/MediaStore가 제공하는 실제 metadata 중 가장 믿을 만한 값을 선택합니다.

권장 순서:

1. `MediaStore.Images.Media.DATE_TAKEN`
   - 값이 0보다 크고 합리적인 범위일 때만 사용
   - 단위는 millis
2. EXIF `DateTimeOriginal`
3. EXIF `DateTimeDigitized`
4. `MediaStore.Images.Media.DATE_ADDED`
   - 값이 0보다 크면 seconds로 보고 `* 1000`
5. `MediaStore.Images.Media.DATE_MODIFIED`
   - 값이 0보다 크면 seconds로 보고 `* 1000`
6. 가능하면 파일 lastModified
7. 그래도 없으면 제외/실패 처리

### 합리적인 날짜 기준
`1970-01-01` 방지를 위해 최소 유효 날짜를 둡니다.

권장:

```text
MIN_REASONABLE_PHOTO_TIME_MILLIS = 2000-01-01T00:00:00
```

이보다 이전인 값은 유효 촬영일로 보지 않습니다.

### 적용 경로
공통 helper를 적용할 경로:

- 초기 사진 분류 item 생성
- 신규 사진 로딩/sync
- 수동 사진 추가
- missing photo 복원/검사 중 날짜 재사용 경로

### 기존 DB 데이터에 대한 영향
새 로직을 적용해도 이미 DB에 `1970-01-01`로 저장된 이벤트는 자동으로 이동하지 않습니다.

이유:

- 이벤트 날짜는 이미 DB에 저장된 값입니다.
- 새 날짜 결정 helper는 새로 스캔/추가되는 사진에 적용됩니다.
- 기존 데이터를 옮기려면 별도 migration 또는 repair/backfill 작업이 필요합니다.

현재 앱은 배포 전이고 테스트 데이터는 다시 로딩할 예정이므로, 이번 ENH에서는 기존 1970년 이벤트 재배치는 고려하지 않습니다.

---

## 초기 분류 대상 선정 (#39)

### 기준 기간
초기 백그라운드 분류 대상은 다음 기간입니다.

```text
child.birthDate.minusYears(1) <= resolvedPhotoDate <= now/latest
```

생년월일 1년 전보다 오래된 사진은 얼굴 분석하지 않습니다.

### 왜 전체 row를 훑는가
MediaStore 쿼리에서 먼저 기간 필터링하면 다음 사진을 놓칠 수 있습니다.

- `DATE_TAKEN = 0`
- `DATE_ADDED`가 오래된 값
- `DATE_MODIFIED`도 오래된 값
- EXIF 촬영일만 정상

따라서 전체 row를 훑고, 날짜만 먼저 확정한 뒤 기준 기간 필터를 적용합니다.

### 비용 판단
전체 사진에 대해 날짜 metadata를 읽는 것은 얼굴 분석보다 훨씬 가볍습니다.

비용 순서:

```text
MediaStore row 읽기 < EXIF 날짜 읽기 << Bitmap 디코딩 < 얼굴 감지 ML < face embedding
```

무거운 얼굴 분석은 기준 기간 내 사진에만 수행합니다.

---

## 데이터 설계

ENH-018/ENH-019에서 추가한 초기 scan session/item/cluster 구조를 확장하거나 재사용합니다.

### Session 상태

후보:

```text
PREPARING_ITEMS
SCANNING
SCAN_COMPLETED
PARTIALLY_APPLIED
APPLIED
CANCELLED
FAILED
```

필드 후보:

```kotlin
val status: String
val totalCount: Int
val processedCount: Int
val elapsedSeconds: Long
val lastCheckpointAt: Long
val completedAt: Long?
val itemPreparedAt: Long?
val cutoffDateEpochDay: Long?
```

### Item 상태

후보:

```text
PENDING
PROCESSING
PROCESSED
NO_FACE
FAILED
APPLIED
DISMISSED
```

Item에는 확정된 날짜를 저장합니다.

```kotlin
val takenAt: Long
```

이 값은 `PhotoDateResolver`가 결정한 날짜입니다. 더 이상 raw `DATE_TAKEN=0`을 그대로 저장하지 않습니다.

### Cluster
기존 `initial_scan_clusters` 사용.

`추가` 시:

- 적용된 cluster row 삭제 또는 `APPLIED` 상태 추가
- 해당 item 삭제 또는 상태 변경

초기 구현 권장:

- cluster row 삭제
- 관련 item 삭제

이유:

- 선택창에서 다시 보이지 않아야 함
- cache 용도이므로 장기 보존 필요성이 낮음

---

## 신규 사진 로딩과의 분리

초기 백그라운드 분류와 신규 사진 로딩은 cursor와 UX가 다릅니다.

### 초기 백그라운드 분류

- 대상: 앱 초기 세팅 시점의 전체 갤러리 중 `birthDate - 1년` 이후 사진
- 선정: 전체 row 날짜 판정 후 대상 item 저장
- 처리: 얼굴 클러스터링
- 결과: 그룹 선택창에서 사용자가 cluster를 선택

### 신규 사진 로딩

- 대상: 앱 초기화 이후 새로 추가된 사진
- 선정: `DATE_ADDED` 기반 cursor/overlap
- 날짜 저장: `PhotoDateResolver`로 결정한 날짜 사용
- 처리: child embedding 유사도 비교
- 결과:
  - 높으면 자동 추가
  - 애매하면 pending
  - 낮으면 무시/거절 처리

두 작업의 pending/group/cache DB는 섞지 않습니다.

---

## UI 설계

### 타임라인 상단 배너
위치:

- 검색 결과 안내/펜딩 사진 배너와 충돌하지 않는 상단 영역
- 기존 타임라인 카드 영역 위

상태:

```text
사진 분류 준비 중
사진 분류 중 1,970 / 12,430
분류된 사진 그룹을 확인해주세요
사진 분류를 이어서 진행합니다
```

배너는 앱을 재시작해도 세션 상태에 따라 복원됩니다.

### 그룹 선택창
대량 그룹 선택을 고려합니다.

요구사항:

- 그룹 목록은 스크롤 가능
- 각 그룹은 대표 사진 grid와 사진 수 표시
- 선택 상태 명확히 표시
- `추가`, `닫기`, `완료` 버튼을 하단 고정 영역에 배치
- `완료`는 destructive action으로 색상/확인 팝업을 둠

---

## 처리 흐름

### 초기 세팅 완료

```text
save child
mark initialized
start initial background classification session
navigate to timeline
```

### 앱 시작 시

```text
load child
load initial classification session
if status == PREPARING_ITEMS or SCANNING:
    show progress banner
    resume service
if status == SCAN_COMPLETED or PARTIALLY_APPLIED:
    show completed banner
```

### 분류 service

```text
if item list not fixed:
    status = PREPARING_ITEMS
    query all MediaStore image rows
    for each row:
        resolvedDate = resolvePhotoDate(row, uri)
        if resolvedDate == null:
            skip or record failed date
        else if resolvedDate >= birthDate - 1 year:
            insert scan item with resolvedDate

status = SCANNING

while pending item exists:
    process next checkpoint
    update item status
    update clusters
    update session progress

mark scan completed
show completed banner
```

### 그룹 추가

```text
selectedClusters = user selection
for each selected cluster:
    create photo events
    create photo records
    remove cluster and items from initial selection cache
update child embedding
if remaining clusters exist:
    status = PARTIALLY_APPLIED
else:
    status = APPLIED
    hide banner
```

---

## 수용 기준

### 날짜

- `DATE_TAKEN = 0`인 사진이 곧바로 1970년으로 들어가지 않습니다.
- 갤러리 앱 날짜순과 크게 어긋나지 않는 날짜가 선택됩니다.
- 날짜 필드 단위 seconds/millis 혼동이 없습니다.
- 초기 분류, 신규 사진 로딩, 수동 사진 추가에서 동일한 날짜 정책이 적용됩니다.

### 초기 분류

- 초기 세팅 완료 후 사진 분석 완료를 기다리지 않고 타임라인에 진입합니다.
- 전체 갤러리 row를 날짜만 먼저 판정합니다.
- `birthDate - 1년` 이후 사진만 얼굴 분석 대상으로 저장합니다.
- 백그라운드 분석 진행률이 상단 배너에 표시됩니다.
- 앱이 중단되어도 재시작 시 이어서 진행됩니다.
- 분석 완료 후 배너를 눌러 그룹 선택창을 열 수 있습니다.

### 그룹 선택

- 사용자가 그룹을 여러 번 나누어 추가할 수 있습니다.
- `추가`는 선택 그룹만 이벤트로 추가하고 해당 그룹을 선택 목록에서 제거합니다.
- `닫기`는 배너와 cache를 유지합니다.
- `완료`는 확인 팝업 이후에만 남은 cache와 배너를 제거합니다.

### 안정성

- 특정 사진 날짜 판정/로딩/얼굴 분석 실패가 전체 작업을 죽이지 않습니다.
- checkpoint 이후 완료된 item은 다시 처리하지 않습니다.
- 날짜 판정 실패 사진은 얼굴 분석 없이 제외 또는 실패 기록합니다.
- foreground service가 예외를 logcat에 남깁니다.

---

## 리스크

- 전체 사진 row에 대해 EXIF 날짜를 확인하면 기기/저장소에 따라 시간이 걸릴 수 있습니다.
- 클라우드 placeholder나 접근 불가 URI는 날짜/이미지 로딩이 실패할 수 있습니다.
- 그룹 수가 많으면 선택창 성능 최적화가 필요할 수 있습니다.
- 적용된 그룹을 삭제 방식으로 처리하면 디버그 재현성이 낮아질 수 있습니다.

---

## 향후 확장

- 충전 중/Wi-Fi 조건에서만 백그라운드 분류
- 배터리 최적화 제외 안내
- 그룹 병합/분할 UI
- 날짜 판정 실패 사진 디버그 목록
- 초기 분류 재시작/재생성 옵션
