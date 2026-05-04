# ENH-019: 초기 사진 분석 중간 Resume 및 누적 클러스터링

## 목표 (Objective)
초기 사진 분석이 수만 장 규모로 오래 걸리더라도, 앱/서비스가 중간에 종료되었을 때 처음부터 다시 분석하지 않고 이어서 진행할 수 있게 합니다.

또한 클러스터링 과정에서 기존처럼 클러스터 평균을 매번 다시 계산하지 않고, 클러스터별 누적 embedding sum/count를 저장해 빠르게 이어 붙일 수 있게 합니다.

---

## 배경 (Background)
ENH-018은 “분석 완료 후, 사용자가 클러스터 선택하기 전 앱이 종료되는 경우”를 복원합니다.

하지만 4만 장 수준의 전체 사진 분석에서는 분석 자체가 여러 시간 걸릴 수 있습니다. 이 경우 다음 문제가 남습니다.

- 분석 중 앱/서비스가 죽으면 처음부터 다시 해야 합니다.
- 현재 클러스터링은 비교할 때마다 클러스터 멤버들의 평균 embedding을 다시 계산합니다.
- 전체 사진을 모두 메모리에 모은 뒤 클러스터링하는 방식은 장시간 작업/대량 사진에 취약합니다.

따라서 초기 분석을 “사진별 상태 + 누적 클러스터 상태”로 DB에 기록하면서 진행해야 합니다.

---

## 핵심 개념

### 1. 분석 대상 사진 목록 고정
초기 스캔 시작 시 MediaStore에서 분석 대상 사진 목록을 한 번 조회해 session item으로 저장합니다.

각 item은 이후 다음 상태 중 하나를 가집니다.

```text
PENDING
PROCESSING
PROCESSED
NO_FACE
FAILED
```

### 2. 청크는 checkpoint 단위
청크는 서버 API 제한 때문이 아니라, 내부 checkpoint 단위입니다.

예시:

```text
INITIAL_SCAN_CHECKPOINT_SIZE = 500
```

500장 처리 후:

- item 상태 저장
- 성공한 embedding 저장
- cluster 누적 상태 저장
- session 진행률 저장

서비스가 죽으면 다음 실행 때 이미 `PROCESSED / NO_FACE / FAILED`인 item은 건너뛰고 `PENDING`부터 이어갑니다.

### 3. 누적 클러스터링
현재 클러스터링은 사진을 순차로 보며 기존 클러스터 centroid와 비교합니다.

이 구조는 resume에 적합합니다. 단, centroid를 매번 전체 멤버 평균으로 다시 계산하지 않고 클러스터 row에 누적값을 저장합니다.

클러스터 저장값:

```text
embeddingSumJson
count
representativeUrisJson
```

새 embedding을 기존 클러스터에 추가하면:

```text
newSum = oldSum + embedding
newCount = oldCount + 1
newCentroid = newSum / newCount
```

비교할 때는 `oldSum / oldCount`로 centroid만 계산합니다. 클러스터 안의 모든 embedding을 다시 읽지 않습니다.

---

## DB 설계

ENH-018의 임시 테이블은 v1 완료 복원에는 충분하지만, 중간 resume에는 item 상태와 cluster 누적 상태가 필요합니다.

초기 구현은 기존 ENH-018 테이블을 확장하기보다, 명확한 용도의 새 테이블을 추가하는 방향을 권장합니다.

### 1. Session
기존 `initial_scan_sessions`를 유지하고 확장합니다.

현재 후보:

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

추가 권장 필드:

```kotlin
val lastCheckpointAt: Long = 0
```

`status`:

```text
RUNNING
PAUSED
COMPLETED
CANCELLED
APPLIED
FAILED
```

초기 resume 구현에서는 앱/서비스가 죽어 `RUNNING`으로 남아 있어도 resume 가능한 session으로 취급합니다.

### 2. Scan Items
분석 대상 사진 목록과 처리 상태를 저장합니다.

