# ENH-009: 타임라인 사진 추가/전체보기 + 텍스트 숨김 UX

TODO 1단계 범위인 #4(사진 추가), #5(사진+ 전체보기), #6(텍스트 숨김/복원)을 하나의 UX 개선 묶음으로 정의.

## 상태 범례
- ✅ 구현 완료
- 🟡 부분 구현
- 🔲 미구현 (계획)

---

## 1. 범위 요약

- ✅ **#4(수정안)** 날짜카드 대신 전역 액션(상단)에서 사진 추가 + takenAt 기준 자동 병합 + 중복 차단 (이슈 #19)
- ✅ **#5** 날짜카드 "사진+" 액션으로 그날 전체 사진 보기(다이얼로그) + 탭 시 전체화면/좌우 슬라이드 (이슈 #19)
- ✅ **#6** 텍스트 이벤트(한 일/한 말/기타) 숨김 기능 + 숨김 목록 관리 (이슈 #19)

목표: 타임라인에서 **사진 편집/탐색 동선**과 **텍스트 이벤트 정리 동선**을 한 화면 흐름에서 완결.

---

## 2. 현재 구조 파악 (코드 기준)

### 2.1 타임라인/상세 다이얼로그

- `TimelineScreen.kt`
  - 날짜카드에서 사진 썸네일 일부를 그리드로 노출
  - 탭 시 전체화면 뷰어(`PhotoFullscreenDialog`) 진입
  - 상세 다이얼로그(`DailyDetailDialog`)에서 카테고리별 이벤트 표시

### 2.2 이벤트/사진 관리 유스케이스

- `ManageEventUseCase.kt`
  - `removePhoto()` 등 삭제/제외 API 존재
- `EventRepository`/`PhotoRecord` 계층
  - 사진 레코드 CRUD 경로 존재

### 2.3 제약/전제

- 사진 삭제/제외 동선은 있으나, 타임라인 날짜 컨텍스트에서 "사진 추가" 진입이 약함
- 텍스트 이벤트는 현재 삭제 중심이며 "숨김(soft delete)" 개념이 없음

---

## 3. #4 날짜카드에서 사진 추가 기능

### 3.1. 🔲 UX 요구사항

- 각 날짜카드에 "사진 추가" 진입점 제공
  - 후보: 카드 우상단 아이콘 버튼 / 카드 하단 액션 행 / long-press 메뉴
- 선택한 사진은 **해당 날짜로 귀속**되어 이벤트에 반영

### 3.2. 🔲 제안 UI 스펙

- 기본 카드 레이아웃 안에 **Add 아이콘 버튼(+) 고정 배치** (텍스트 버튼 대신 아이콘 우선)
- 탭 시 Photo Picker 실행 (`image/*`, 다중 선택 허용)
- 선택 완료 후 토스트/스낵바로 `N장 추가됨` 피드백

### 3.3. 🔲 데이터 처리

- 선택된 URI 메타데이터에서 takenAt 추출
- 날짜카드 날짜 기준으로 이벤트 생성/병합 규칙 적용
- **추가된 사진은 기본값으로 `isExcludedFromModel = true`(학습 제외)로 저장**
- 원본 이미지를 복사/중복 저장하지 않고, **기존과 동일하게 원본 리소스 URI를 참조**
- 기존 얼굴 임베딩 파이프라인 재사용 가능 여부 확인

---

## 4. #5 날짜카드 "사진+"으로 그날 전체 사진 보기

### 4.1. 🔲 UX 요구사항

- 모든 날짜카드에 공통으로 `사진+` 액션 노출
- 탭 시 그 날짜의 사진만 모아 **별도 화면/다이얼로그**로 탐색

### 4.2. 🔲 제안 UI 스펙

- 카드 하단 액션 영역: `사진+` 버튼 고정
- 진입 화면:
  - 상단: 날짜 + 총 개수
  - 본문: LazyVerticalGrid 썸네일
  - 탭: `PhotoFullscreenDialog`로 확대

### 4.3. 🔲 구현 메모

- 현재 날짜카드 "요약 뷰"에서 사진 썸네일을 최대 4장만 보여주는 로직(`photos.take(4)`)과 분리
  - 즉, 질문하신 것처럼 **날짜카드를 펼치기 전/리스트에서 보이는 미리보기 영역**을 의미
- `사진+` 진입 시에는 요약 제한 없이 해당 날짜의 전체 사진을 조회
- full list는 동일 날짜 `EventCategory.PHOTO` 전체를 source of truth로 사용

