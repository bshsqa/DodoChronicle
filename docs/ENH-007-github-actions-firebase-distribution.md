# ENH-007: GitHub Actions + Firebase App Distribution CI/CD

push → 자동 빌드 → 폰으로 설치 링크 발송.

## 상태 범례
- ✅ 구현 완료 (자동화)
- 🔧 수동 설정 필요 (1회)

---

## 전체 흐름

```
git push (main)
  └── GitHub Actions 트리거
        ├── JDK 17 + Gradle 캐시 세팅
        ├── assembleDebug
        └── Firebase App Distribution 업로드
              └── 등록된 테스터 이메일로 설치 링크 발송
                    └── 폰에서 탭 → 설치
```

---

## 1. 사전 수동 설정 (최초 1회)

### 1.1. 🔧 Firebase 프로젝트 생성 및 Android 앱 등록

1. [Firebase Console](https://console.firebase.google.com) → 새 프로젝트 생성
2. **Android 앱 추가**
   - 패키지명: `com.bshsqa.dodochronicle`
   - `google-services.json` 다운로드 — 현재 사용 안 하므로 보관만 해도 됨
3. 좌측 메뉴 → **App Distribution** → 시작하기

### 1.2. 🔧 테스터 등록

App Distribution → **테스터 및 그룹** → 그룹 `testers` 생성 → 이메일(본인) 추가

### 1.3. 🔧 서비스 계정 JSON 발급

1. Firebase 콘솔 → 프로젝트 설정 → **서비스 계정** 탭
2. "새 비공개 키 생성" → JSON 파일 다운로드
3. 파일 내용 전체를 복사해 보관

### 1.4. 🔧 GitHub Secrets 등록

레포지토리 → Settings → Secrets and variables → Actions → **New repository secret**

| Secret 이름 | 값 |
|-------------|-----|
| `FIREBASE_APP_ID` | Firebase 콘솔 → 앱 설정 → 앱 ID (예: `1:1234567890:android:abcdef`) |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | 1.3에서 다운로드한 JSON 파일의 **전체 내용** |
| `GEMINI_API_KEY` | 기존 local.properties의 `GEMINI_API_KEY` 값 |

---

## 2. 자동화 구성 (구현 완료)

### 2.1. ✅ `.github/workflows/android-ci.yml`

**트리거**: `main` 브랜치에 push 시 실행

**주요 단계**:
1. JDK 17 (temurin) 세팅
2. Gradle 캐시 (`.gradle/caches`, `.gradle/wrapper`)
3. `local.properties`에 `GEMINI_API_KEY` Secret 주입
4. `./gradlew assembleDebug`
5. `wzieba/Firebase-Distribution-Github-Action@v1` 으로 APK 업로드
   - 릴리즈 노트에 브랜치명, 커밋 SHA, 커밋 메시지 포함
6. APK를 GitHub Actions Artifact로도 업로드 (Firebase 없이 직접 다운로드 가능)

---

## 3. 사용 방법

```bash
git push origin main   # 이것만 하면 됨
```

1~2분 내로 폰 이메일에 "새 빌드 사용 가능" 알림 도착
→ 탭 → Firebase App Tester 앱(또는 브라우저) → 설치

---

## 4. 주의사항

- **Debug 빌드**: 서명이 필요 없는 debug APK를 배포. 기기에 "출처를 알 수 없는 앱" 허용 필요 (Firebase App Tester 앱 설치 시 자동 처리).
- **GEMINI_API_KEY**: CI에서 빈 값이면 빌드는 성공하나 카카오 분석 기능은 동작 안 함. Secret 등록 권장.
- **빌드 시간**: Gradle 캐시 없는 첫 빌드 약 5~8분, 이후 캐시 적중 시 2~3분.
- **비용**: GitHub Actions 공개 레포 무료 / 비공개 레포 월 2,000분 무료. Firebase App Distribution 무료.

---

## 5. 파일 변경 목록

| 파일 | 상태 | 설명 |
|------|------|------|
| `.github/workflows/android-ci.yml` | ✅ | GitHub Actions 워크플로우 |
