# 문서 충돌 분석 보고서

> 분석일: 2026-04-25 | 대상 브랜치: `claude/enhance-child-setup-flow-tjizM`

분석 대상 문서:
- `docs/REQUIREMENTS.md` (v1.3)
- `docs/SCENARIO_VERIFICATION.md`
- `docs/CHANGE_PLAN_20260425.md`
- `README.md`

---

## 요약

최초 분석 시 발견된 8개의 충돌이 모두 해소되었다.
현재 모든 문서는 실제 코드 동작을 기준으로 일관되게 정렬되어 있다.

| 충돌 ID | 주제 | 해소 방법 | 상태 |
|---|---|---|---|
| C-01 | 아키텍처 다이어그램 vs 실제 코드 | REQUIREMENTS.md §2 재작성 | ✅ 해소 |
| C-02 | ContentObserver 구현 여부 | SYNC-06 Phase 4로 명시, REQUIREMENTS.md 수정 | ✅ 해소 |
| C-03 | UI-06 이벤트 카드 동작 (확장 vs 이동) | REQUIREMENTS.md UI-06 "상세 화면 이동"으로 수정 | ✅ 해소 |
| C-04 | AI 이름 — Claude vs Gemini | REQUIREMENTS.md KAKO-05, 기술 스택 Gemini API 단일화 | ✅ 해소 |
| C-05 | NFR-05 롤백 표현 vs 실제 AI-first 동작 | NFR-05 재작성 (AI 추출 선행 + 재import 가능 명시) | ✅ 해소 |
| C-06 | MotionLayout 기술 스택 불일치 | REQUIREMENTS.md §6 MotionLayout 제거 | ✅ 해소 |
| C-07 | Phase 계획 체크박스 미갱신 | REQUIREMENTS.md §8 ✅/[ ] 실구현 현황 반영 | ✅ 해소 |
| C-08 | 설정·권한 요구사항 미문서화 | §3.7 설정 화면(SET-01~03), §3.8 권한(PERM-01~03), NFR-09 추가 | ✅ 해소 |

---

## 개별 충돌 상세

### C-01 — 아키텍처 다이어그램 vs 실제 코드

**발생 문서:** `REQUIREMENTS.md` §2 (구버전)

**충돌 내용:**
- 구버전 다이어그램이 실제 계층 구조(Presentation / ViewModel / UseCase / Data / ML·AI / Background)를 정확히 표현하지 않았다.

**해소:**
- §2 아키텍처 다이어그램을 실제 코드 구조(6계층: Presentation, ViewModel, Use Cases, Data, ML/AI, Background)를 기준으로 재작성.
- 각 계층의 구체 클래스명(TimelineViewModel, ImportKakaoUseCase, FaceClusteringEngine 등) 반영.

---

### C-02 — ContentObserver 구현 여부

**발생 문서:** `REQUIREMENTS.md` 구버전 SYNC-06 vs 실제 코드

**충돌 내용:**
- SYNC-06이 ContentObserver 기반 실시간 갤러리 감지를 현재 구현 사항으로 기술.
- 실제 코드는 WorkManager 6시간 주기 스캔만 구현되어 있으며 ContentObserver 없음.

**해소:**
```
SYNC-06: WorkManager로 6시간 주기 스캔을 스케줄링한다.
         ContentObserver 기반 실시간 감지는 Phase 4 구현 예정이다.
```
- Phase 계획(§8)에도 ContentObserver를 Phase 4 미구현 항목으로 명시.

---

### C-03 — UI-06 이벤트 카드 동작

**발생 문서:** `REQUIREMENTS.md` 구버전 UI-06 vs `SCENARIO_VERIFICATION.md` 2-7

**충돌 내용:**
- 구버전 UI-06: "이벤트 카드를 탭하면 카드가 확장(expansion animation)되며 상세 내용을 인라인으로 표시한다"
- 실제 코드: `TimelineScreen`에서 탭 시 `onEventClick(event.id)` 호출 → `NavController`로 `EventDetailScreen` 이동
- SCENARIO_VERIFICATION.md 2-7도 구버전 텍스트를 참조하여 혼동 가능

**해소:**
```
UI-06: 이벤트 카드를 탭하면 이벤트 상세 화면으로 이동한다.
```
- SCENARIO_VERIFICATION.md 2-7 주석도 "(실제: 상세 화면 이동)"으로 수정.

---

### C-04 — AI 이름 (Claude vs Gemini)

