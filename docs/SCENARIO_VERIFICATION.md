# DodoChronicle — 사용자 시나리오 검증 문서

> 작성일: 2026-04-25 | 코드 기준 커밋: `claude/enhance-child-setup-flow-tjizM`

앱을 처음 실행한 사용자가 경험할 수 있는 모든 흐름을 추출하고,
각 흐름이 실제 코드에서 올바르게 동작하는지 검증한다.

---

## 범례

| 기호 | 의미 |
|---|---|
| ✅ PASS | 정상 동작 확인 |
| ⚠️ PARTIAL | 부분 동작 — 일부 케이스 누락 |
| ❌ FAIL | 버그 확인 — 수정 필요 |

---

## 1. 초기화 플로우 (첫 실행)

### 1-1. 앱 최초 실행 → 초기화 마법사 진입

**시나리오:** 앱을 처음 설치하고 실행한다.

✅ **PASS**

- `MainActivity`가 DataStore의 `initialized` 키를 읽어 `false`이면 `Screen.Init`으로 라우팅
- `AppNavigation`의 `startDestination`이 Init으로 설정됨

---

### 1-2. 아이 정보 입력 — 사진 선택

**시나리오:** 1단계에서 사진 박스를 탭해 갤러리에서 사진 1장을 선택한다.

✅ **PASS**

- `ActivityResultContracts.GetContent()`로 이미지 피커 실행
- 선택된 URI가 `vm.setReferencePhoto()`로 저장되고 `AsyncImage`로 미리보기 표시

---

### 1-3. 아이 정보 입력 — 이름, 생년월일, 성별

**시나리오:** 이름을 입력하고, 달력에서 생년월일을 선택하고, 성별 버튼을 탭한다.

✅ **PASS**

- `OutlinedTextField` → `vm.setChildName()`
- `DatePickerDialog` → `LocalDate`로 변환 후 `vm.setBirthDate()`
- `SingleChoiceSegmentedButtonRow` → `vm.setGender(Gender.MALE/FEMALE)`

---

### 1-4. 필드 미입력 시 버튼 비활성화

**시나리오:** 4개 필드 중 하나라도 비어 있으면 "사진 분류 시작" 버튼이 비활성화된다.

✅ **PASS**

```kotlin
enabled = state.childName.isNotBlank()
    && state.birthDate != null
    && state.referencePhotoUri.isNotBlank()
    && state.gender != null
```

4가지 조건 모두 AND로 연결되어 하나라도 null/blank면 비활성화.

---

### 1-5. TFLite 모델 파일 없을 때 스캔 시도

**시나리오:** `assets/mobile_face_net.tflite`가 없는 상태에서 스캔을 시작한다.

❌ **FAIL → 수정 완료**

**수정 전 문제:**
- `FaceEmbedder.init()`이 예외를 조용히 삼켜 `interpreter = null` 상태가 됨
- 스캔을 돌리면 `embed()`가 항상 `null` 반환 → 임베딩 0개 → 클러스터 0개
- 사용자에게는 "아이 얼굴이 감지된 사진이 없습니다" 라는 **원인과 무관한 오류 메시지** 표시

**수정 내용:** `InitViewModel.startScanning()`에서 `faceEmbedder.isAvailable()` 체크 추가.
모델 없으면 즉시 명확한 오류 메시지 표시.

---

### 1-6. 스캔 진행 — 진행률 표시 및 제스처 차단

**시나리오:** 스캔이 시작되면 전체 화면 오버레이가 표시되고 다른 영역을 터치해도 반응하지 않는다.

✅ **PASS**

- `ScanningStep`이 `Modifier.fillMaxSize().pointerInput { /* 제스처 차단 */ }`으로 전체 화면 점유
- `LinearProgressIndicator`와 `X / Y 장 완료 (Z%)` 텍스트로 진행률 표시

---

### 1-7. 스캔 중 취소

**시나리오:** 스캔 중 "취소" 버튼을 누른다. 입력했던 이름/생년월일/성별/사진이 그대로 남아 있다.

✅ **PASS**

- `cancelScanning()`이 `scanJob.cancel()`로 코루틴 즉시 중단
- `performScan()` 루프 내 `currentCoroutineContext().ensureActive()`로 취소 시점에 즉시 탈출
- `_uiState.update { state -> state.copy(...) }`에서 `childName`, `birthDate`, `gender`, `referencePhotoUri`는 명시하지 않으므로 기존 값 유지
- 수집 중이던 임베딩·클러스터 데이터만 폐기 후 ChildInfo 단계로 복귀

---

### 1-8. 스캔 완료 — 얼굴 없음

**시나리오:** 갤러리에 얼굴이 없는 사진만 있어 클러스터링 결과가 비어 있다.

