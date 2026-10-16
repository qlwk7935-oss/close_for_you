# 닫아줄게

TalkBack을 사용하는 시각장애인·저시력 Android 사용자의 팝업 접근을 돕는 앱입니다. 팝업 후보를 발견한 뒤 내용을 안내하고 사용자의 선택과 안전 규칙에 따라 처리하는 것이 목표입니다.

현재는 **A 담당 1차 개발**입니다. Android 프로젝트, 공통 계약, 접근성 서비스, 노드 추출·보호 검사·팝업 후보 감지의 기반을 구현합니다. 팝업 분류·자동 클릭, 실제 음성 안내·음성 인식, Room 저장소는 각 담당자의 다음 구현 대상이며 Mock은 실제 기기를 조작하지 않습니다. **실기기·TalkBack 검증은 아직 수행하지 않았습니다.**

## 먼저 읽을 문서

- [개발 규칙](AGENTS.md)
- [통합 설계 v5 안내](docs/닫아줄게_통합설계_v5.md) → [기존 원문](docs/통합설계.md)
- [검증 실험 계획서 안내](docs/닫아줄게_v5_검증실험_계획서.md) → [기존 원문](docs/검증%20실험%20계획.md)
- [팀원별 연동 방법](docs/TEAM_INTEGRATION.md)
- [구현 상태·검증 결과·TODO](docs/DEVELOPMENT_STATUS.md)

최신 팀 결정이 원문의 이전 기능 목록보다 우선합니다. **F6 영상 내용·줄거리 설명은 제외**, F5 영상 광고 감지·안전 처리는 유지합니다. **F10 음성 안내, F12 사용자 선택 기억, F13 음성 명령은 필수**이며 아직 구현하지 않은 부분은 개발 상태 문서에 남깁니다. 음성 명령은 말하기 버튼을 누를 때만 활성화합니다.

## 빌드

Android Studio 또는 JDK 17 이상, Android SDK Platform 35가 필요합니다. 프로젝트는 Kotlin 2.1.20, AGP 8.9.2, Gradle 8.11.1을 사용하고 Java/Kotlin 바이트코드 대상은 17입니다. 최소 Android 11(API 30), compile/target SDK는 35입니다. 실제 빌드 실행 환경·결과는 [개발 상태](docs/DEVELOPMENT_STATUS.md)에 기록합니다.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android SDK 경로는 로컬 `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`으로 지정합니다. `local.properties`, 서명 키, APK와 기기 개인정보는 Git에 추가하지 않습니다.

## 실행·안전 확인

1. 디버그 APK를 설치하고 앱을 실행합니다.
2. 앱의 접근성 사용 목적과 현재 구현 범위를 확인합니다.
3. 앱의 버튼으로 Android 접근성 설정을 열어 `닫아줄게` 서비스를 직접 활성화합니다. 앱이 접근성 서비스를 자동 활성화하지 않습니다.
4. 본인 소유 시험 앱에서만 화면 분석을 허용하고 가짜 데이터로 시험합니다. 초기 허용 앱 목록은 비어 있습니다.
5. 분석을 멈추려면 앱의 분석 설정을 끄거나 시스템 접근성 설정에서 서비스를 해제합니다.

접근성 권한 활성화만으로 모든 앱을 분석하지 않습니다. 비밀번호·입력칸·민감 징후·시스템 승인 화면 또는 판정 불가 상태에서는 분석을 차단합니다. 화면 원문과 개인정보를 일반 로그에 기록하지 않으며 노드 객체는 A 내부에서 불변 데이터로 복사합니다. 이번 버전은 스크린샷·클릭·제스처·뒤로 가기·녹음·네트워크 전송을 실행하지 않습니다.

## 병렬 개발

단일 `app` 모듈 안에서 패키지 소유권을 나누며, 공통 계약은 `core/model`과 `core/contract`에 둡니다. A `feature/core`, B `feature/decision`, C `feature/ui-voice`, D `feature/database-qa`에서 개발하고 Pull Request로 검토합니다. `main`에 직접 기능을 병합하지 않습니다. 최초 계약과 Mock 사용 예시는 [연동 문서](docs/TEAM_INTEGRATION.md)를 참고하세요.
