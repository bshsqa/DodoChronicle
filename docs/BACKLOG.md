# DodoChronicle — 백로그 (낮은 우선순위 작업)

> 최종 수정: 2026-04-25

이 문서는 현재 구현하지 않은 낮은 우선순위 항목들을 추적한다.
높은 우선순위 항목(설정 화면, 권한 처리 UI)은 REQUIREMENTS.md §3.7~3.8 및 구현 코드 참조.

---

## 미구현 항목

### BL-01 — ContentObserver 기반 갤러리 실시간 감지
**관련 요구사항:** SYNC-06

**현재 동작:** WorkManager 6시간 주기 스캔만 동작

**목표 동작:** 갤러리에 새 사진이 추가되는 순간 ContentObserver가 감지하여
WorkManager 작업을 즉시 트리거한다.

**구현 포인트:**
- `ContentObserver` 등록: `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
- `Service` 또는 `WorkManager`의 one-time work로 연동
- 배터리 소모 고려 — 감지 후 디바운스 적용 필요

---

### BL-02 — 온보딩 UX 개선 (권한 요청 사전 안내)
**관련 요구사항:** PERM-01, PERM-03

**현재 동작:** 앱 시작 즉시 시스템 권한 다이얼로그 표시 (사전 설명 없음)

**목표 동작:**
1. 앱 최초 실행 시 "왜 사진 권한이 필요한지" 안내 화면 먼저 표시
2. 사용자가 "확인" 후 시스템 권한 다이얼로그 표시
3. Android 정책상 2회 거부 후 "다시 묻지 않기" 상태 처리

**구현 포인트:**
- `shouldShowRequestPermissionRationale()` 활용
- DataStore에 "onboarding_shown" 플래그 저장

---

### BL-03 — 타임라인 성능 최적화
**관련 요구사항:** NFR-02 (60fps)

**현재 동작:** 이벤트 카드를 `Box` + `offset` 으로 절대 좌표 배치
이벤트가 많아질수록 모든 카드를 매 프레임 그림

**목표 동작:** 화면에 보이는 카드만 그리기

**구현 포인트:**
- `LazyColumn` 또는 커스텀 가상화 레이아웃으로 전환
- 현재 `pointerInput` 기반 핀치줌과의 통합 필요
- Canvas `drawBehind` 최적화

---

### BL-04 — 사진 스캔 속도 최적화
**관련 요구사항:** NFR-01

**현재 동작:** 사진을 1장씩 순차 처리 (ML Kit → TFLite 파이프라인)

**목표 동작:** 병렬 처리로 스캔 시간 단축

**구현 포인트:**
- `Dispatchers.IO`에서 `async` 병렬 실행
- TFLite `Interpreter`는 thread-safe하지 않으므로 인스턴스 풀 필요
- 메모리 압박 주의 (고해상도 Bitmap 동시 로딩 수 제한)

---

### BL-05 — 성별 기본값 안내 (기존 설치 사용자)
**관련 요구사항:** INIT-09

**현재 동작:** DB 마이그레이션(v1→v2) 시 기존 레코드의 gender가 'MALE' 기본값으로 설정되지만 사용자에게 알리지 않음

**목표 동작:** 기존 설치 사용자가 처음 업데이트 후 앱을 열면 "성별 설정이 남아로 지정되었습니다. 설정에서 변경하세요." 안내 표시

**구현 포인트:**
- DataStore에 `gender_migration_notified` 플래그
- 설정 화면에서 성별 변경 기능 (현재 미구현 — MGT-04 범위)

---

## 완료 항목 (참고용)

| 항목 | 완료일 | 비고 |
|---|---|---|
| 설정 화면 (SET-01~03) | 2026-04-25 | claude/settings-permission-ui |
| 권한 거부 처리 UI (PERM-03) | 2026-04-25 | claude/settings-permission-ui |

---

*이 문서는 REQUIREMENTS.md Phase 4 미구현 항목과 연동된다.*