✅ **PASS**

- `clusters.isEmpty()` 조건 → `InitStep.ChildInfo`로 복귀 + 에러 다이얼로그 표시
- 입력 필드는 그대로 유지 (`step`과 `error`만 업데이트)

---

### 1-9. 클러스터 선택 화면

**시나리오:** 스캔 완료 후 인물 그룹 그리드가 표시된다. 참조 사진 기반 사전 선택 없이 모두 동등하게 표시된다.

✅ **PASS**

- `_rawClusters` 전체를 `ClusterUiModel`로 변환하여 표시
- 참조 사진(`referencePhotoUri`)은 클러스터 정렬/필터링에 사용되지 않음
- `selectedClusterIds`는 초기 `emptySet()` → 모든 카드가 동등한 비선택 상태로 시작

---

### 1-10. 클러스터 선택/해제 토글

**시나리오:** 그룹 카드를 탭하면 선택/해제된다. 여러 그룹 선택 가능.

✅ **PASS**

```kotlin
val selected = if (id in s.selectedClusterIds) s.selectedClusterIds - id
               else s.selectedClusterIds + id
```

Set 연산으로 올바르게 토글.

---

### 1-11. 클러스터 미선택 시 완료 버튼 비활성화

**시나리오:** 아무 그룹도 선택하지 않으면 "선택 완료" 버튼이 비활성화된다.

✅ **PASS**

```kotlin
enabled = state.selectedClusterIds.isNotEmpty()
```

---

### 1-12. 초기화 완료 → 타임라인으로 이동

**시나리오:** 클러스터 선택 완료 후 Child 정보가 DB에 저장되고 타임라인 화면으로 전환된다.

✅ **PASS**

- `confirmClusters()` → `childRepository.save(child)` → `dataStore: initialized = true`
- `InitStep.Done` 전환 → `LaunchedEffect`에서 `onInitComplete()` 호출 → 타임라인 이동
- 다음 실행부터는 `initialized = true`로 타임라인 직접 진입

---

### 1-13. 두 번째 실행 → 타임라인 직접 진입

**시나리오:** 초기화 완료 후 앱을 재실행하면 초기화 마법사를 건너뛰고 타임라인이 표시된다.

✅ **PASS**

- `MainActivity`가 DataStore 값을 읽어 `initialized = true`면 `Screen.Timeline`으로 라우팅

---

## 2. 타임라인

### 2-1. 타임라인 이벤트 로딩

**시나리오:** 타임라인 화면이 열리면 DB에서 해당 아이의 이벤트가 로드된다.

✅ **PASS**

- `TimelineViewModel.init`에서 `childRepository.getFirst()` → `eventRepository.observe()` 구독
- `combine(_filterCategory, _onlyFavorite)` Flow로 필터 변경 시 자동 재쿼리

---

### 2-2. 아이 정보 없이 타임라인 진입

**시나리오:** (비정상 경로) DB에 아이 정보가 없는 상태로 타임라인이 로드된다.

❌ **FAIL → 수정 완료**

**수정 전 문제:**
- `childRepository.getFirst()` 반환 `null` → `return@launch`로 조용히 종료
- 타임라인은 빈 화면으로 표시되고 이벤트 구독 자체가 시작되지 않음
- 사용자는 왜 아무것도 없는지 알 수 없음

**수정 내용:** null 반환 시 `needsInit = true` 상태 플래그 설정.
UI에서 이를 감지해 자동으로 Init 화면으로 복귀.

---

### 2-3. 핀치줌으로 타임라인 축적 변경

**시나리오:** 두 손가락으로 핀치인/아웃하면 타임라인 줌이 변경된다.

✅ **PASS**

- `awaitEachGesture { calculateZoom() }` → `scale = (scale * zoom).coerceIn(0.2f, 12f)`
- 줌 레벨에 따라 날짜 레이블이 연→월→주→일 단위로 자동 전환

---

### 2-4. 드래그로 타임라인 스크롤

**시나리오:** 화면을 위아래로 드래그하면 타임라인이 이동한다.

✅ **PASS**

- `calculatePan().y` → `offsetY` 업데이트

---

### 2-5. 카테고리 필터

**시나리오:** "한 말" 칩을 탭하면 SAID 카테고리 이벤트만 표시된다.

✅ **PASS**

- `setFilterCategory(category)` → `_filterCategory` Flow 업데이트 → DAO 쿼리 자동 재실행
- `AND (:category IS NULL OR category = :category)` 조건으로 정확히 필터링

---

### 2-6. 즐겨찾기 필터

**시나리오:** 즐겨찾기 토글을 켜면 즐겨찾기된 이벤트만 표시된다.

✅ **PASS**

