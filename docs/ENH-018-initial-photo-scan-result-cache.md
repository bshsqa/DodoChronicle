# ENH-018: 초기 사진 분석 결과 영구 캐시 및 선택 복원

## 목표 (Objective)
초기 사진 분석이 완료된 뒤 사용자가 아이 얼굴 그룹을 선택하기 전에 앱이 종료되더라도, 이미 분석한 얼굴 embedding과 클러스터링 결과를 잃지 않도록 합니다.

앱 재실행 시 완료된 초기 분석 결과가 있으면 다시 전체 사진 분석을 수행하지 않고 `아이 그룹 선택` 단계로 복원합니다.

선택 완료 후에는 임시 분석 결과를 삭제합니다.

---

## 배경 (Background)
현재 초기 사진 분석 흐름은 다음과 같습니다.

1. `ScanForegroundService`가 MediaStore 사진을 스캔합니다.
2. 얼굴이 감지된 사진에서 `PhotoEmbedding`을 생성합니다.
3. `FaceClusteringEngine`이 embedding을 클러스터링합니다.
4. 결과를 `ScanState.Done(clusters, embeddings)`로 메모리 상태에 올립니다.
5. `InitViewModel`이 결과를 받아 `아이 그룹 선택` 화면을 보여줍니다.
6. 사용자가 클러스터를 선택하고 `선택 완료`를 눌러야 DB에 실제 사진 이벤트가 저장됩니다.

문제는 4~6 사이입니다.

현재 결과는 메모리 중심 상태이므로, 분석 완료 후 사용자가 확인하기 전에 앱 프로세스가 종료되면 분석 결과가 사라질 수 있습니다.

테스트용 50장 정도라면 큰 문제가 아니지만, 이후 전체 사진 수만 장을 분석할 경우 다시 분석해야 하는 비용이 매우 큽니다.

---

## 핵심 원칙

- 초기 분석 결과는 “사용자 선택 전까지의 임시 결과”입니다.
- 임시 결과는 앱 재시작 후 복원 가능해야 합니다.
- 사용자가 선택 완료하면 임시 결과는 삭제합니다.
- 사용자가 분석을 취소하면 임시 결과도 삭제합니다.
- 실제 타임라인에는 사용자가 선택 완료한 클러스터만 반영합니다.

---

## 저장 모델

### 1. Scan Session
초기 분석 작업 단위를 session으로 저장합니다.

후보 테이블:

```kotlin
@Entity(tableName = "initial_scan_sessions")
data class InitialScanSessionEntity(
    @PrimaryKey val id: String,
    val childName: String,
    val birthDate: String,
    val gender: String,
    val referencePhotoUri: String,
    val status: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val totalCount: Int = 0,
    val processedCount: Int = 0,
    val elapsedSeconds: Long = 0
)
```

`status` 후보:

```text
RUNNING
COMPLETED
CANCELLED
APPLIED
FAILED
```

초기 구현에서는 `COMPLETED` 복원이 핵심입니다.

주의:

현재 앱은 `선택 완료` 시점에 `Child`를 실제 DB에 저장합니다. 따라서 완료된 스캔 결과를 복원하려면 클러스터뿐 아니라 초기 입력값도 session에 함께 저장해야 합니다.

저장 대상:

- 아이 이름
- 생년월일
- 성별
- 대표 사진 URI

복원 시 이 값들을 `InitUiState`에 다시 넣어야 사용자가 바로 클러스터를 선택하고 적용할 수 있습니다.

### 2. Scan Photo Embedding
얼굴 embedding이 생성된 사진을 저장합니다.

후보 테이블:

```kotlin
@Entity(
    tableName = "initial_scan_photo_embeddings",
    indices = [
        Index("sessionId"),
        Index("clusterId"),
        Index("uri")
    ]
)
data class InitialScanPhotoEmbeddingEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val uri: String,
    val takenAt: Long,
    val embeddingJson: String,
    val clusterId: Int? = null
)
```

저장 단위:

- `uri`
- `takenAt`
- `embeddingJson`
- `clusterId`

`clusterId`는 클러스터링 완료 후 update합니다.

### 3. Cluster Snapshot
별도 cluster 테이블은 필수는 아닙니다.