---

## 5. #6 텍스트 이벤트 숨김 + 숨김 목록 관리

### 5.1. 🔲 UX 요구사항

- 한 일/한 말/기타 이벤트에서 "삭제" 대신 "숨김" 제공
- 숨김 아이템은 설정 메뉴에서 조회/복원
- 숨김 목록에서 롱프레스로 개별 복원

### 5.2. 🔲 데이터 모델 변경

- `EventEntity`에 숨김 플래그 컬럼 추가 (예: `isHidden: Boolean = false`)
- 카테고리별 조회/타임라인 조회 시 `isHidden = 0` 필터 적용
- 숨김 목록 전용 조회 API 추가 (`isHidden = 1`)

### 5.3. 🔲 UX 상세

- 이벤트 카드 메뉴: `숨기기`
- 설정 메뉴: `숨김 아이템`
- 숨김 아이템 화면:
  - 리스트 표시 (날짜/카테고리/요약)
  - 롱프레스 → `복원`

---

## 6. 기술 설계 포인트

### 6.1 DB 마이그레이션

- 예상 버전 업: `vN -> vN+1`
- 마이그레이션:
  - `events` 테이블에 `isHidden INTEGER NOT NULL DEFAULT 0` 추가
- 회귀 포인트:
  - 기존 이벤트 노출 여부
  - 숨김 후/복원 후 타임라인 즉시 반영

### 6.2 상태 관리(ViewModel)

- 타임라인 상태에 다음 액션 추가
  - `addPhotosToDate(date)`
  - `hideEvent(eventId)`
  - `restoreHiddenEvent(eventId)`
- 숨김 목록 상태(loading/empty/list) 분리

### 6.3 네비게이션

- `Timeline -> DayPhotosScreen(또는 Dialog)`
- `Settings -> HiddenItemsScreen`

---

## 7. 파일 변경 계획 (예상)

| 파일 | 상태 | 변경 내용 |
|------|------|-----------|
| `presentation/timeline/TimelineScreen.kt` | 🔲 | 날짜카드 `+사진`/`사진+` 액션, 해당 날짜 전체 사진 진입 UI |
| `presentation/timeline/TimelineViewModel.kt` | 🔲 | 사진 추가/숨김/복원 액션 및 상태 갱신 |
| `domain/usecase/ManageEventUseCase.kt` | 🔲 | 텍스트 이벤트 숨김/복원 API 추가 + 수동 추가 사진 기본 학습제외 처리 |
| `data/local/db/entity/EventEntity.kt` | 🔲 | `isHidden` 컬럼 추가 |
| `data/local/db/dao/EventDao.kt` | 🔲 | 숨김 제외 조회 + 숨김 목록 조회 쿼리 추가 |
| `domain/repository/EventRepository.kt` | 🔲 | hide/restore/queryHidden 인터페이스 추가 |
| `data/repository/EventRepositoryImpl.kt` | 🔲 | hide/restore/queryHidden 구현 |
| `data/local/db/DodoDatabase.kt` | 🔲 | DB 버전 증가 + 마이그레이션 추가 |
| `presentation/settings/*` 또는 `presentation/timeline/*` | 🔲 | 숨김 목록 화면(또는 다이얼로그) 추가 |

---

## 8. 구현 순서 (권장)

1. **#6 데이터 기반 먼저**: `isHidden` 스키마 + DAO/Repository + UseCase
2. **#6 UI 마감**: 숨기기/숨김목록/복원 동선
3. **#4 사진 추가**: 날짜카드 액션 + picker + 저장
4. **#5 사진+**: 날짜별 전체보기 화면/다이얼로그 + 전체화면 연계
5. 통합 QA: 날짜별 카드/상세/전체화면/설정 동선 회귀

---

## 9. 수용 기준 (Acceptance Criteria)

### #4
- 날짜카드에서 2탭 이내로 사진 추가 가능
- 추가 직후 해당 날짜카드 썸네일/개수 반영
- 수동 추가 사진은 기본 `isExcludedFromModel = true`로 저장
- 앱 내부에 사진 파일 복사 없이 원본 URI 참조 방식 유지

### #5
- 모든 날짜카드에서 `사진+` 진입 가능
- 진입 화면에 해당 날짜 사진이 누락 없이 표시

### #6
- 텍스트 이벤트 숨김 시 타임라인에서 즉시 사라짐
- 설정의 숨김 목록에서 롱프레스로 복원 가능
- 복원 시 원래 날짜/카테고리에 다시 노출