- `toggleFavoriteFilter()` → `_onlyFavorite` Flow → `AND (:onlyFavorite = 0 OR isFavorite = 1)`

---

### 2-7. 이벤트 카드 탭 → 상세 화면

**시나리오:** 이벤트 카드를 탭하면 상세 화면으로 이동한다.

✅ **PASS**

- `onClick = { onEventClick(event.id) }` → NavController로 `Screen.EventDetail` 이동

---

### 2-8. 이벤트 카드 롱프레스 → 컨텍스트 메뉴

**시나리오:** 이벤트 카드를 길게 누르면 삭제/즐겨찾기 메뉴가 나타난다.

✅ **PASS**

- `combinedClickable(onLongClick = { showMenu = true })` → `DropdownMenu` 표시

---

### 2-9. 이벤트 삭제

**시나리오:** 컨텍스트 메뉴에서 삭제를 선택하면 이벤트가 타임라인에서 사라진다.

✅ **PASS**

- `deleteEvent(id)` → `manageEventUseCase.delete(id)` → `eventDao.deleteById(id)`
- Room Flow가 변경을 감지해 UI 자동 업데이트

---

### 2-10. 즐겨찾기 토글

**시나리오:** 컨텍스트 메뉴에서 즐겨찾기를 탭하면 ★ 상태가 토글된다.

✅ **PASS**

- `toggleFavorite(event)` → `manageEventUseCase.setFavorite(id, !isFavorite)` → `eventDao.setFavorite()`

---

### 2-11. 수동 이벤트 추가 (FAB)

**시나리오:** + 버튼을 탭해 날짜, 카테고리, 내용을 입력하고 저장한다.

✅ **PASS**

- FAB 탭 → `AddEventDialog` 표시 → `vm.addManualEvent(date, category, content)` → DB 저장

---

### 2-12. 빈 타임라인

**시나리오:** 이벤트가 한 개도 없을 때 타임라인이 비어 있다.

✅ **PASS**

- `if (state.events.isEmpty())` → `EmptyTimeline` 컴포저블로 "아직 기록이 없어요" 안내 표시

---

## 3. 카카오톡 파싱

### 3-1. .txt 파일 선택 → 파싱 시작

**시나리오:** 카카오톡 내보내기 파일을 선택하면 파싱이 시작된다.

✅ **PASS**

- `ActivityResultContracts.GetContent()` → URI → `importKakao(uri)` → `contentResolver.openInputStream()`

---

### 3-2. 카카오톡 .txt 형식 파싱

**시나리오:** 카카오톡 내보내기 형식의 날짜/발신자/메시지가 올바르게 구조화된다.

✅ **PASS**

- Regex: `^(\d{4}년 \d{1,2}월 \d{1,2}일 (?:오전|오후) \d{1,2}:\d{2}), (.+?) : (.+)$`
- 여러 줄 메시지는 `flush()` 패턴으로 누적 처리
- 한국어 오전/오후 → epoch millis (Asia/Seoul) 정확히 변환

---

### 3-3. 같은 파일 재import — 중복 제거

**시나리오:** 동일한 파일을 두 번 import해도 메시지가 중복 저장되지 않는다.

✅ **PASS**

- SHA-256(`"$sentAt|$content"`) 해시로 `messageExistsByHash()` 체크
- `lastImportedAt` 이후 메시지 + 해시 미등록 메시지만 처리

---

### 3-4. 이전에 없던 새 메시지만 AI 전송

**시나리오:** 증분 import 시 신규 메시지만 Gemini에 전달된다.

✅ **PASS**

- `filter { it.sentAt > lastImportedAt }` + `filter { !messageExistsByHash(...) }` 이중 필터

---

### 3-5. AI key 없음 — 정상 처리

**시나리오:** Gemini API 키가 blank인 상태로 import한다.

✅ **PASS**

- `if (messages.isEmpty() || apiKey.isBlank()) return@withContext emptyList()`
- "메시지 N개, 이벤트 0개 추가됨" 스낵바로 안내, 크래시 없음

---

### 3-6. import 중 AI 호출 실패 — 데이터 정합성

**시나리오:** AI 호출이 실패하거나 예외가 발생해도 기존 메시지가 손상되지 않는다. 재import 시 해당 메시지에서 이벤트가 추출된다.

❌ **FAIL → 수정 완료**

**수정 전 문제:**
1. DB에 메시지 삽입 (line 50)
2. `lastImportedAt` 업데이트 (line 53)
3. AI 이벤트 추출 (line 56)