초기 구현은 `InitialScanPhotoEmbeddingEntity.clusterId`로 충분합니다.

복원 시:

1. session의 embedding rows 조회
2. `clusterId` 기준으로 groupBy
3. 각 cluster의 대표 URI 최대 9개 구성
4. `ClusterUiModel` 생성
5. `_rawClusterPhotos` 복원

---

## 동작 흐름

### 1. 초기 분석 시작
사용자가 초기 설정에서 사진 분석을 시작하면 새 session을 생성합니다.

```text
status = RUNNING
startedAt = now
processedCount = 0
totalCount = queryPhotos().size
```

기존 미완료 session 처리:

- `RUNNING` session이 남아 있으면 이전 앱 종료 또는 강제 종료로 간주합니다.
- 초기 구현에서는 오래된 `RUNNING` session을 삭제하고 새로 시작합니다.
- 향후에는 `processedCount` 기준 resume도 검토할 수 있습니다.

### 2. 분석 중
각 사진에서 얼굴 embedding 생성에 성공하면 임시 embedding 테이블에 저장합니다.

성능을 위해 매 사진마다 즉시 insert하지 않고 batch insert를 권장합니다.

권장 batch:

```text
50~200개 단위
```

진행률은 session의 `processedCount`를 주기적으로 갱신합니다.

### 3. 분석 완료 및 클러스터링
모든 embedding을 만든 뒤 클러스터링합니다.

클러스터링 완료 후 각 embedding row에 `clusterId`를 저장합니다.

session 업데이트:

```text
status = COMPLETED
completedAt = now
elapsedSeconds = ...
```

그 다음 `ScanState.Done`을 emit해 현재처럼 `아이 그룹 선택` 화면을 표시합니다.

### 4. 앱 재시작 후 복원
앱 초기화 화면 진입 시 완료된 미적용 session을 확인합니다.

조건:

```text
status == COMPLETED
```

완료 session이 있으면:

- 사진 분석을 다시 수행하지 않습니다.
- 임시 embedding rows를 읽습니다.
- clusterId 기준으로 클러스터 UI를 복원합니다.
- `InitStep.ClusterSelect`로 진입합니다.
- 안내 문구를 표시합니다.

예시:

```text
이전 사진 분석 결과를 불러왔습니다.
총 32분 14초 소요된 분석 결과입니다.
```

### 5. 선택 완료
사용자가 클러스터를 선택하고 `선택 완료`를 누르면 현재처럼:

- 선택된 cluster의 사진을 `EventCategory.PHOTO` 이벤트로 저장
- `PhotoRecord` 저장
- `UpdateChildEmbeddingUseCase` 실행

이후 임시 데이터 삭제:

```text
delete initial_scan_photo_embeddings where sessionId = ...
delete initial_scan_sessions where id = ...
```

혹은 session을 `APPLIED`로 표시한 뒤 오래된 데이터를 prune해도 됩니다.

초기 구현 권장:

- 적용 성공 후 즉시 삭제

### 6. 취소
사용자가 분석 중 취소하거나, 클러스터 선택 화면에서 처음으로 돌아가는 UX가 생기면:

- 현재 session과 embedding rows 삭제
- `ScanStateHolder` reset

---

## DAO 설계

후보:

```kotlin
@Dao
interface InitialScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: InitialScanSessionEntity)

    @Query("SELECT * FROM initial_scan_sessions WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    suspend fun getLatestCompletedSession(): InitialScanSessionEntity?

    @Query("DELETE FROM initial_scan_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(items: List<InitialScanPhotoEmbeddingEntity>)

    @Query("SELECT * FROM initial_scan_photo_embeddings WHERE sessionId = :sessionId")
    suspend fun getEmbeddings(sessionId: String): List<InitialScanPhotoEmbeddingEntity>

    @Query("DELETE FROM initial_scan_photo_embeddings WHERE sessionId = :sessionId")
    suspend fun deleteEmbeddings(sessionId: String)
}
```

클러스터 id update는 다음 중 하나로 처리합니다.

- embedding 저장 전 클러스터링까지 끝낸 뒤 최종 rows를 insert
- 먼저 embedding rows를 insert하고, 클러스터링 후 update