```kotlin
@Entity(
    tableName = "initial_scan_items",
    indices = [
        Index("sessionId"),
        Index("status"),
        Index("uri", unique = true),
        Index("clusterId")
    ]
)
data class InitialScanItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val uri: String,
    val takenAt: Long,
    val status: String,
    val embeddingJson: String = "[]",
    val clusterId: Int? = null,
    val errorMessage: String = "",
    val updatedAt: Long = 0
)
```

상태 의미:

- `PENDING`: 아직 처리하지 않음
- `PROCESSING`: 현재 처리 중. 앱이 죽으면 다음 시작 때 `PENDING`으로 되돌려도 됨
- `PROCESSED`: 얼굴 embedding 생성 및 클러스터 배정 완료
- `NO_FACE`: 얼굴 없음
- `FAILED`: 이미지 로딩/embedding 실패

### 3. Clusters
누적 클러스터 상태를 저장합니다.

```kotlin
@Entity(
    tableName = "initial_scan_clusters",
    primaryKeys = ["sessionId", "clusterId"],
    indices = [Index("sessionId")]
)
data class InitialScanClusterEntity(
    val sessionId: String,
    val clusterId: Int,
    val embeddingSumJson: String,
    val count: Int,
    val representativeUrisJson: String,
    val updatedAt: Long
)
```

대표 URI는 UI preview용입니다.

규칙:

- 최대 9개만 저장
- 처음 들어온 사진부터 유지
- 필요하면 대표 이미지 품질 기준은 후속으로 개선

---

## DAO 설계

```kotlin
@Dao
interface InitialScanDao {
    // session
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: InitialScanSessionEntity)

    @Query("SELECT * FROM initial_scan_sessions WHERE status IN ('RUNNING', 'PAUSED', 'COMPLETED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getResumableSession(): InitialScanSessionEntity?

    @Query("UPDATE initial_scan_sessions SET status = :status, processedCount = :processedCount, elapsedSeconds = :elapsedSeconds, lastCheckpointAt = :checkpointAt WHERE id = :sessionId")
    suspend fun updateSessionProgress(
        sessionId: String,
        status: String,
        processedCount: Int,
        elapsedSeconds: Long,
        checkpointAt: Long
    )

    // items
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<InitialScanItemEntity>)

    @Query("SELECT COUNT(*) FROM initial_scan_items WHERE sessionId = :sessionId AND status IN ('PROCESSED', 'NO_FACE', 'FAILED')")
    suspend fun countFinishedItems(sessionId: String): Int

    @Query("SELECT * FROM initial_scan_items WHERE sessionId = :sessionId AND status IN ('PENDING', 'PROCESSING') ORDER BY takenAt DESC LIMIT :limit")
    suspend fun getNextPendingItems(sessionId: String, limit: Int): List<InitialScanItemEntity>

    @Query("UPDATE initial_scan_items SET status = 'PENDING' WHERE sessionId = :sessionId AND status = 'PROCESSING'")
    suspend fun resetProcessingItems(sessionId: String)

    @Update
    suspend fun updateItems(items: List<InitialScanItemEntity>)

    // clusters
    @Query("SELECT * FROM initial_scan_clusters WHERE sessionId = :sessionId")
    suspend fun getClusters(sessionId: String): List<InitialScanClusterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClusters(clusters: List<InitialScanClusterEntity>)

    // cleanup
    @Query("DELETE FROM initial_scan_items WHERE sessionId = :sessionId")
    suspend fun deleteItems(sessionId: String)

    @Query("DELETE FROM initial_scan_clusters WHERE sessionId = :sessionId")
    suspend fun deleteClusters(sessionId: String)

    @Query("DELETE FROM initial_scan_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
```

---

## 처리 흐름

### 1. 새 분석 시작
새 분석 시작 시:

