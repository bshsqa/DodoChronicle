# ENH-005: 이벤트 UX 개선 및 카카오 import 보강

## 상태 범례
- ✅ 구현 완료
- 🔲 미구현 (계획)

---

## 1. 이벤트 카드 UX 개선

### 1.1. ✅ 텍스트 이벤트 롱프레스: 즐겨찾기만 표시

- **변경 전**: 롱프레스 시 드롭다운에 "즐겨찾기"와 "삭제" 두 항목 표시
- **변경 후**: 롱프레스 시 "즐겨찾기" 항목만 표시 (삭제 제거)
- **이유**: 텍스트 이벤트는 카카오 대화 분석 결과물로, 실수로 삭제 시 복구 불가. 사진은 갤러리에서 다시 추가 가능하지만 이벤트는 재분석 없이는 복원 불가능.
- **대상**: `DailyDetailDialog` 내 텍스트 이벤트 인라인 드롭다운

### 1.2. ✅ 사진 이벤트 즐겨찾기 추가

- **변경 전**: 사진 선택 모드 헤더에 "학습 제외/포함"과 "삭제" 버튼만 존재
- **변경 후**: "즐겨찾기" 별 아이콘 버튼 추가 (학습 제외 버튼 왼쪽)
- **동작**:
  - 선택된 사진 중 하나라도 즐겨찾기 아닌 게 있으면 → 전체 즐겨찾기 추가
  - 선택된 사진 모두 즐겨찾기이면 → 전체 즐겨찾기 해제
- **추가된 ViewModel 메서드**: `setFavoriteBatch(eventIds: List<String>, isFavorite: Boolean)`

---

## 2. 카카오 import 로딩 UX 개선

### 2.1. 🔲 취소 가능한 로딩 팝업

- 로딩 중 오버레이에 **[취소] 버튼** 추가
- 취소 시:
  - 진행 중인 API 호출(현재 청크)은 완료 후 중단 (청크 중간 강제 중단 아님)
  - 취소 시점까지 성공한 청크들의 메시지와 이벤트는 DB에 저장
  - `KakaoRoom`의 `lastImportedAt`을 마지막으로 성공한 청크의 마지막 메시지 `sentAt`으로 업데이트
  - 다음에 같은 방으로 같은 파일 재import 시 중단 지점 이후부터 재개
- 취소 후 스낵바: `"가져오기 취소됨 — 메시지 N개, 이벤트 N개 저장됨"`

> **구현 주의**: 현재는 AI 추출 전부 완료 후 DB 일괄 저장 구조. 취소 재개를 지원하려면 **청크 완료마다 부분 저장**으로 구조 변경 필요. 취소 신호는 `MutableStateFlow<Boolean>` 또는 `AtomicBoolean`으로 청크 루프 진입 전 체크.

### 2.2. 🔲 완료 후 결과 확인 화면

- import 완료 시 자동으로 팝업이 닫히지 않고 **[확인] 버튼**이 등장
- 완료 팝업에 표시할 내용:
  - 처리된 메시지 수 / 추출된 이벤트 수
  - API 요청 횟수 / 총 토큰 수
  - 총 소요 시간 (분:초)
  - 실패한 청크 수 (있을 경우)
- [확인] 누르면 팝업 닫히고 타임라인으로 복귀
- `TimelineUiState`에 `importDone: ImportDoneInfo?` 필드 추가. import 완료 시 `isLoading = false` 대신 `importDone = ImportDoneInfo(...)` 로 전환.

---

## 3. 카카오 파싱 및 LLM 컨텍스트 보강

### 3.1. 🔲 모바일 포맷 헤더 명시적 무시

카카오 모바일 내보내기 파일 첫 두 줄 형식:

```
백다래‥, 아빠, 엄마 4 님과 카카오톡 대화   ← 방 정보
저장한 날짜 : 2026년 4월 24일 오후 9:09   ← 저장 날짜
```

- 현재도 자연스럽게 무시되긴 하지만, 다중행 메시지 처리 중 잘못 붙을 가능성 존재
- `KakaoParser`에서 해당 줄 패턴을 명시적으로 스킵:

```kotlin
private val roomHeaderPattern = Regex(".+님과 카카오톡 대화")
private val saveDatePattern = Regex("저장한 날짜 :.+")

// 파싱 루프에서:
if (roomHeaderPattern.matches(line) || saveDatePattern.matches(line)) continue
```

- 방 이름 자동 감지는 사용하지 않음. 단톡방은 참여자 변경 시 방 이름이 바뀌므로 사용자가 직접 별명 지정하는 현재 방식 유지.

### 3.2. 🔲 LLM에 아이 생년월일·성별·나이 전달

현재 LLM 프롬프트에는 아이 이름만 전달. 생년월일과 성별 추가 시:

- **나이 기반 맥락**: "걸음마를 시작했어" → 몇 개월짜리 아이인지 LLM이 판단 가능
- **성별 표현 인식**: "왕자님", "공주님" 등 지칭 표현 정확도 향상

**나이 계산 기준**: import 시점이 아닌 **해당 청크의 날짜 범위** 기준으로 계산. 10년치 대화를 청크별로 처리하므로 청크마다 아이 나이가 달라짐.

```kotlin
private fun calculateAge(birthDate: LocalDate, refDate: LocalDate): String {
    val months = ChronoUnit.MONTHS.between(birthDate, refDate)
    return if (months < 24) "${months}개월" else "${months / 12}세 ${months % 12}개월"
}

// buildPrompt()에서 청크 시작/끝 날짜로 나이 범위 계산
val ageStart = calculateAge(birthDate, chunkStartDate)
val ageEnd   = calculateAge(birthDate, chunkEndDate)
val ageStr   = if (ageStart == ageEnd) ageStart else "$ageStart ~ $ageEnd"
```

프롬프트 예시:
```
아이 이름: 홍길동
생년월일: 2020-03-15
이 대화 시점 나이: 9개월 ~ 1세 3개월
성별: 남아
```

**변경 파일**: `GeminiEventClassifier.kt` (`extractEvents`, `extractFromChunk`, `buildPrompt`), `ImportKakaoUseCase.kt` (`child.birthDate`, `child.gender` 전달)

---

## 4. 파일 변경 목록

| 파일 | 상태 | 변경 내용 |
|------|------|-----------|
| `TimelineScreen.kt` | ✅ | 텍스트 이벤트 드롭다운 삭제 제거; 사진 즐겨찾기 버튼 추가 |
| `TimelineViewModel.kt` | ✅ | `setFavoriteBatch()` 추가 |
| `KakaoParser.kt` | 🔲 | 헤더 2줄 명시적 스킵 |
| `GeminiEventClassifier.kt` | 🔲 | `birthDate`, `gender`, 청크 기준 나이 전달 |
| `ImportKakaoUseCase.kt` | 🔲 | `child.birthDate`, `child.gender` 전달; 청크 완료마다 부분 저장 |
| `TimelineViewModel.kt` | 🔲 | `cancelImport()`, `dismissImportResult()`, `ImportDoneInfo` 상태 |
| `TimelineScreen.kt` | 🔲 | 로딩 팝업 [취소] 버튼; 완료 팝업 결과 + [확인] 버튼 |