초기 구현 권장:

- 서비스 내부에서는 메모리에 embedding을 모아 기존처럼 클러스터링합니다.
- 클러스터링 완료 후 `clusterId`가 포함된 rows를 한 번에 insert합니다.
- 이렇게 하면 DAO가 단순해집니다.

단점:

- 분석 중 앱이 죽으면 중간 결과는 복원되지 않습니다.

장점:

- 구현이 단순합니다.
- ENH의 핵심인 “분석 완료 후 선택 전 종료”를 해결합니다.

전체 수만 장에서 분석 중 종료까지 resume하려면 후속 ENH로 확장합니다.

---

## 구현 단계 제안

### v1: 완료 결과 복원
최소 구현 범위입니다.

- 분석 완료 후 cluster 결과를 DB에 저장
- 앱 재시작 시 completed session 복원
- 선택 완료 후 임시 결과 삭제

해결하는 문제:

- “분석은 끝났는데 선택 전에 앱이 꺼짐” 케이스

해결하지 않는 문제:

- “분석 중간에 앱이 꺼짐” 케이스

### v2: 중간 진행 resume
후속 확장입니다.

- 분석 중 embedding을 batch 저장
- processed uri 기록
- 앱 재시작 후 남은 사진만 이어서 분석

이건 구현 복잡도가 크므로 초기 범위에서 제외합니다.

---

## UI/UX

### 복원 시
앱 시작 시 completed session이 있으면 자동으로 `아이 그룹 선택` 화면으로 이동합니다.

안내 문구:

```text
이전 사진 분석 결과를 불러왔습니다.
총 32분 14초 소요된 분석 결과입니다.
```

### 재분석
사용자가 복원된 결과를 버리고 다시 분석하고 싶을 수 있습니다.

초기 구현에서는 별도 버튼 없이 기존 취소/뒤로 동작으로 초기화할 수 있게 해도 됩니다.

후속 개선:

```text
[다시 분석하기]
```

버튼을 제공하고, 누르면 임시 결과를 삭제한 뒤 새 분석을 시작합니다.

---

## 수용 기준 (Acceptance Criteria)

### 기능 기준
- 초기 사진 분석 완료 후 클러스터링 결과가 임시 DB에 저장된다.
- 앱이 종료된 뒤 다시 열어도 완료된 클러스터 선택 화면을 복원할 수 있다.
- 복원된 화면에서 클러스터를 선택하고 적용하면 기존과 동일하게 사진 이벤트가 생성된다.
- 적용 성공 후 임시 분석 결과가 삭제된다.
- 취소 시 임시 분석 결과가 삭제된다.

### 품질 기준
- 복원된 클러스터의 사진 수와 대표 이미지가 분석 직후 화면과 동일해야 한다.
- 앱 재시작 후에도 총 소요 시간이 표시된다.
- 임시 embedding JSON 파싱 실패 row가 있어도 앱이 crash하지 않는다.
- 오래된 임시 session이 여러 개 있을 경우 가장 최근 completed session 하나만 복원한다.

### 제외 기준
- 분석 중간 resume은 v1 범위에 포함하지 않는다.
- 클라우드 사진 원본 다운로드는 포함하지 않는다.
- 클러스터링 알고리즘 변경은 포함하지 않는다.

---

## 리스크 (Risks)

- 수만 장 embedding을 JSON으로 저장하면 DB 용량이 커질 수 있습니다.
- 완료 후 한 번에 저장하는 v1 방식은 분석 중 앱 종료를 복원하지 못합니다.
- URI가 복원 시점에 이미 삭제되었거나 접근 불가하면 대표 이미지가 깨질 수 있습니다.
- clusterId 저장 후 알고리즘 threshold를 바꾸면 기존 캐시와 현재 알고리즘 결과가 달라질 수 있습니다.

---

## 향후 확장

- 분석 중간 resume
- 오래된 임시 scan session 자동 정리
- 다시 분석하기 버튼
- 임시 scan 결과 저장 용량 표시
- embedding binary 저장 최적화
- 분석 결과 적용 전 사진 원본 존재 여부 재확인
