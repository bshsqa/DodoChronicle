# DodoChronicle

아이의 성장 이벤트와 사진을 시간 흐름으로 기록·탐색하는 Android 앱.
카카오톡 대화와 갤러리 사진을 자동 수집하여 타임라인으로 시각화한다.

---

## 빌드 전 필수 준비 사항

아래 두 가지가 없으면 앱이 정상 동작하지 않는다.

### 1. TFLite 얼굴 인식 모델 파일

얼굴 클러스터링의 핵심 모델이다. **이 파일이 없으면 초기화 시 얼굴 인식이 전혀 되지 않는다.**

**필요한 파일:** `mobile_face_net.tflite`

| 항목 | 값 |
|---|---|
| 파일명 | `mobile_face_net.tflite` |
| 넣을 위치 | `app/src/main/assets/mobile_face_net.tflite` |
| 입력 shape | `[1, 112, 112, 3]` float32 |
| 출력 shape | `[1, 128]` float32 (128차원 임베딩) |
| 예상 파일 크기 | 1~5 MB |

**구하는 방법:**
GitHub에서 `MobileFaceNet tflite` 로 검색하면 공개된 변환 모델이 여러 개 있다.
입출력 shape이 위 스펙과 일치하는 것을 사용해야 한다.

```
app/
└── src/main/assets/
    ├── .gitkeep
    └── mobile_face_net.tflite   ← 여기에 넣기
```

### 2. Gemini API 키

카카오톡 대화에서 이벤트를 자동 추출하는 데 사용된다.
**없으면 AI 분류 기능만 비활성화**되고, 나머지(사진 스캔, 타임라인 등)는 동작한다.

프로젝트 루트에 `local.properties` 파일을 만들고 아래 내용을 추가한다:

```properties
GEMINI_API_KEY=여기에_발급받은_키_입력
```

API 키는 [Google AI Studio](https://aistudio.google.com/app/apikey)에서 무료로 발급받을 수 있다.

> `local.properties`는 `.gitignore`에 포함되어 있어 커밋되지 않는다.

---

## 개발 환경 요구사항

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 이상 권장 |
| JDK | 17 이상 |
| Android SDK | API 35 (compileSdk) |
| 지원 기기 | Android 10 (API 29) 이상 |

---

## 빌드 및 실행

```bash
# 1. 클론
git clone https://github.com/bshsqa/DodoChronicle.git
cd DodoChronicle

# 2. 모델 파일 복사 (위 준비 사항 참고)
cp /다운받은/경로/mobile_face_net.tflite app/src/main/assets/

# 3. API 키 설정
echo "GEMINI_API_KEY=your_api_key" >> local.properties

# 4. 디버그 빌드 & 설치 (기기 연결 or 에뮬레이터 실행 상태에서)
./gradlew installDebug
```

Android Studio를 사용한다면 위 1~3 완료 후 프로젝트를 열고 Run(▶) 버튼으로 실행하면 된다.

---

## 체크리스트

처음 클론 후 빌드 전 확인:

- [ ] `app/src/main/assets/mobile_face_net.tflite` 파일 존재
- [ ] `local.properties`에 `GEMINI_API_KEY=` 입력 (AI 기능 필요 시)
- [ ] Android 기기 연결 또는 에뮬레이터(API 29+) 실행 중

---

## 주요 기능

- **초기화 마법사** — 아이 정보(사진·이름·생년월일·성별) 입력 → 전체 사진 얼굴 클러스터링 → 아이 그룹 선택
- **타임라인** — 이벤트 카드를 시간 축으로 탐색, 핀치줌 지원
- **카카오톡 import** — `.txt` 내보내기 파일 파싱 → Gemini AI로 이벤트 자동 추출
- **자동 사진 동기화** — 백그라운드에서 신규 사진 주기적 스캔 및 분류