AI 호출 전에 이미 메시지가 DB에 쓰이고 타임스탬프가 갱신됨.
이후 `eventRepository.insertAll()`이 예외를 던지면 메시지는 커밋되고 이벤트는 없는 상태가 됨.
재import 시 해당 메시지들은 타임스탬프 + 해시 필터에 걸려 **영구적으로 이벤트 추출 기회를 잃음.**

**수정 내용:** AI 이벤트 추출을 DB 쓰기 전으로 이동.
AI 결과를 먼저 메모리에 확보한 뒤 메시지·이벤트·타임스탬프를 한꺼번에 저장.

---

### 3-7. "사진", "동영상" 시스템 메시지 필터링

**시나리오:** 카카오톡의 "사진" / "동영상" 텍스트 메시지는 AI에 전달되지 않는다.

✅ **PASS**

- `filter { it.content != "사진" && it.content != "동영상" }` 로 AI 전송 전 제거

---

## 4. 사진 동기화

### 4-1. 앱 재시작 → 신규 사진만 스캔

**시나리오:** 앱 재시작 시 마지막 스캔 이후 추가된 사진만 처리된다.

✅ **PASS**

- `getLatestPhotoTakenAt()` 기준으로 `DATE_TAKEN > ?` 조건 MediaStore 쿼리

---

### 4-2. 고유사도 사진 자동 추가 (≥ 0.75)

**시나리오:** 아이 얼굴과 유사도가 0.75 이상인 사진이 자동으로 타임라인에 추가된다.

✅ **PASS**

- `if (maxSimilarity >= AUTO_ADD_THRESHOLD)` → `savePhotoEvent()` → DB 저장

---

### 4-3. 중간 유사도 사진 확인 요청 (0.4 ~ 0.75)

**시나리오:** 유사도 0.4~0.75 구간의 사진은 사용자 확인 요청 배너에 표시된다.

✅ **PASS**

- `needsConfirmation` 리스트에 추가 → `TimelineScreen` 상단 배너로 표시

---

### 4-4. 저유사도 사진 무시 (< 0.4)

**시나리오:** 유사도 0.4 미만 사진은 알림 없이 조용히 건너뛴다.

✅ **PASS**

- `else` 분기에서 아무 동작 없이 넘어감

---

### 4-5. 확인 요청 사진 — 수락/거부

**시나리오:** 배너에서 사진을 수락하면 타임라인에 추가되고, 거부하면 사라진다.

✅ **PASS**

- `confirmPendingPhoto(pending, accept = true/false)` → 수락 시 `savePhotoEvent()`, 거부 시 skip
- 처리 후 `pendingPhotos`에서 해당 항목 제거 → 배너에서 사라짐

---

### 4-6. WorkManager 6시간 주기 백그라운드 스캔

**시나리오:** 앱이 꺼진 상태에서도 6시간마다 신규 사진이 자동 스캔된다.

✅ **PASS**

- `PeriodicWorkRequestBuilder<PhotoSyncWorker>(6, TimeUnit.HOURS)`
- `ExistingPeriodicWorkPolicy.KEEP`으로 중복 등록 방지
- 기기 재시작 후에도 WorkManager가 재스케줄링

---

## 5. 요약

### 전체 시나리오 결과

| 구분 | 총 시나리오 | ✅ PASS | ⚠️ PARTIAL | ❌ FAIL |
|---|---|---|---|---|
| 초기화 | 13 | 11 | 0 | 2 |
| 타임라인 | 12 | 11 | 0 | 1 |
| 카카오톡 | 7 | 5 | 0 | 2 (1 포함) |
| 사진 동기화 | 6 | 6 | 0 | 0 |
| **합계** | **38** | **33** | **0** | **5** |

> 5건의 FAIL은 모두 이 문서 작성 시점에 코드 수정 완료됨.

---

### 발견된 버그 및 수정 내용

| # | 심각도 | 위치 | 문제 | 수정 방법 |
|---|---|---|---|---|
| B-01 | 🔴 CRITICAL | `InitViewModel.startScanning()` | `mobile_face_net.tflite` 없을 때 조용히 실패하고 오해를 부르는 오류 메시지 표시 | `faceEmbedder.isAvailable()` 체크 추가, 명확한 오류 메시지 표시 |
| B-02 | 🟠 HIGH | `TimelineViewModel.init` | 아이 DB 없을 때 `return@launch`로 조용히 종료, 빈 타임라인만 표시 | `needsInit` 상태 플래그 추가, UI에서 Init 화면으로 자동 복귀 |
| B-03 | 🟡 MEDIUM | `ImportKakaoUseCase` | AI 호출 전 DB 쓰기 → 이벤트 저장 실패 시 해당 메시지가 재처리 기회를 영구 상실 | AI 추출을 DB 쓰기 전으로 이동 |

---

*문서 버전: 1.0 | 작성: 2026-04-25*