1. 기존 임시 session 삭제 또는 사용자의 확인 후 삭제
2. MediaStore에서 대상 사진 목록 조회
3. `initial_scan_items`에 모두 `PENDING`으로 저장
4. session 생성
5. `ScanForegroundService` 시작

주의:

- item 목록 저장이 너무 크면 4만 row insert가 발생합니다.
- 그래도 분석 전체 비용에 비하면 감당 가능한 수준입니다.
- insert는 batch로 수행합니다.

### 2. Resume 시작
앱 시작 또는 분석 화면 진입 시:

1. `getResumableSession()` 조회
2. `COMPLETED`면 바로 클러스터 선택 화면 복원
3. `RUNNING` 또는 `PAUSED`면 “이전 사진 분석을 이어서 진행할까요?” 안내

초기 구현은 자동 resume도 가능하지만, 사용자가 의도하지 않은 장시간 작업이 시작될 수 있으므로 확인 UI를 권장합니다.

문구:

```text
이전 사진 분석이 완료되지 않았습니다.
3,240 / 40,123장까지 분석했습니다.
이어서 진행할까요?
```

버튼:

```text
[이어서 분석] [처음부터 다시]
```

### 3. 청크 처리
서비스는 다음 loop를 반복합니다.

```text
while (pending item exists):
    items = getNextPendingItems(sessionId, 500)
    mark items PROCESSING

    for item in items:
        bitmap load
        face detect
        embedding generate
        assign to cluster
        update item status/result in memory

    DB transaction:
        update item rows
        upsert cluster rows
        update session progress
```

### 4. 사진별 처리 결과
사진 1장 처리 규칙:

```kotlin
val bitmap = loadBitmap(uri)
if (bitmap == null) {
    status = FAILED
    errorMessage = "bitmap load failed"
    return
}

val faces = faceDetector.detectFaces(bitmap)
if (faces.isEmpty()) {
    status = NO_FACE
    return
}

val embedding = faceEmbedder.embed(bitmap, faces.first())
if (embedding == null) {
    status = FAILED
    errorMessage = "embedding failed"
    return
}

val clusterId = assignToCluster(embedding)
status = PROCESSED
embeddingJson = embedding
clusterId = clusterId
```

### 5. 클러스터 배정
서비스는 session의 cluster 상태를 메모리에 유지합니다.

```kotlin
data class MutableScanCluster(
    val clusterId: Int,
    val embeddingSum: FloatArray,
    var count: Int,
    val representativeUris: MutableList<String>
) {
    val centroid: FloatArray
        get() = FloatArray(embeddingSum.size) { embeddingSum[it] / count }
}
```

배정:

```kotlin
var bestCluster: MutableScanCluster? = null
var bestSimilarity = CLUSTER_THRESHOLD

for (cluster in clusters) {
    val sim = cosineSimilarity(cluster.centroid, embedding)
    if (sim > bestSimilarity) {
        bestSimilarity = sim
        bestCluster = cluster
    }
}

if (bestCluster == null) {
    createNewCluster(embedding, uri)
} else {
    bestCluster.embeddingSum += embedding
    bestCluster.count += 1
    if (bestCluster.representativeUris.size < 9) {
        bestCluster.representativeUris += uri
    }
}
```

### 6. 완료
모든 item이 `PENDING/PROCESSING`이 아니게 되면:

1. session `COMPLETED`
2. 클러스터 선택 화면 복원
3. 사용자는 클러스터 선택
4. 선택된 clusterId의 `PROCESSED` item을 실제 `Event/PhotoRecord`로 저장
5. 임시 session/items/clusters 삭제

---

## UI/UX

### 진행 화면
진행 중 표시:

```text
사진 분석 중...
3,240 / 40,123 장 완료 (8%)
현재까지 42분 12초 소요
```

추가 가능:

```text
앱을 닫아도 나중에 이어서 분석할 수 있습니다.
```

### Resume 안내
앱 재실행 시 미완료 session이 있으면:

