# ENH-011: ONNX MiniLM 기반 문맥 검색 엔진 교체

## 목표 (Objective)
현재 타임라인 검색의 키워드 검색은 유지하되, 고장난 온디바이스 문맥 검색 엔진을 `paraphrase-multilingual-MiniLM-L12-v2` 계열의 ONNX 기반 임베딩 파이프라인으로 교체하여 한국어 의미 검색이 실제로 동작하도록 복구합니다.

## 배경 (Background)
기존 ENH-010 구현에서는 MediaPipe `TextEmbedder` + `universal_sentence_encoder.tflite` 자산을 사용해 문맥 검색을 구현했습니다. 그러나 실제 디버그 결과:

- 한국어 유사 문장과 무관 문장의 cosine similarity가 모두 `1.0`
- 서로 다른 검색어(`병원`, `식사`, `할머니`)에 대해 결과 상위 목록과 점수 분포가 완전히 동일
- 전체 이벤트가 거의 항상 유사도 임계치 이상으로 판정

즉, 현재 방식은 **검색 로직의 미세 조정으로 해결 가능한 수준이 아니라, 임베딩 모델 또는 런타임 호환성이 깨진 상태**로 판단합니다.

따라서 문맥 검색 복구를 위해 MediaPipe 기반 경로를 폐기하고, ONNX Runtime 기반의 독립적인 텍스트 임베딩 엔진으로 전환합니다.

---

## 주요 요구사항 (Requirements)

### 1. 검색 UX는 유지
- `TimelineScreen`의 검색 다이얼로그 구조는 유지합니다.
- 키워드 검색과 문맥 검색 토글 UX는 유지합니다.
- 검색 결과는 기존처럼 **날짜순 정렬 정책을 그대로 유지**합니다.

### 2. 키워드 검색은 기존 동작 유지
- 띄어쓰기 기반 AND 검색
- 따옴표 기반 정확 문구 검색
- 검색 대상: `content`, `longContent`, `rawExcerpt`
- 키워드 하이라이트 표시 유지

### 3. 문맥 검색 엔진만 교체
- 기존 MediaPipe `TextEmbedder` 경로를 제거합니다.
- `paraphrase-multilingual-MiniLM-L12-v2` 계열 ONNX 모델을 사용합니다.
- 한국어 입력 문장에 대해 실제 분별력 있는 임베딩 벡터를 생성해야 합니다.

### 4. 문맥 검색은 텍스트 전체 문맥 기준
- 이벤트 임베딩 생성 시 사용 텍스트:
  - `content`
  - `longContent`
  - `rawExcerpt`
- 검색어도 동일한 tokenizer/encoder 경로로 임베딩합니다.

### 5. 사진 이벤트는 계속 제외
- `EventCategory.PHOTO`는 문맥 검색 대상에서 제외합니다.

---

## 현재 문제점 정리 (Current Issues)

### 1. 기존 모델 경로는 한국어 문맥 검색에 실패
- self-test 결과 `ko_related=1.0`, `ko_unrelated=1.0`
- query가 달라도 점수 분포와 상위 결과가 동일
- 실질적으로 “거의 모든 이벤트가 비슷하다”는 잘못된 판정을 수행

### 2. 모델 파일 교체만으로는 해결되지 않음
- `mUSE Lite` 계열은 현 시점에서 프로젝트에 바로 넣을 수 있는 MediaPipe 호환 TFLite 자산 확보가 불명확
- 따라서 “MediaPipe 런타임 유지 + 모델 파일만 교체” 전략은 신뢰성이 낮음

### 3. ONNX 경로는 구조 작업이 필요
- 현재 `TextEmbeddingEngine`은 MediaPipe 전제
- ONNX Runtime, tokenizer, pooling, 입력 텐서 생성이 새로 필요

---

## 구현 설계 (Implementation Details)

### 1. 런타임 교체
기존:
- `TextEmbeddingEngine.kt`
- MediaPipe `TextEmbedder`
- `.tflite` asset

변경 후:
- `OnnxTextEmbeddingEngine.kt` 신규 도입 또는 `TextEmbeddingEngine.kt` 재구현
- ONNX Runtime Mobile 사용
- `paraphrase-multilingual-MiniLM-L12-v2` ONNX 자산 사용

### 2. 모델 및 토크나이저 자산
필수 자산 예시:
- `app/src/main/assets/minilm/model_qint8_arm64.onnx`
- `app/src/main/assets/minilm/tokenizer.json`
- `app/src/main/assets/minilm/tokenizer_config.json`
- `app/src/main/assets/minilm/sentencepiece.bpe.model`
- 필요 시 `special_tokens_map.json`, `tokenizer_config.json`, `sentencepiece` 또는 vocab 관련 파일

주의:
- 실제 채택 자산 구성은 확보 가능한 ONNX export 포맷에 따라 달라질 수 있음
- tokenizer 구현 방식에 따라 파일 구성도 달라질 수 있음
- 현재 확보 자산은 `arm64` 대상 양자화 ONNX 모델이므로, 실제 검증 타깃은 physical Android device 기준으로 둡니다.

### 3. 입력 전처리
- 검색어와 이벤트 텍스트 모두 동일한 tokenizer를 사용
- 최대 토큰 길이 제한 필요 (예: 128 또는 256)
- 이벤트 텍스트는 다음 순서로 결합:
  1. `content`
  2. `longContent`
  3. `rawExcerpt`

