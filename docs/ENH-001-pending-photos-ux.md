# ENH-003: 확인 필요한 사진 UI/UX 개선

## 1. 개요
현재 `TimelineScreen` 하단에 표시되는 "확인 필요한 사진" 배너의 위치와 동작 방식을 개선합니다. 기존 방식은 사진을 보지 못한 채로 예/아니오만 선택해야 했으며, 배너가 하단 FAB(+) 버튼과 겹치는 문제가 있었습니다. 이를 상단으로 이동하고, 사진 목록을 직접 보고 선택할 수 있는 다이얼로그(또는 BottomSheet)를 제공하여 사용성을 향상시킵니다.

## 2. 요구사항 (변경 사항)
1. **배너 위치 변경**: `TimelineScreen` 메인 영역 하단에서 상단(카테고리 필터 바로 아래, 타임라인 목록 위)으로 이동합니다.
2. **배너 텍스트 및 UI 변경**:
   - 기존: "확인 필요한 사진 N장" + [아니오] [예] 버튼
   - 변경: "확인 필요한 사진이 N장 있습니다. 확인하시겠습니까?" + 클릭 가능한 배너(버튼 없이 전체 영역 클릭)
3. **사진 확인 다이얼로그 추가**:
   - 배너 클릭 시 팝업(다이얼로그) 노출
   - 확인이 필요한 사진들의 썸네일 리스트(Grid 형태) 표시
   - 각 사진마다 선택/해제(체크박스) 가능하도록 제공
   - [확인] 버튼 클릭 시, 선택된 사진은 승인(수락) 처리하고 선택되지 않은 사진은 거부 처리

## 3. 구현 계획

### 3.1. `TimelineScreen.kt` 수정
- `Scaffold`의 메인 `Column` 내부에서 `PendingPhotosBanner`의 위치를 `TimelineContent` 위로 올립니다.
- `PendingPhotosBanner` 컴포저블을 클릭 가능한 UI로 수정하고 버튼을 제거합니다.
- `var showPendingPhotosDialog by remember { mutableStateOf(false) }` 상태를 추가합니다.
- 새로운 컴포저블 `PendingPhotosDialog`를 작성합니다:
  - `LazyVerticalGrid`를 사용하여 `pendingPhotos` 목록을 썸네일과 함께 렌더링
  - `mutableStateMapOf` 또는 `Set`을 사용하여 사용자가 선택한 사진들의 URI를 관리 (기본값: 모두 선택됨)
  - 하단 [적용/확인] 버튼을 누르면 ViewModel에 최종 선택 결과를 전달

### 3.2. `TimelineViewModel.kt` 수정 (선택적)
- 현재 `confirmPendingPhoto` 함수가 개별 사진에 대해 동작합니다.
- 팝업에서 여러 장을 한 번에 처리하므로, 배치(batch) 처리를 위한 새로운 메서드 `processPendingPhotos(acceptedUris: Set<String>, rejectedUris: Set<String>)`를 추가하거나, UI에서 루프를 돌며 개별 호출합니다.
- 안정성을 위해 ViewModel 내에서 일괄 처리(루프 호출 후 상태 갱신)하는 메서드 추가를 권장합니다.

## 4. 고려사항
- 사진이 많을 경우 다이얼로그 내 스크롤이 필요하므로 `LazyVerticalGrid`를 사용합니다.
- 확인 후 다이얼로그가 닫히며 타임라인에 사진 이벤트가 추가되므로 자연스러운 상태 갱신이 보장되어야 합니다.