```text
이전 사진 분석이 완료되지 않았습니다.
3,240 / 40,123장까지 분석했습니다.
이어서 진행할까요?
```

버튼:

```text
이어서 분석
처음부터 다시
```

### 완료 결과 복원
ENH-018과 동일합니다.

```text
이전 사진 분석 결과를 불러왔습니다.
총 32분 14초 소요된 분석 결과입니다.
```

---

## 기존 ENH-018과의 관계

ENH-018은 완료 결과 복원입니다.

ENH-019는 이를 확장해 분석 중간 상태까지 저장합니다.

구현 시 선택지:

### 선택지 A: ENH-018 테이블 유지 + ENH-019 테이블 추가
장점:

- 기존 구현을 크게 깨지 않습니다.
- 점진적 적용이 쉽습니다.

단점:

- 임시 스캔 관련 테이블이 중복됩니다.

### 선택지 B: ENH-019 테이블로 통합
장점:

- 구조가 명확합니다.
- 완료 복원과 중간 resume이 한 체계로 묶입니다.

단점:

- 기존 ENH-018 구현을 일부 교체해야 합니다.

권장:

- 바로 구현한다면 선택지 B를 권장합니다.
- `initial_scan_photo_embeddings`는 제거하거나 더 이상 사용하지 않고, `initial_scan_items`와 `initial_scan_clusters`로 통합합니다.

---

## 수용 기준 (Acceptance Criteria)

### 기능 기준
- 초기 사진 분석 시작 시 대상 사진 목록이 session item으로 저장된다.
- 분석 중 앱/서비스가 종료되어도 이미 처리한 사진은 다시 처리하지 않는다.
- 앱 재시작 시 미완료 session을 이어서 분석할 수 있다.
- 501번째 사진은 앞 청크까지 누적된 cluster 상태를 기준으로 기존 클러스터에 배정될 수 있다.
- 모든 사진 분석 완료 후 클러스터 선택 화면이 표시된다.
- 완료 후 선택 전 앱이 종료되어도 클러스터 선택 화면이 복원된다.
- 선택 완료 후 임시 session/items/clusters가 삭제된다.

### 성능 기준
- 클러스터 비교 시 클러스터 전체 embedding을 매번 다시 평균내지 않는다.
- cluster별 `embeddingSum/count`를 사용해 centroid를 계산한다.
- DB write는 사진 1장마다가 아니라 checkpoint 단위로 묶어서 수행한다.

### 안정성 기준
- `PROCESSING` 상태에서 앱이 죽은 item은 재시작 시 다시 처리 대상이 된다.
- embeddingJson 파싱 실패 cluster/item은 앱 crash 없이 제외하거나 FAILED 처리한다.
- URI 접근 실패 사진은 FAILED로 기록하고 전체 분석은 계속한다.

---

## 리스크 (Risks)

- item 4만 개와 embedding JSON 저장으로 DB 용량이 커질 수 있습니다.
- checkpoint 크기가 너무 작으면 DB write 비용이 커지고, 너무 크면 재시작 시 재처리 손실이 커집니다.
- 누적 centroid 방식은 현재 순차 클러스터링의 특성을 유지하므로, 사진 처리 순서에 영향을 받습니다.
- 분석 대상 목록을 시작 시 고정하므로, 분석 중 새로 추가된 사진은 다음 신규 사진 동기화에서 처리합니다.

---

## 기본 상수 제안

```kotlin
private const val INITIAL_SCAN_CHECKPOINT_SIZE = 500
private const val INITIAL_SCAN_CLUSTER_THRESHOLD = 0.68f
private const val INITIAL_SCAN_MAX_REPRESENTATIVES = 9
```

초기 값은 실제 기기에서 테스트하며 조정합니다.

---

## 향후 확장

- 예상 남은 시간 표시
- 충전 중/배터리 충분 조건 안내
- 실패 사진 재시도
- 오래된 session 자동 정리
- embedding binary 저장 최적화
- 클러스터 병합/분할 후처리
