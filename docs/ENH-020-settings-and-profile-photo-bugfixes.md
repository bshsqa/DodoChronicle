# ENH-020: 설정/옵션 버그 수정

## 목표
설정 및 옵션 화면에서 확인된 독립적인 버그를 배포 전 안정화합니다.

관련 이슈:

- #36 Gemini API 키 입력 후 모델 목록 로딩 실패
- #38 초기 선택 대표 사진이 옵션 상단에 표시되지 않는 문제

이 ENH는 사진 분류 파이프라인을 바꾸지 않습니다. 사진 날짜 결정, 초기 분류 대상 선정, 백그라운드 초기 분류, 대량 그룹 선택 UX는 ENH-021에서 다룹니다.

---

## 범위

포함:

- Gemini 모델 목록 로딩 실패 원인 확인 및 수정
- 모델 목록 로딩 실패 시 사용자 메시지와 logcat 로그 개선
- 저장된 Gemini API 키와 새 입력 키의 사용 흐름 정리
- 옵션 다이얼로그 상단 대표 사진 표시 문제 수정
- 대표 사진 변경 시 DB와 UI state 갱신
- 대표 사진 URI 권한 안정화 또는 fallback 제공

제외:

- 사진 날짜 결정 로직 개선 (#37, ENH-021)
- 초기 사진 분류 UX 재설계 (#39, ENH-021)
- 대량 사진 그룹 선택 UX 개선 (#40, ENH-021)
- 기존 DB에 저장된 사진 날짜 보정

---

## 1. Gemini 모델 목록 로딩 수정

### 현재 문제
옵션에서 Gemini API 키를 입력한 뒤 모델 목록을 불러오면 목록이 표시되지 않습니다.

확인할 가능성:

- debug fallback 제거 이후 기존 키 유지 흐름이 깨졌을 수 있음
- 키 입력 후 모델 fetch는 성공했지만 `TimelineUiState.geminiModelOptions` 반영이 안 되었을 수 있음
- API 실패 메시지가 snackbar에 표시되지 않아 실패 원인을 알기 어려울 수 있음
- Gemini models API 응답 파싱 또는 `generateContent` 필터가 너무 엄격할 수 있음
- 네트워크/권한/키 오류를 모두 같은 실패로 처리하고 있을 수 있음

### 기대 동작

#### 새 키 입력
1. 사용자가 API 키를 입력합니다.
2. `모델 목록 불러오기`를 누릅니다.
3. 입력한 키로 Gemini models API를 호출합니다.
4. 사용 가능한 `generateContent` 모델을 dropdown에 표시합니다.
5. 첫 번째 모델을 기본 선택하거나, 기존 선택 모델이 목록에 있으면 유지합니다.

#### 기존 키 사용
1. 이미 API 키가 저장되어 있습니다.
2. 사용자가 API 키 필드를 비워둡니다.
3. `모델 목록 불러오기`를 누릅니다.
4. 저장된 키로 모델 목록을 다시 불러옵니다.

#### 저장
1. 사용자가 모델을 선택합니다.
2. `저장`을 누릅니다.
3. API 키 입력값이 있으면 새 키를 저장합니다.
4. API 키 입력값이 비어 있으면 기존 저장 키를 유지합니다.
5. 선택된 모델 id를 저장합니다.

### 로그

권장 태그:

```text
DodoGeminiSettings
```

권장 로그:

```text
Model fetch started. hasInputKey=true hasSavedKey=false
Model fetch response. code=200 contentLength=...
Model fetch parsed. total=12 usable=8
Model fetch failed. code=403 bodyPrefix=...
Model save requested. hasInputKey=false hasExistingKey=true model=models/...
```

API 키 원문은 로그에 남기지 않습니다.

### 오류 메시지

사용자 메시지는 가능한 한 원인별로 나눕니다.

- 키가 비어 있음: `Gemini API 키를 먼저 입력해주세요.`
- 400/401/403: `Gemini API 키를 확인해주세요.`
- 네트워크 실패: `네트워크 연결을 확인해주세요.`
- 응답 파싱 실패: `모델 목록 응답을 읽지 못했습니다.`
- 사용 가능 모델 없음: `사용 가능한 Gemini 생성 모델이 없습니다.`

### 수용 기준

- 새 키 입력 후 모델 목록 dropdown이 채워집니다.
- 저장된 키 상태에서 빈 입력으로도 모델 목록 갱신이 가능합니다.
- 모델 선택 후 저장하면 설정 메뉴에 선택 모델이 표시됩니다.
- 실패 시 사용자에게 원인성 메시지가 표시됩니다.
- 실패 원인을 logcat에서 확인할 수 있습니다.
- API 키 원문은 로그에 노출되지 않습니다.

---

## 2. 옵션 상단 대표 사진 표시 수정

### 현재 문제
초기 세팅에서 선택한 사진이 옵션 다이얼로그 상단 대표 사진 영역에 표시되지 않습니다.

가능성:

- `Child.referencePhotoUri`가 초기 저장 시 비어 있음
- `TimelineUiState.childReferencePhotoUri`가 초기 로드에서 채워지지 않음
- 대표 사진 선택에 `GetContent`를 사용해 앱 재시작 후 URI 접근 권한이 사라짐
- `AsyncImage` 로딩 실패 fallback이 없어 빈 영역처럼 보임
- 대표 사진 변경 후 DB는 갱신됐지만 UI state가 즉시 갱신되지 않음

### 기대 동작

- 초기 세팅에서 선택한 사진을 `Child.referencePhotoUri`에 저장합니다.
- 타임라인 진입 시 `childReferencePhotoUri` state에 복원합니다.
- 옵션 다이얼로그 상단에 원형 이미지로 표시합니다.
- 이미지를 터치하면 대표 사진을 변경할 수 있습니다.
- 변경 후 DB와 UI state가 즉시 갱신됩니다.
- 이미지 로딩 실패 시 fallback 아이콘을 보여줍니다.

### URI 권한 정책

대표 사진은 앱 재시작 후에도 보여야 하므로 URI 권한을 안정화해야 합니다.

권장:

- 대표 사진 선택은 `ActivityResultContracts.OpenDocument()` 사용
- 선택 후 `takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` 호출
- 기존 초기 세팅의 대표 사진 선택도 가능하면 동일하게 `OpenDocument`로 전환
- 이미 저장된 `GetContent` URI가 접근 실패하면 fallback 표시

초기 구현에서 앱 내부 파일 복사까지는 하지 않습니다. 다만 장기적으로는 대표 사진 썸네일을 앱 내부 캐시에 복사하는 방식도 고려할 수 있습니다.

### UI 요구사항

옵션 다이얼로그 상단:

- 원형 대표 사진
- 아이 이름
- `사진을 눌러 대표 사진 변경` 안내
- 사진이 없거나 실패하면 `AddAPhoto` fallback

대표 사진 영역은 설정 메뉴의 다른 항목보다 위에 위치합니다.

### 수용 기준

- 초기 선택 사진이 옵션 상단에 표시됩니다.
- 앱 재시작 후에도 대표 사진이 유지됩니다.
- 옵션에서 대표 사진 변경 후 즉시 반영됩니다.
- 이미지 로딩 실패 시 빈 영역이 아니라 fallback UI가 표시됩니다.
- URI 권한이 가능한 한 유지됩니다.

---

## 구현 순서

1. Gemini 모델 목록 로딩 실패 로그 추가
2. 저장된 키/입력 키 선택 흐름 점검
3. 모델 목록 fetch 및 save 로직 수정
4. Gemini 설정 UI 상태 갱신 검증
5. 대표 사진 선택 launcher를 `OpenDocument` 기반으로 조정
6. `takePersistableUriPermission` 적용
7. `Child.referencePhotoUri` 저장/복원 경로 점검
8. 옵션 상단 대표 사진 로딩 실패 fallback 적용

---

## 테스트 시나리오

### Gemini

- 새 API 키를 입력하고 모델 목록을 불러옵니다.
- 모델을 선택하고 저장합니다.
- 앱을 재시작한 뒤 키 입력 없이 모델 목록을 다시 불러옵니다.
- 잘못된 키를 입력했을 때 오류 메시지가 표시되는지 확인합니다.
- logcat에서 `DodoGeminiSettings` 로그를 확인합니다.

### 대표 사진

- 초기 세팅에서 선택한 사진이 옵션 상단에 표시되는지 확인합니다.
- 앱 재시작 후에도 표시되는지 확인합니다.
- 옵션에서 대표 사진을 변경하고 즉시 반영되는지 확인합니다.
- 접근 불가 URI 또는 삭제된 사진에서 fallback이 표시되는지 확인합니다.