**발생 문서:** `REQUIREMENTS.md` 구버전 KAKO-05

**충돌 내용:**
- 구버전: "AI(Claude/Gemini API)를 사용하여 메시지에서 이벤트를 추출"
- 실제 코드: `GeminiEventClassifier`가 `gemini-2.0-flash-lite` 모델만 사용

**해소:**
```
KAKO-05: AI(Gemini API)를 사용하여 메시지에서 아이 관련 이벤트를 추출한다.
```
- §6 기술 스택에서도 "이벤트 분류 AI" 항목을 "Gemini API" 단일로 명시.

---

### C-05 — NFR-05 롤백 표현

**발생 문서:** `REQUIREMENTS.md` 구버전 NFR-05

**충돌 내용:**
- 구버전: "AI 처리 실패 시 DB 변경 사항을 롤백한다"
- 실제 코드(`ImportKakaoUseCase`): AI 추출을 먼저 실행하고, 성공해야만 DB에 쓴다. 롤백 로직 없음.

**해소:**
```
NFR-05: 카카오톡 import 시 AI 이벤트 추출을 DB 쓰기 전에 먼저 완료한다.
         AI 처리 실패 시 DB에 아무것도 기록되지 않아 재import로 재처리가 가능하다.
```

---

### C-06 — MotionLayout 기술 스택

**발생 문서:** `REQUIREMENTS.md` 구버전 §6

**충돌 내용:**
- 구버전이 MotionLayout을 애니메이션 라이브러리로 포함.
- 실제 코드는 Jetpack Compose 전용이며 MotionLayout(View-based) 없음.

**해소:**
- §6 기술 스택에서 MotionLayout 항목 삭제.

---

### C-07 — Phase 계획 체크박스

**발생 문서:** `REQUIREMENTS.md` 구버전 §8

**충돌 내용:**
- 구버전 Phase 계획 체크박스가 모두 `[ ]`(미완료) 또는 일부만 체크되어 현황 불일치.

**해소:**
- Phase 1~4 각 항목을 실제 구현 완료 여부에 따라 `[x]` / `[ ]`로 정확히 표시.
- Phase 3에서 설정 화면만 `[ ]`(미구현)으로 명시.
- Phase 4에서 설정 화면, 권한 거부 UI, ContentObserver 등 미구현 항목 열거.

---

### C-08 — 설정·권한 요구사항 미문서화

**발생 문서:** `REQUIREMENTS.md` 구버전 (§3.7, §3.8 없음)

**충돌 내용:**
- 시나리오 검증에서 2-13(설정 화면 재초기화), 2-14(데이터 초기화), 2-15(권한 거부)가 ⏳(미구현)으로 식별되었으나, 해당 기능 명세가 요구사항 문서에 존재하지 않음.

**해소:**
- §3.7 설정 화면 신규 추가: SET-01(초기화 재실행), SET-02(데이터 전체 초기화), SET-03(변경 후 타임라인 즉시 갱신)
- §3.8 권한 요구사항 신규 추가: PERM-01(READ_MEDIA_IMAGES), PERM-01b(READ_EXTERNAL_STORAGE), PERM-02(INTERNET), PERM-03(권한 거부 처리)
- NFR-09 추가: 권한 거부 시 동작 정책 명시

---

## 잔존 이슈 (충돌 아님, 기술적 부채)

충돌은 아니지만 향후 구현 시 주의해야 할 미결 사항:

| 항목 | 내용 | 관련 요구사항 |
|---|---|---|
| 설정 화면 | Phase 4 미구현. SET-01~03 구현 필요 | SET-01, SET-02, SET-03 |
| 권한 거부 처리 UI | PERM-03 명세는 있으나 현재 코드에 안내 UI 없음 | PERM-03, NFR-09 |
| ContentObserver | SYNC-06 Phase 4 예정 — 현재 6시간 주기만 동작 | SYNC-06 |
| DB 마이그레이션 기본값 | 기존 설치 앱의 gender가 'MALE'로 설정됨. SET-01 구현 시 재초기화로 해결 | MGT-04 |
| 성별 기본값 안내 | 설치 업데이트 사용자에게 성별 변경 안내 없음 | INIT-09 |

---

## 결론

8개 충돌 모두 해소됨. 모든 문서는 현재 코드베이스 동작과 일치한다.
잔존 이슈 5건은 Phase 4 구현 계획에 포함된 항목이며 문서 충돌이 아닌 미구현 기능에 해당한다.

---

*작성: 2026-04-25*