권장 결합 방식:
```text
content
longContent
rawExcerpt
```

### 4. 출력 벡터 처리
- MiniLM ONNX 출력 텐서에서 sentence embedding을 구성해야 함
- 일반적으로 `last_hidden_state`에 대해 attention mask 기반 mean pooling 수행
- pooling 후 L2 normalization 적용
- DB에는 `List<Float>` JSON으로 저장

### 5. 이벤트 임베딩 저장 정책
- 신규 카카오 이벤트 생성 시 ONNX 엔진으로 즉시 임베딩
- 수동 텍스트 이벤트 추가 시에도 ONNX 엔진으로 임베딩
- 사진 이벤트는 `[]` 유지

### 6. 기존 이벤트 재임베딩 (Backfill)
이번 작업부터는 기존 데이터 무시 전략을 사용하지 않습니다.

필수 요구:
- 기존 `textEmbeddingJson` 값이 비어 있거나 과거 MediaPipe 경로로 생성된 경우, ONNX 기준으로 재생성할 수 있어야 함

후보 방식:
- 앱 실행 시 1회 백그라운드 backfill
- 수동 “문맥 검색 인덱스 재생성” 액션 제공

권장:
- 설정 메뉴에서 수동 재생성 액션 제공
- 필요 시 자동 1회 실행 플래그 추가

### 7. 검색 실행 로직
`TimelineViewModel`에서:

1. 검색어 임베딩 생성
2. 텍스트 이벤트의 `textEmbeddingJson` 로드
3. cosine similarity 계산
4. 임계치 이상 이벤트만 필터링
5. 결과는 기존 날짜순 유지

주의:
- 이벤트별 검색 시점 재임베딩은 금지
- 검색 시점에는 저장된 벡터만 사용해야 성능이 안정적임

### 8. 진단 로깅
디버그 빌드에서 다음 로그를 유지:

- self-test:
  - 유사한 한국어 문장 similarity
  - 무관한 한국어 문장 similarity
  - 한국어/영어 교차 similarity
- 실제 검색:
  - query
  - 전체 후보 수
  - min/max similarity
  - top 5 결과

정상 기준 예시:
- `ko_related > ko_unrelated`
- 서로 다른 query에서 top 결과가 의미 있게 달라짐

---

## 의존성 변경 (Dependencies)

추가 예정:
- ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android`)
- DJL HuggingFace Tokenizers (`ai.djl.huggingface:tokenizers`)
- Android tokenizer native runtime (`ai.djl.android:tokenizer-native`)

주의:
- 실제 Maven Central 배포 상태 기준으로 DJL 계열 버전은 동일 major/minor line으로 맞춰 적용합니다.

제거 또는 축소 검토:
- MediaPipe Text Tasks (`com.google.mediapipe:tasks-text`)

단, 다른 기능에서 MediaPipe Text Tasks를 사용하지 않는지 최종 확인 후 제거합니다.

---

## 작업 범위 (Scope)

포함:
- ONNX Runtime 기반 텍스트 임베딩 엔진 구현
- tokenizer 로딩 및 입력 텐서 구성
- MiniLM sentence embedding 추출
- 이벤트 임베딩 생성 경로 교체
- 검색 self-test 및 로그 유지
- 기존 문맥 검색 복구

제외:
- 서버 기반 검색
- 별도 검색 결과 페이지
- 벡터 인덱스(HNSW, FAISS 등) 도입
- 검색 결과 relevance 순 정렬

---

## 승인 후 작업 순서 (Planned Steps)

1. ONNX Runtime 의존성 추가
2. 현재 `TextEmbeddingEngine` 제거 또는 재구현
3. MiniLM tokenizer + inference 래퍼 구현
4. self-test 재구성
5. 카카오 import / 재시도 / 수동 이벤트 임베딩 경로 교체
6. 기존 이벤트 임베딩 재생성 전략 구현
7. 문맥 검색 점수 로그로 검증

---

## 수용 기준 (Acceptance Criteria)

### 기능 기준
- `병원`, `식사`, `할머니` 같은 서로 다른 query에 대해 문맥 검색 결과가 달라진다
- 사진 이벤트는 검색되지 않는다
- 키워드 검색은 기존처럼 동작한다
- 검색 결과 정렬은 날짜순을 유지한다

### 품질 기준
- self-test에서:
  - `ko_related > ko_unrelated`
  - 무관 문장 similarity가 비정상적으로 `1.0`에 수렴하지 않는다
- 실제 검색 로그에서:
  - 서로 다른 query의 top 결과와 score range가 구분된다

### 기술 기준
- 문맥 검색 시 이벤트별 실시간 재임베딩이 발생하지 않는다
- 저장된 `textEmbeddingJson`만으로 검색 가능하다

---

## 리스크 (Risks)

- ONNX export 포맷에 따라 tokenizer 구현 난이도가 달라질 수 있음
- sentence embedding pooling 방식이 모델 export와 맞지 않으면 품질 저하 가능
- 기존 이벤트 재임베딩에 시간이 걸릴 수 있음
- Android APK 용량 증가 가능

---

## User Review Required

> [!IMPORTANT]
> 이번 작업은 “모델 파일만 교체”가 아니라 검색 엔진 런타임 자체를 교체하는 작업입니다. 승인 후에는 ONNX Runtime, tokenizer, 임베딩 저장 정책, 기존 이벤트 재임베딩 전략까지 포함한 구조 변경이 진행됩니다.
