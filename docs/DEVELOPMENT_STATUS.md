# A 담당 개발 상태 — 1차 기반과 2차 캡처/OCR

기준일: 2026-10-17. Android 코어와 최초 공통 계약을 제안하는 단계입니다. 개발 환경에서 실기기·에뮬레이터·TalkBack 시험은 미실시입니다. **사용자 제공 API 37 에뮬레이터 결과는 아래 PR별 기록과 [최신 PR #6 정리](PR6_REVIEW_SCOPE.md)에 별도로 반영**합니다. 이전 절의 미실시/미검증은 당시 개발 환경의 기록이며 후속 사용자 실측과 구분합니다. 제조사별 호환성·팝업 감지 성능·사용성 개선을 주장하지 않습니다. 원문 설계와 검증 계획은 보존하고 최신 팀 결정을 적용합니다.

A 2차는 PR #1 병합 후 최신 `main`(`f52be56`)에서 만든 `feature/core-capture-ocr`입니다. [캡처/OCR 구현·자원 수명·시험 절차](CAPTURE_OCR.md)가 현재 캡처 경로의 상세 기준입니다. 기존 공통 계약은 유지하고 캡처는 별도 동의·앱별 허락 기본 꺼짐으로 연결했습니다.

최신 F1 개선은 PR #6 병합 후 `main`의 `db63c83`에서 생성한 **feature/f1-popup-detection**입니다. [F1 판정·고정 시나리오·기대/실제 비교와 Windows API 37 절차](F1_DETECTION_VALIDATION.md)를 추가했습니다. 아래 F1 절은 JVM과 실제 Android 미검증을 구분합니다.

## 구현 범위

| 항목 | 현재 상태 | 담당·남은 작업 |
|---|---|---|
| Kotlin + Compose Android 프로젝트 | A 1차 소스·빌드 설정·Gradle Wrapper 구성 | A: 최종 Gradle 결과 아래 기록 |
| 공통 모델 8종·인터페이스 | 최초 계약과 불변 DTO, Android 노드 객체를 계약에서 제외 | 전원: PR 리뷰로 계약 합의·변경 |
| 접근성 서비스 등록·활성화 안내 | 서비스 선언·메타데이터, 목적·제한 안내, 시스템 설정 이동 | A/C: 실제 설정 화면·TalkBack 탐색 확인 |
| F0-A 분석 보호 | 앱별 허용 기본값 없음, 비밀번호·입력·민감 징후·판정 불가 차단 구조 | A/D: 보호 시나리오 확장·오차단/누락 측정 |
| F0-B 캡처 후 보호 | 2차: 영역 제한·OCR 후 독립 보호 검사, 민감/불명 OCR 미전달 | A/D: 이미지 민감 신호·OCR 누락·native 메모리 수명 실기기 검증 |
| F1 팝업 감지 | bounded 창/의미 있는 overlay 근거와 비후보·불확실 분리, 고정 14개 모델 비교 및 실제 서비스 계측 소스 | 사용자 API 37은 baseline 차단으로 14개 미관측. 추가 차단 진단은 기기 미검증, 일반 앱 precision/recall 미확인 |
| F2 노드·한국어 OCR | 2차: 번들 ML Kit 한국어 모델, tree/OCR 출처·화면 좌표·정렬 결합 | A: 실제 인식 정확도·복잡한 읽기 순서·확대/풀스크린 영역 확장 검증 |
| F3 닫기 후보 | 공통 데이터와 Mock만 제공 | B/A: E1~E4 증거·현재 대상 재탐색; 아이콘만으로 자동 실행 금지 |
| F4 이미지 의미 설명 | TODO, 분석기 인터페이스 경계 | A/B: 로컬 모델·실행 환경·한국어 품질 검증; ML Kit GenAI 배경 실행 가정 금지 |
| F5 영상 광고 감지·안전 처리 | 필수 유지, 현재 TODO | A/B: 영상 신호와 광고 근거 분리·스킵 안전성 검증 |
| F6 영상 내용·줄거리 설명 | 최신 팀 결정으로 범위 제외 | 구현·음성 명령·줄거리 실험 대상에서 제외 |
| F7 분류·F8 안전 판단 | B용 인터페이스와 안전한 Mock | B: 분류와 자동 허가 분리·금지 우선 정책 구현 |
| F9 클릭·결과 확인 | 인터페이스와 실행하지 않는 Mock; 실제 클릭 없음 | A/B: 단일 행동 게이트, 직전 재검사·1회 실행·사후 확인 구현 |
| F10 TalkBack 음성 안내 | **필수**, C용 인터페이스·Mock; 실제 음성 안내 미구현 | C: 오버레이/자체 TTS·다시 듣기·포커스 유지·안내 상태 구분 |
| F11 이벤트 관리 | 화면/사용자 조작 변경에 따른 토큰 무효화의 기반 | A/B: 단일 실행 큐·장부·중복 방지·연쇄 상한 TODO |
| F12 사용자 선택 기억 | **필수**, 저장소 계약·메모리 Mock; Room·영구 기록 없음 | D: 정확한 본문/구조/대상 결박·버전/만료/철회·기록 삭제 |
| F13 음성 명령 | **필수**, C용 계약·Mock; 마이크 사용 없음 | C: 말하기 버튼 누름 후에만 SpeechRecognizer·권한 요청·취소; 위험 행동 금지 |
| CI | Gradle 빌드·단위 시험·Lint 설정 | A/D: 저장소 Actions 실행 결과·실기기 QA 연결 |

## 1차 빌드 기록 (PR #1)

2026-10-16 클라우드에서 아래 명령을 실제 실행했습니다. APK 생성과 JVM 단위 시험·정적 검사는 실기기 검증과 구분합니다.

| 항목 | 결과 |
|---|---|
| 실행 환경 | Linux 클라우드, Temurin JDK 17.0.20.1, Android Platform 35 / Build Tools 35.0.0 |
| 명령 | `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| 실행 결과 | **BUILD SUCCESSFUL** (최종 실행 48초). `assembleDebug`, `testDebugUnitTest`, `lintDebug` 모두 성공. 단위 시험 20개 통과, 실패/오류/건너뜀 0개. Lint `No issues found` (오류·경고 0개) |
| 실패 및 수정 | 최초 실행은 환경의 Java 21이 컴파일러 없는 JRE여서 `JAVA_COMPILER` toolchain 오류 발생. 공식 배포본 SHA256 검증 후 완전한 JDK 17을 설치하고 `JAVA_HOME`을 지정해 재실행. 이후 Lint 경고 10건은 API 31 전용 메타데이터 분리, 최소 SDK보다 낮은 버전 분기 제거, 앱 아이콘·KTX 편집 적용으로 해소. 프로젝트에 기기별 JDK/SDK 경로를 저장하지 않음 |
| 산출물 | `app/build/outputs/apk/debug/app-debug.apk`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html` (빌드 산출물은 Git 제외) |
| 실기기 결과 | 미실시·미검증 |

단위 시험은 보호 정책·후보 휴리스틱·화면 세대 무효화, 합성 화면의 날짜/앱 버전/대상 변경·만료·철회·허락 교체, Mock 미실행·음성/캡처 미지원·원문 출력 방지를 검증했습니다. 병합 매니페스트에서 `INTERNET`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`가 없음을 확인했고 디버그 APK 서명 검증도 통과했습니다. 최초 전체 빌드에서 AndroidX의 `libandroidx.graphics.path.so` 디버그 심볼을 제거하지 않고 패키징한다는 도구 안내가 있었으며, 최종 빌드·Lint 오류는 없습니다. GitHub Actions 실행 여부는 로컬 검증 결과와 별개입니다.

## 2차 빌드·시험 기록

| 항목 | 결과 |
|---|---|
| 환경 | JDK 17.0.20.1, Gradle 8.11.1, Android SDK 35 / Build Tools 35.0.0 |
| 필수 명령 | `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| 최초 2차 결과 | **BUILD SUCCESSFUL in 57s**. 필수 명령 실제 실행, JVM 시험 43개 통과(실패/오류/건너뜀 0), Lint `No issues found` (오류/경고 0) |
| 계측 APK | `:app:assembleDebugAndroidTest` 추가 실행 성공. 합성 OCR/수명 3개 및 명시적 opt-in 서비스 캡처 1개의 계측 테스트 소스·APK 컴파일 확인, 실제 실행 0개 |
| 기기 상태 | 연결된 Android 기기 없음, 실행 가능한 emulator/KVM 없음. 실제 캡처·모델 추론·TalkBack 시험 미실시 |
| 외부 계약 | B/C/D 공통 모델·메서드·enum 시그니처 변경 없음. `PopupContracts.kt`는 설명 주석만 갱신 |

2차 신규 JVM 시험 23개와 기존 20개를 함께 실행했습니다. APK 안의 한국어 `Kore_ctc` 모델/native OCR 라이브러리 포함, 병합 매니페스트의 인터넷/마이크/일반 오버레이 권한 없음, 버전별 메타데이터의 캡처 능력 활성화·제스처 비활성화도 확인했습니다. 캡처/글자의 파일 저장·원문 로그·외부 행동 호출이 자체 production 소스에 없음을 정적으로 점검했습니다. 초기 Lint 경고 2건은 SDK 35 callback IntDef에서 누락된 secure-window 코드를 별도 API 34+ 분기로 처리하고 Bitmap KTX 접근을 적용하여 해소했습니다. 최초 전체 빌드의 ML Kit native 라이브러리 디버그 심볼 미제거 안내는 라이브러리를 그대로 패키징한다는 뜻이며, 빌드 실패는 없었습니다.

## PR #2 검토 수정 기록 (2026-10-17)

- CaptureBudget: 프로세스 누적 30회 제한 제거, 절전 시간을 포함하는 단조 시계의 최근 10분 30회 이동창으로 회복. 최소 간격/분당 제한/동시 실행/동의·앱별 허용 유지. 30개 시작 시각만 저장하며 원문 없음.
- F0-A/F0-B: 실제 민감 징후를 먼저 보호하고 일반 결정 단어는 전체 차단 대신 고정 요약으로 최소화. 공개 snapshot의 원본 노드와 행동 정보를 제거. B의 USER_DECISION 분류·위험 결정 금지 책임과 C/D 연동 요구는 [TEAM_INTEGRATION.md](TEAM_INTEGRATION.md)에 명시.
- 공통 모델/인터페이스 시그니처와 B/C/D 구현은 변경하지 않음. A 내부 결과에 공개용 snapshot을 명시하고 상태 코드 DECISION_SUMMARY_ONLY만 추가.
- 검증: JDK 17에서 `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 실행, **BUILD SUCCESSFUL in 58s**. JVM 시험 53개 통과(실패/오류/건너뜀 0), Lint `No issues found`(오류/경고 0). 이전 43개에서 회복/장시간 사용·민감/일반 결정·공개 데이터 최소화 관련 10개 시험을 추가하고 기존 시험을 갱신. 자체 접근성 production 소스에 로그/파일 쓰기/클릭 API 호출 추가 없음, `git diff --check` 통과.
- 실기기 검증: 미실시. 합성 결제/동의 안내와 개인정보 혼합 화면, 절전/장시간 사용 회복을 포함한 후속 절차는 [CAPTURE_OCR.md](CAPTURE_OCR.md). 실제 캡처·OCR 정확도·TalkBack·native 작업 종료 보장은 여전히 미검증.

## Android API·권한

| 항목 | 지원·현재 설정 |
|---|---|
| 최소 버전 | Android 11/API 30. API 29 이하는 설치 지원하지 않음 |
| compile/target | API 35 |
| 빌드 | AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.1.20 / JVM 대상 17 |
| 접근성 서비스 | 시스템이 바인딩할 때 `BIND_ACCESSIBILITY_SERVICE` 요구. 사용자가 설정에서 직접 켜야 함 |
| 창 내용·여러 창 | `canRetrieveWindowContent` 및 `flagRetrieveInteractiveWindows`. 모든 앱의 노드 가용성을 보장하지 않음 |
| 접근성 도구 선언 | API 31+의 `isAccessibilityTool=true`는 `res/xml-v31`에 선언. API 30용 `res/xml`과 이벤트·능력 설정은 동일하게 유지 |
| 화면 캡처 | 2차: API 30~33 `takeScreenshot`, API 34+ `takeScreenshotOfWindow`. `canTakeScreenshot=true`, 별도 동의·앱별 허용·F0-A·영역·자원 한도 검사 필요 |
| 제스처 | `dispatchGesture` API 24+. 이번 버전 미구현·`canPerformGestures=false`; TalkBack 충돌 실험 필요 |
| 온디바이스 음성 인식 | API 31+에서 지원 여부 검사 필요. API 30/미지원 기기에서는 접근성 버튼 대안 필요 |
| 인터넷·녹음·일반 오버레이 | 이번 버전 `INTERNET`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW` 미요청. C의 향후 누르고 말하기 구현 때만 마이크 동의 검토 |

서비스 해제·앱 전환·분석 중지 때 임시 상태를 폐기해야 하며 화면 글자·설명·화면 지문을 일반 로그·Crash 메타데이터에 출력하지 않습니다. 해시도 화면을 식별할 수 있으므로 임의로 분석 서버나 백업에 저장하지 않습니다. 앱별 허용은 해당 화면이 항상 안전하다는 뜻이 아닙니다.

현재 보호는 주요 민감 앱의 고정 차단 목록과 허용 앱 목록, 입력 속성, 한국어·영어 텍스트 징후에 기반합니다. 모든 금융·건강·메신저 앱, 이미지로만 된 개인정보, 다른 언어의 보호 화면·보안 키패드를 완전히 식별하지 못합니다. 동일 창의 새 컨테이너를 덮개 후보로 보는 휴리스틱은 일반 UI 변경도 오탐할 수 있습니다. 보호 통과·후보 발견은 비민감 화면이나 실제 팝업임을 보증하지 않습니다.

노드 처리 한도는 이벤트 debounce 180ms, 후보 TTL 5초, 창 32개, 두 번의 노드 순회 합산 최대 2,400 방문, 깊이 60, 노드별 텍스트 4,096자, 복사할 전체 텍스트 32,768자, 동기 수집 시간 예산 100ms입니다. 2차 캡처/OCR 한도는 캡처 1초·OCR 3초, 동시 1개, 2초 간격·분당 6회·최근 10분당 30회(시간 경과 시 회복), 소스 800만/ROI 200만 픽셀입니다. **모두 실측 전 임시값**입니다. 노드 보호 실패는 전체 차단하고, 캡처/OCR 실패는 최신 보호를 재검사하여 안전한 트리만 남기거나 전체 차단합니다. 노드 읽기는 Main 스레드에서 동기 수행하고 OCR 추론은 ML Kit의 비동기 Task로 실행하므로 지연·탐색 영향은 실기기에서 측정해야 합니다.

API 지원 범위 참고: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer). API 존재는 기기별 실행 성공을 보장하지 않습니다.

## 다음 실기기 시험

가짜 데이터가 있는 자체 시험 앱으로 먼저 진행합니다. 아래는 **계획**이며 통과 기록이 아닙니다.

| 원문 실험 | A 1차에서 확인할 사항 | 상태 |
|---|---|---|
| B01 / G1 | 서비스 활성화, 다른 앱의 이벤트, 창/노드·라벨/좌표, 별도 허용한 합성 이미지의 실제 캡처·한국어 OCR | 기기 미실시. 2차 계측 테스트·절차 제공 |
| B05 / F0 | 미허용 앱·비밀번호·입력칸·가짜 로그인/결제·시스템 승인·OCR 이미지 민감 신호 차단, 원문 로그 없음 | 기기 미실시. F0-A/F0-B 제어 흐름은 합성 JVM 시험으로 별도 확인 |
| C01 | 대화상자/동일 창 덮개/전면 전환 후보와 일반 페이지·키보드·토스트 오탐 구분 | 미실시. 후보 발견은 광고 분류·자동 허가가 아님 |
| C08 / F11 | 앱/창/본문 변경·스크롤·사용자 조작 때 토큰 무효화, 오래된 결과 재사용 차단 | 미실시. 실제 실행 연계는 B 통합 후 시행 |
| C11 | API 30/34/35, 서로 다른 제조사·크기, TalkBack 켠 상태의 서비스·설정 UI | 미실시 |

C02~C06·C09·C10·C12~C14 및 G2~G5는 관련 구현 뒤 시행합니다. C07은 F5 영상 감지와 안전 처리만 유지하며 F6 영상 내용·장면·줄거리 설명 평가는 최신 결정에 따라 제외합니다. 단위 시험은 실제 접근성 노드·음성·사용자의 이해·OS 권한 동작을 검증하지 않습니다.


## Android 합성 테스트 호스트 런타임 수정 (2026-10-17)

- 보고: API 37에서 test APK Activity 독립 실행 시 Intrinsics ClassNotFoundException. 기존 계측 DEX에는 Activity만, 대상 본 앱 DEX에는 Kotlin 런타임이 있는 것을 확인.
- 수정: 별도 debug/testOnly capture-test-host 앱, 자체 stdlib·이미지 helper, release 변형 비활성화. 운영 앱의 production 소스와 공통 계약은 변경 없음.
- 회귀 검사: 실제 DEX 정의/참조 검사 스크립트와 CI 단계 추가. 계측에 Intrinsics Class.forName과 외부 프로세스의 합성 Dialog 표시 시험 추가(명시적 opt-in). 서비스 OCR 시험의 대상 패키지도 독립 호스트로 변경.
- 최종 검증: JDK 17에서 `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug` 실행, **BUILD SUCCESSFUL in 32s**. JVM 53개 성공 결과 유지(test 태스크는 변경 없는 production/unit 소스의 캐시 재사용), 실패/오류/건너뜀 0. 본 앱·호스트 Lint 오류/경고 0. 호스트 APK DEX의 Intrinsics 등 실제 정의·의존 참조 및 운영/계측 APK의 호스트 부재 검사 통과. 기존 APK는 Intrinsics 누락으로 검사 실패(exit 1) 재현. apkanalyzer로 정의/매니페스트 독립 교차 확인. debugRuntimeClasspath는 Kotlin stdlib 2.1.20/annotations뿐이며 release APK 태스크 없음. 최초 호스트 Lint의 백업 규칙 경고 1건은 dataExtractionRules 추가로 해소.
- 기기 상태: adb 연결 0대, emulator/KVM 없음. 계측 6개는 컴파일만 확인, 실제 실행 0개. API 35/37의 호스트 UI·프로세스 유지·OCR/캡처는 미검증.
- Windows 재검증 및 이전 명령 교체: [ANDROID_TEST_RUNTIME.md](ANDROID_TEST_RUNTIME.md).


## 실제 서비스 연결 대기 수정 (2026-10-17)

- 기준: PR #3 병합 main 28c5a77에서 fix/service-test-connection 생성.
- 사용자 보고: Windows Medium Phone API 37에서 호스트 실행·한국어 모델 계측 3개 성공, 서비스 Bound/Crashed 없음. 기존 서비스 시험은 연결 단계 10초에서 실패했으며 캡처/OCR 실패로 해석하지 않음.
- 원인 조사: AOSP 일반 계측 시작/종료 경로는 대상 앱을 종료할 수 있음. 서비스 앱 자체를 대상으로 한 시험이 process-local Runtime을 직접 대기하는 구조를 제거. 사용자 이미지의 정확한 콜백/로더 누락 원인은 아직 실측으로 확정하지 않음.
- 변경: 합성 호스트 대상 외부 계측, debug-only signature/고정 호출자 보호 probe, 실제 서비스 콜백·초기화 phase·동일 프로세스/로더·메인 heartbeat 진단. Runtime connected 강제 설정 없음. 연결 10초/결과 20초 유지. BEGIN은 UI에서 직접 동의/시작하고 합성 호스트만 허용된 경우에만 관측; 설정 쓰기/자동 허용 없음.
- 개인정보: IPC/실패 결과는 고정 코드·집계·boolean만. 원본 dumpsys는 실패 때 메모리 파싱 후 버림. 노드/이미지/본문/지문/다른 앱 정보 출력·저장 없음. release에 provider/시험 권한 없음.
- 최종 검증: JDK 17에서 `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` 실행, **BUILD SUCCESSFUL in 2m 6s**. JVM 본 앱 59개/호스트 6개, 총 65개 성공(실패/오류/건너뜀 0). 두 모듈 Lint 오류/경고 0. 본 앱 계측 5개/외부 계측 1개 컴파일. DEX에서 외부 드라이버의 본 앱 Runtime/Preferences/model 의존 부재, 실제 host 타깃 매니페스트, release APK의 debug probe 클래스·provider·권한 부재, debug 본 앱/호스트 서명 일치 확인. 패키징 스크립트/`git diff --check` 통과.
- 초기 컴파일에서 UiAutomation.flags getter가 공개 SDK에 없어 실패. 해당 비공개 API 접근을 제거하고 공개 DONT_SUPPRESS 요청과 실제 콜백 확인으로 수정.
- 이 환경에서는 기기 실행 0개. 본 앱 대상 계측 5개·외부 드라이버 1개 컴파일만 검증. [Windows 설치·정확한 새 runner·진단 해석](SERVICE_CAPTURE_TEST.md).

## API 37 캡처·OCR 세션별 진단 (2026-10-17)

- 기준: PR #4 병합 main cf52770에서 `fix/capture-session-diagnostics` 생성.
- 사용자 보고: 서비스 연결 전용 시험은 통과. 전체 시험은 20초 후 `SYSTEM_WINDOW;REGION_UNCERTAIN`으로 실패. 이 클라우드의 실행 결과와 구분함.
- 확인된 결함: provider의 BLOCK은 모든 패키지 이벤트, EVIDENCE는 전역 마지막 상태여서 같은 시도라는 보장이 없었음. 실제 합성 Dialog의 차단 사유나 API 호출 여부를 이전 문자열만으로 확정할 수 없음.
- 구현: 합성 root를 확인한 시도만 기록하는 A 내부 일회성 진단, 새 시도 시 두 코드/단계 동시 초기화, 이전 콜백·취소·END 격리. collector의 시스템/IME/외부 창 보호 분기, 영역 판정의 active/경계/모호함/가림, 확대·버퍼/프레임 경계 불일치를 고정 코드로 분리. 실제 캡처 API 호출부터 OCR/F0-B/발행까지 단계 관측. 성공 Candidate도 같은 화면 버전·창·실제 API 호출·F0-B 통과를 확인하고 결과와 진단을 함께 고정.
- 출력 필터: F0_A/F0_B 등 숫자 0이 포함된 고정 단계 코드도 보존하고 원문/구분자 입력은 UNKNOWN 처리하는 회귀 검사 추가.
- 유지: PrivacyPolicy, 보안/입력/외부 앱 보호, 사용자 동의·앱별 허용·캡처 예산·모든 타임아웃, 합성 호스트 화면, B·C·D 공통 모델/인터페이스. 원문·이미지·좌표·실제 창 ID/지문은 진단/로그/파일로 출력하지 않음. 운영 UI 및 B·C·D 기능 추가 없음.
- 최종 로컬 명령: `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` (JDK 17).
- 결과: **BUILD SUCCESSFUL in 28s**, 196 tasks(15 실행, 181 up-to-date). JVM **78개**(본 앱 71, 호스트 7), 실패/오류/건너뜀 0. 새 회귀 시험 13개 포함. 본 앱/호스트 debug Lint 오류·경고 0. 본 앱 debug/release·독립 호스트·계측 APK 2개 빌드/컴파일 확인. DEX 패키징 검사 통과 및 release 매니페스트에서 debug provider/시험 권한 부재 확인.
- 한계: adb 연결 0대, emulator/KVM 없음. 계측 실행 0개. API 37에서 실제 어떤 창/영역이 차단됐는지는 미확정이며 캡처·OCR 성공 주장 없음. 보호를 추측으로 완화하거나 타임아웃을 늘리지 않음.
- 재시험: [CAPTURE_SESSION_DIAGNOSTICS.md](CAPTURE_SESSION_DIAGNOSTICS.md)의 Windows 설치 4개 APK·연결·전체 시험 명령 및 고정 코드 해석. 합성 앱만 사용하고 실제 개인정보 화면은 제외.

## Dialog OCR 영역과 화면 좌표 출처 수정 (2026-10-17)

- 기준: PR #5 병합 main f72fd0f에서 `fix/dialog-capture-coordinates` 생성.
- 사용자 보고: API 37에서 후보/F0-A 통과, REGION_NO_BOUNDED_DIALOG·OUTSIDE·API=NONE. 실제 캡처 API/OCR/F0-B 이전의 영역 결정 실패로 확인. 이전 screen bounds가 빈 값인지/회전·컨텍스트 크기 차이인지, 실제 창이 이탈했는지는 좌표/역할 자료가 없어 미확정.
- 확인한 코드 결함: 일반 Service 컨텍스트의 maximumWindowMetrics를 다른 앱 접근성 screen 좌표의 전체 화면으로 가정. 이 API는 Context의 UI Configuration 기반이고 전체 논리 화면을 보장하지 않음. 기본 Dialog의 이벤트 클래스명과 노드 클래스도 구분해야 함.
- 수정: 기본 디스플레이를 명시한 WindowContext에서 화면 경계를 읽고 논리 디스플레이 크기/0 원점/읽기 전후 회전을 교차 확인. 불일치는 차단하며 원본 창/노드 경계를 자르거나 이동하지 않음. API 30 생성 경로와 API 31+ 생성 경로 분리. 서비스/화면 구성 변경 때 캐시 초기화. 화면 밖 창은 Dialog 노드로 우회하지 않고 REGION_WINDOW_OUTSIDE로 차단.
- 진단: 예전 서비스 metrics와 새 경계의 관계, 벗어난 방향, root/창 포함 관계, Dialog 클래스 존재 여부, 고정 합성 Dialog/배경 역할을 같은 시도에서 코드로만 제공. 호스트는 제목만 추가하고 이미지/버튼/창 크기 유지. 제목에 OCR 정답 단어를 넣지 않음. 전체 시험 성공 시 FRAME=VERIFIED 및 실제 합성 Dialog 역할도 검사.
- 회귀: JVM 9개 추가. API 35/37에서 실제 실행할 화면 출처 검사와 독립 Dialog 기하 검사 계측 2개 추가. 모달 Dialog로 배경 창이 getWindows에 안 나와도 호스트 자신의 decor 노드 ID와 비교하므로 목록 존재를 강제하지 않음. DEX 검사도 새 계측 클래스 2개의 실제 포함 확인.
- 실행: JDK 17에서 `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease`.
- 결과: 최종 **BUILD SUCCESSFUL in 28s**, 196 tasks(9 실행, 187 up-to-date). JVM **87개**(본 앱 80, 호스트 7), 실패/오류/건너뜀 0. 단위 소스 검증은 이전 동일 명령 실행에서 수행했고 마지막 계측 보강 실행은 변경 없는 unit 결과 재사용. 양쪽 debug Lint 오류·경고 0. 본 앱 debug/release·호스트 및 계측 APK 2개 컴파일 성공. 새 geometry 계측을 포함한 DEX 패키징 검사 통과. release 매니페스트에 debug provider/시험 권한/일반 overlay 권한 없음.
- 보호 유지: 개인정보 보호/시스템·외부 앱·가림/보안 창/확대/버퍼 크기/동의·앱별 허용/빈도·메모리·타임아웃 유지. 전체 화면 fallback이나 Mock OCR 주입 없음. B·C·D 공통 모델/포트 및 기능 변경 없음.
- 미검증: adb 연결 0대, emulator/KVM 없음. 신규 계측 2개 포함 전체 계측 8개는 컴파일/패키징만 확인, 기기 실행 0개. API 35/37의 창 실제 경계·캡처 버퍼/이미지·OCR 성공은 주장하지 않음. 파티션/호환 모드/원점·크기 불일치는 임의 변환하지 않고 지원 제한으로 차단.
- 재시험: [DIALOG_CAPTURE_COORDINATES.md](DIALOG_CAPTURE_COORDINATES.md)에 좌표계 공식 근거, 수정 전후, 고정 진단 해석, API 35/37 Windows 명령을 기록. geometry 계측을 먼저 실행한 후 서비스 재확인 및 사용자 UI 동의를 거쳐 외부 연결/캡처 시험 진행. 합성 화면만 사용.

## PR #6 추가: HardwareBuffer 크기 불일치 조사 (2026-10-17)

- 작업 위치: 기존 `fix/dialog-capture-coordinates`, PR #6. 기준 39c9bed. 새 브랜치/PR 생성 및 main 병합 없음.
- 사용자 보고(API 37): 화면 출처/독립 Dialog geometry 계측 각각 OK, 서비스/후보/F0-A/FRAME/ROLE 통과. 실제 window 캡처는 onSuccess의 BUFFER_GEOMETRY·REGION_UNCERTAIN에서 멈춤. bounded ROI=window이므로 한 축 이상의 실제 버퍼 크기 불일치는 확인되지만 구체적 크기/원점/원인은 미확정. OCR/F0-B 전 실패.
- 조사: 공개 API에 buffer 크기=접근성 창 bounds 계약이나 원점/변환 metadata 없음. AOSP는 대상 ViewRootImpl SurfaceControl의 layer 캡처이며 접근성 사각형 crop을 넘기지 않음. WindowLayout은 surface inset/FLAG_SCALED/resize 크기를 고려. CTS는 일반 Activity decor와 버퍼 크기 비교이며 모든 Dialog/compat/rotation의 screen 매핑 증명이 아님.
- 구현: A 내부 CaptureBufferPolicy에서 실제 buffer width/height/layers/format의 메모리 조건을 Bitmap wrap/복사 전에 우선 검사. exact-size 기존 경로 유지, 모든 불일치/디스플레이 크기/교환 관계는 좌표 대응 미입증으로 계속 차단. 임의 crop/resize/회전/display fallback/Mock OCR 없음.
- 진단: 같은 시도에서 CAPTURE_BUFFER_RECEIVED 및 축별 MATCH/LARGER/SMALLER, extent/display-sized/swapped, mapping, memory, 실제 HardwareBuffer.close 이후 released만 기록. 원문·픽셀 수·좌표·여백·이미지·OCR 본문 저장/출력 없음. 이전 시도/취소/END 뒤 진단 갱신은 폐기. CPU Bitmap/native OCR lease와 모든 timeout/예산/보호는 유지.
- 계측: 새 ServiceBufferGeometryOptInTest는 실제 합성 Dialog callback·해제와 안전 거부만 검사하고 OCR 성공을 요구하는 기존 시험과 분리. 버퍼 시험 OK를 OCR 성공으로 취급하지 않음. 공개 decor.z 유무만 별도 보조 관측하며 inset/transform의 증명으로 쓰지 않음. 호스트 UI/elevation/크기 변경 없음. DEX 검사에도 새 시험 포함 확인.
- 빌드 오류 수정: 처음에는 공개 SDK에 없는 LayoutParams.surfaceInsets 참조로 host 컴파일 실패. 숨겨진 API/반사 사용 없이 공개 decor.z 진단으로 교체. 이어 계측 helper 호출 수신자가 Activity 타입이어서 실패한 부분을 실제 SyntheticPopupActivity 타입으로 명시하여 수정. 최종 실패를 남기지 않음.
- 최종 실행: JDK 17의 `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` → **BUILD SUCCESSFUL in 1m 17s**, 196 tasks(38 실행, 158 up-to-date).
- 결과: JVM **99개**(본 앱 92, 호스트 7), 새 회귀 12개 포함, 실패/오류/건너뜀 0. 양쪽 debug Lint 이슈 0. Debug/Release 및 계측 APK 빌드 성공. 새 버퍼 계측 포함 DEX 패키징 검사 및 release의 debug provider/시험 권한/일반 overlay 권한 부재 확인 통과.
- 한계: adb 기기 0대, emulator/KVM 없음. 계측 총 9개 컴파일/패키징만 확인했고 새 시험의 기기 실행 0개. API 35/37 캡처·OCR 성공 주장 없음. 불일치 buffer-to-screen 변환 지원은 미해결이며 REGION_UNCERTAIN으로 안전하게 거부함. 실제 native stride/driver 할당량은 공개 metadata로 알 수 없어 픽셀 예산을 전체 GPU/RSS 상한으로 표현하지 않음.
- [WINDOW_CAPTURE_BUFFER.md](WINDOW_CAPTURE_BUFFER.md)에 API/AOSP/CTS 근거, 가능한 원인과 한계, 수정 전후, 고정 코드 및 API 35/37 Windows 설치/연결/버퍼/전체 OCR 명령 기록. B·C·D 공통 모델/인터페이스 변경 없음.

## PR #6 추가: 합성 패턴 기반 버퍼 원점 검증 계측

기존 `fix/dialog-capture-coordinates`에서 작업합니다. 사용자 보고 API 37의 버퍼 전용 시험은 양 축 LARGER, DISPLAY_SIZED=NO, SWAPPED=NO, UNPROVEN_WINDOW, 실제 해제를 확인했습니다. 원점/변환/실제 OCR 성공은 아직 확인되지 않았습니다.

- 추가: `synthetic-test-support`의 순수 PatternMapping/좌표 redaction, 호스트의 SyntheticPatternView 및 명시적 pattern Intent 모드, ServiceSyntheticPatternOptInTest, 합성 JVM 회귀 15개.
- 연결: debug provider의 일회성 ARM_PATTERN, SyntheticPatternProbe, build variant별 SyntheticBufferInspection. AndroidScreenshotManager에 실제 window callback의 메모리 검사 이후 관측/해제 알림만 추가했습니다. release는 no-op이며 CaptureBufferPolicy·ROI·OCR 수락 조건과 B·C·D 공통 모델/포트는 변경하지 않았습니다.
- 보호: 기존 signature/caller/동의/단일 호스트 허용, 후보/F0-A/창·노드 경계·토큰 검증을 통과한 실제 callback만 검사. 실제 수치/이미지/원문 저장·출력·외부 전송 없음. 자체 geometry는 안정된 두 pre-draw 후 IPC 메모리로만 전달. 모든 픽셀 참조/배열은 즉시 정리하고 500ms 검사 예산과 END/취소 검사를 추가했습니다.
- 원인 조사: AOSP의 z 기반 비공개 surface inset 및 surface 크기 증가 경로 확인. 새 기본 Dialog vs 첫 show 전 z=0인 새 Dialog를 비교하되 결과는 고정 코드만 반환. 디바이스 원인은 미확정이며 숨겨진 API/추정 크롭/resize/display fallback/Mock OCR은 없습니다.
- 패키징: 새 계측/호스트 패턴/본 앱 debug 검사 클래스의 DEX 존재, 독립 런타임과 driver 클래스 분리, release의 패턴 구현/debug provider 부재 검사 보강.
- 문서: [합성 버퍼 좌표 대응 검증](SYNTHETIC_BUFFER_MAPPING.md)에 변경 이유, 공식 API/AOSP 근거, 고정 코드, API 35/37 Windows 빌드·4개 APK 설치·계측 명령, 남은 제약 기록. CAPTURE_OCR/WINDOW_CAPTURE_BUFFER/CAPTURE_SESSION_DIAGNOSTICS/SERVICE_CAPTURE_TEST에서 연결합니다.

최종 로컬 검증: JDK 17로 `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` 실행, **BUILD SUCCESSFUL (58초, 196 tasks)**. JVM **114개**(본 앱 92 + 호스트 22, 새 회귀 15 포함), 실패/오류/건너뜀 0. 양쪽 debug Lint 이슈 0. 새 계측 포함 Debug/Release·계측 APK 컴파일, DEX 패키징 검사 및 release manifest의 debug provider/시험 권한/일반 overlay 권한 부재 검사 통과.

연결된 adb 기기 0대이며 emulator/KVM이 없어 신규 계측은 **미실행**입니다. API 35/37의 패턴 검증·그림자 가설·실제 캡처/OCR은 미검증으로 유지합니다. 실제 합성 패턴 검증 성공도 일반 앱용 crop 허가나 OCR 성공을 의미하지 않습니다. 큰 버퍼의 REGION_UNCERTAIN 거부는 계속 유지하며, 다음 실제 고정 코드 결과로 원인을 좁혀야 합니다. GitHub Actions 결과는 해당 커밋의 PR #6 Checks에서 별도로 확인합니다. F10/F12/F13 후속 구현과 F6 제외는 유지합니다.

## PR #6 추가: CONTENT_NOT_SYNTHETIC 고정 진단과 두 모드 최종 판정

- 기준: 기존 `fix/dialog-capture-coordinates`의 abfc754, PR #6에서 계속 작업. 사용자 API 37 결과는 DEFAULT의 서비스 연결/ARM/기준점/동일 이동량/decor 포함 이후 콘텐츠 불일치, 실제 버퍼 해제입니다. 기존 assertion 때문에 ZERO는 미실행이었고 원인은 미확정입니다.
- 조사: AOSP Android 15의 Dialog rounded/inset 배경, Material windowClipToOutline → PhoneWindow/decor, View foreground 및 Bitmap.getPixels의 sRGB/non-premultiplied 계약 확인. corner clipping, decor 덮임, 하드웨어 합성, 색상/읽기 변환은 가능한 원인이며 사용자 API 37 내부의 실제 원인이라고 단정하지 않습니다.
- 구현: 순수 PatternMapping에서 전체 콘텐츠 검사 중 위치(CORNER/EDGE/INTERIOR) × 알파·색상(TRANSPARENT/PARTIAL/OPAQUE_NEAR_WHITE/OPAQUE_OTHER) 12개 범주 존재만 bit mask로 집계. LANDMARKS/CONTENT_SCAN 단계와 함께 debug provider를 통해 고정 코드만 전달. 실제 위치/RGB/이미지/원문/픽셀 개수 출력·저장 없음. 성공 허용 조건(알파 255, 채널 차이 1 이하), 500ms 예산과 전체 픽셀 검증 유지. 중단된 스캔을 성공으로 보고하지 않음.
- 관측: 합성 호스트 ARM 시점의 자체 하드웨어/outline/ancestor clipping/foreground 플래그 및 ScreenshotResult/CPU copy의 색 공간 범주만 추가. Dialog 모양, clipping, Paint, 하드웨어 가속 설정은 변경하지 않음.
- 계측: ServiceSyntheticPatternOptInTest의 모드 내부 assertion을 고정 실패 코드 수집으로 변경. PatternModeRunner가 기본 실패/일반 예외/AssertionError 이후에도 각 세션/Activity 정리와 기존 간격을 거쳐 ZERO 관측을 시도. 두 모드 결과와 elevation 비교를 출력한 후 최종 assertion. 한 모드 실패나 정리 실패는 최종 실패 유지. UNKNOWN=UNKNOWN 또는 어느 모드든 미검증이면 INCONCLUSIVE. 명시적 전체 취소는 전파하고 다음 모드를 실행하지 않음.
- 보호: 이번 수정은 app/src/main과 app/src/release를 변경하지 않음. F0-A/F0-B, 동의/허용 앱/가림/민감/시스템·보안 창, native/Bitmap 해제, 실제 메모리/캡처 예산/timeout, 프로덕션 버퍼 불일치 거부 및 B·C·D 공통 계약 유지. 임의 crop/resize/tolerance 확대/Mock OCR 없음.
- 회귀: PatternMappingTest 확장 7개 및 새 PatternModeComparisonTest 11개. 12종 범주, 첫·마지막 불일치 집계, tolerance/알파 경계, 부분 스캔, 양 모드 관측·최종 실패, 예외/취소/불확실 비교 검증. APK 검사에 실제 two-mode runner/comparison DEX 포함 검사 추가.
- 문서: [SYNTHETIC_CONTENT_DIAGNOSTICS.md](SYNTHETIC_CONTENT_DIAGNOSTICS.md)에 확인 사실/가설, 고정 코드 정의와 한계, 모드별 판정 및 Windows API 35/37 설치·재시험 명령. SYNTHETIC_BUFFER_MAPPING/CAPTURE_OCR에서도 연결.

최종 JDK 17 검증: `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` → **BUILD SUCCESSFUL (28초, 196 tasks)**. JVM **132개**(본 앱 92 + 호스트 40, 새 회귀 18 포함), 실패/오류/건너뜀 0. 양쪽 debug Lint 이슈 0. Debug/Release·계측 APK 컴파일, 독립 런타임/driver 클래스 분리/새 runner DEX 패키징 검사, release 패턴 검사 구현·debug provider/시험 권한/overlay 권한 부재 검사 통과.

연결된 adb 기기가 없어 **수정된 계측의 API 35/37 실행·콘텐츠 불일치 실제 원인·전체 매핑/OCR 성공은 미검증**입니다. Windows에서는 DEFAULT가 같은 이유로 실패해도 ZERO 결과와 INCONCLUSIVE 비교를 먼저 보고한 뒤 JUnit 실패를 유지해야 합니다. CI 결과는 해당 추가 커밋의 PR #6 Checks에서 별도로 확인합니다. 새 PR/자동 병합을 수행하지 않습니다. F10/F12/F13 후속 요구와 F6 제외도 유지합니다.

## PR #6 추가: API 37 모서리 알파의 사각형 Dialog 통제 실험

- 작업 기준: 기존 fix/dialog-capture-coordinates의 4b966f2, 기존 PR #6. 사용자 실제 API 37 결과는 DEFAULT/ZERO 모두 기준점 정렬/전체 스캔 완료, 모서리 알파만 불일치, 실제 z 대비 YES/NO, SRGB, 버퍼 해제입니다. 원인은 미확정이며 기존 두 모드의 실패를 유지합니다.
- 새 실험: ServiceSyntheticRenderControlsOptInTest가 별도로 ZERO 참조와 ROUND_CLIPPED/ROUND_UNCLIPPED/SQUARE_CLIPPED/SQUARE_UNCLIPPED를 실제 Android Dialog와 기존 실제 window API로 관측합니다. 고정 8px/0px corner radius와 outline flag만 네 통제 사이에서 달리하고 흰색 단색/alpha255, z=0, 창 크기/패턴은 동일하게 유지합니다. Window/GradientDrawable/ViewOutlineProvider의 공개 API만 사용합니다. 일반 한국어 fixture와 기존 DEFAULT/ZERO의 설정/판정을 변경하지 않습니다.
- 조건 검증: ARM에서 자체 background identity/shape/불투명 단색/radius/clip/provider/z 및 다른 부모 outline을 확인하고 CONTROL_CONFIG/CONTROL_ISSUE 코드만 반환합니다. 네 통제의 자체 상대 layout도 SAME/DIFFERENT/UNKNOWN으로 비교합니다. 실제 측정값은 출력하지 않으며 임시 layout signature의 toString은 redacted이고 finally에서 참조를 해제합니다.
- 진단 분리: PatternMapping의 기준점/포함 검사로 확인한 임시 ANCHOR_DECOR 관계를 전체 콘텐츠 검증과 분리. 콘텐츠 실패면 ANCHOR_DECOR가 알려져도 최종 DECOR_BUFFER는 UNKNOWN, PATTERN/MODE_RESULT는 계속 실패입니다. 새 정보를 production crop/수락 정책으로 전달하지 않습니다.
- 판정 분리: 새 실험의 성공 범위는 **사각형 두 통제의 엄격한 전체 픽셀 검증과 실험 관측 조건**입니다. baseline/라운드의 실패는 별도 그대로 보고하며 전체 기본 매핑 성공으로 승격하지 않습니다. 기존 ServiceSyntheticPatternOptInTest는 두 기본 모드 모두 통과를 요구해 현재 실패가 재현되면 계속 JUnit 실패입니다. 새 비교는 검증된 관측/layout/config 아래의 모서리 알파 실패 제거/실패 없음만 코드로 표시하고 불확실하면 INCONCLUSIVE입니다.
- 안전: 이번 추가 수정에서 app/src/main과 app/src/release, F0-A/F0-B, 동의·허용 앱·가림·민감/시스템/보안 창, buffer/Bitmap 해제, 메모리/캡처 예산/timeout, 불일치 버퍼 거부, B·C·D 계약은 변경하지 않습니다. 색상 허용 오차/전체 픽셀 검증을 유지하고 임의 crop/resize/알파 채움/Mock 버퍼·OCR은 없습니다. 화면 이미지/실제 좌표/RGB/원문을 로그·파일에 저장하지 않습니다.
- 검증 보강: 새 RenderControlMatrixTest 12개와 PatternMappingTest 3개를 추가. 기본 실패와 square 성공 분리, 사각형 실패/불완전 관측/미검증 설정/다른 layout/취소·예외의 보수적 판정, 원래 두 모드 유지, provisional/최종 진단 분리를 합성 데이터로 검사합니다. APK 검사에 실제 새 계측/공유 관측기/matrix 및 host 통제 클래스 DEX 포함을 추가했습니다.
- 문서: [SYNTHETIC_RENDER_CONTROLS.md](SYNTHETIC_RENDER_CONTROLS.md)에 실험 변수·판정 범위·해석 조건, API 근거, Windows API 37 및 API 35의 빌드/4개 APK 설치/기존 실패 회귀/별도 통제 명령 기록. 기존 분당 제한을 지키기 위해 두 시험 사이 시간 회복을 기다리고 예산 초기화로 우회하지 않습니다.

최종 JDK 17 실행: `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease` → **BUILD SUCCESSFUL (26초, 196 tasks)**. JVM **147개**(본 앱 92/호스트 55), 새 회귀 15개 포함, 실패/오류/건너뜀 0. 양쪽 debug Lint 이슈 0. Debug/Release·계측 APK 컴파일 및 DEX/독립 Kotlin 런타임/driver 분리 검사, release의 합성 검사·host/debug provider·시험/overlay 권한 부재 검사 통과.

연결된 adb 기기가 없어 **새 통제의 API 37/35 실행·사각형 콘텐츠 성공·실제 원인 확정·전체 캡처/OCR은 미검증**입니다. 기기 결과를 확인하기 전에는 특정 렌더링 경로를 확정하지 않습니다. 통제 성공이 관측되어도 그것은 통제 fixture 범위의 근거이며 기존 Dialog/일반 앱의 매핑이나 큰 버퍼 지원을 증명하지 않습니다. CI 결과는 해당 커밋의 PR #6 Checks에서 확인합니다. 새 PR/자동 병합은 수행하지 않습니다. F10/F12/F13 후속 요구와 F6 제외를 유지합니다.

## PR #6 정리: API 37 통제 실측과 병합 검토 범위

위 절의 통제 미실시는 당시 개발 환경의 기록입니다. 이후 **사용자가 Windows Android Studio / Medium Phone API 37.0 에뮬레이터에서 실제 실행한 결과**를 제공했습니다. 개발 환경에서 재실행한 것이 아니며 설치 APK의 정확한 커밋 해시와 API 35 결과는 별도로 확인하지 않았습니다.

| `ServiceSyntheticRenderControlsOptInTest` 모드 | 사용자 실측 |
|---|---|
| ZERO_ELEVATION | MAPPING_NOT_VERIFIED |
| ROUND_CLIPPED | MAPPING_NOT_VERIFIED |
| ROUND_UNCLIPPED | PASSED |
| SQUARE_CLIPPED | PASSED |
| SQUARE_UNCLIPPED | PASSED |

SERVICE_CONNECTION_READY, OBSERVATIONS=COMPLETE, SQUARE_CONTROLS=PASSED, **OK (1 test)**. CLIPPED_RADIUS_CONTRAST/ROUND_CLIP_CONTRAST/THEME_TO_SQUARE_CONTRAST는 CORNER_ALPHA_FAILURE_REMOVED, SQUARE_CLIP_CONTRAST는 NO_CONTENT_FAILURE, PRODUCTION_POLICY는 UNCHANGED입니다. 원본 화면·좌표·RGB·OCR 원문은 기록하지 않습니다.

- 확인 범위: 이 합성 fixture의 둥근 배경 × outline clipping 조합이 모서리 알파 불일치에 관여한다는 통제 근거입니다. 기본/둥근 clipping 실패를 성공 처리하지 않으며 elevation 단독 비교의 INCONCLUSIVE도 유지합니다. 기본 테마 전체 원인·Android 내부 Surface 경로를 확정한 것은 아닙니다.
- 별개 미해결: 일반 타사 Dialog의 더 큰 HardwareBuffer에서 원점·변환·안전 ROI는 여전히 미입증입니다. 합성 기준점의 대응을 타사 창에 재사용하지 않으며 **REGION_UNCERTAIN 차단 유지**입니다. 실제 한국어 캡처→OCR→F0-B→결과 발행 전체 경로의 성공을 주장하지 않습니다.
- 병합 검토 범위: 좌표/버퍼의 보수적 검증·안전 중단·개인정보 없는 진단·debug 합성 실험·회귀/패키징 및 문서입니다. 일반 앱 OCR 지원 완료나 전체 계측 suite 성공을 뜻하지 않습니다. 알려진 기본 모드/전체 통합 실패를 남기고 팀 리뷰로 판단할 수 있으며 이번 작업에서 병합하지 않습니다.
- 후속: [PR6_REVIEW_SCOPE.md](PR6_REVIEW_SCOPE.md)에 공개 API로 확인 가능한 소유 창/PixelCopy/기존 exact-size 경로 및 별도 MediaProjection 설계 후보와 한계를 정리했습니다. 크기 차이 기반 추정 crop/resize, display 자동 fallback, 숨겨진 API, 전체 window OCR로 ROI 보호 우회는 허용하지 않습니다. 후속 API 35/37 전체 OCR 및 보호/취소/메모리/TalkBack 기기 시험은 TODO입니다.
- 이번 변경은 **문서 5개만**입니다. 테스트의 기존 독립 판정이 요구사항에 맞아 Kotlin/Gradle/매니페스트/공통 계약을 수정하지 않았습니다. F0-A/F0-B, 동의·허용 앱·가림·민감/보안 창·토큰·예산·메모리·버퍼 해제 및 B·C·D 책임을 유지합니다. F10/F12/F13 필수와 F6 제외도 유지합니다.

정리 커밋의 로컬 재검증(JDK 17):

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
python3 -B tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

**BUILD SUCCESSFUL (13초, 196 tasks: 5 executed / 191 up-to-date)**. 기존 JVM 결과 147개(본 앱 92/호스트 55), 실패/오류/건너뜀 0. 양쪽 debug Lint 이슈 0. Debug/Release·계측 APK 빌드 및 DEX의 독립 Kotlin 런타임/driver 분리·release 합성 probe 부재 검사 통과. 문서 변경으로 대부분 작업은 up-to-date이며 새 기기 실행이나 새 런타임 회귀를 수행한 것은 아닙니다. adb 연결 기기 0대입니다. 정리 커밋의 CI는 기존 PR #6 Checks에서 별도 확인하며, 사용자 계측 결과와 CI/로컬 빌드를 구분합니다.

## A 담당 F1 개선: 고정 시나리오와 판정 정확도 회귀

기준 main `db63c83`(PR #6 병합), 새 `feature/f1-popup-detection` 브랜치입니다. 이번 변경은 기존 PR #6의 추가 커밋이 아니며 새 PR로 검토합니다.

- 감지: 새 창 ID/자식 수만으로 후보를 만들던 규칙을 bounded 창 또는 의미 있는 overlay 근거로 제한했습니다. 일반 첫 화면은 baseline, 일반 전환/이름 없는 구조 변화는 불확실로 분리합니다. 숨김·화면 밖·scrollable 조상 아래 노드 제외, 안정적 outline identity 및 중복/연속 Dialog 판정을 보강했습니다. 현재 휴리스틱의 불확실·미지원 화면을 자동 성공으로 올리지 않습니다.
- 실제 연결: `AndroidSnapshotCollector`가 불변 A 내부 frame에 창 경계·가시성·pane/해제 의미·스크롤 조상 근거를 전달하고 서비스가 F1 판정 뒤 기존 분석 경로를 사용합니다. 공통 `PopupSnapshot/PopupEvent` 및 B·C·D 계약은 유지합니다. Candidate는 광고 판정이나 클릭 허가가 아닙니다. 불확실 신호의 별도 사용자 안내 연동은 TODO입니다.
- 호스트: 기존 독립 testOnly 앱에 실제 Dialog, 같은 Activity의 pane overlay, 새 두 번째 Dialog, 실제 Activity 전환, scroll/layout/Toast/IME, 빈 로그인/금융 입력, 가상 권한 안내 및 해석 불확실 화면을 추가했습니다. 모두 고정 합성 정보이며 실제 입력·권한 요청·버튼 승인·자동 외부 행동은 없습니다.
- 관측: signature/고정 host로 보호한 debug F1 IPC와 외부 계측을 추가했습니다. 수집 동의·단일 호스트 허용·분석 시작·실제 연결 및 **캡처 동의 OFF/캡처 앱 없음**을 요구합니다. 관측은 동일 소유 창으로 결박하고 45초/256개 코드로 제한합니다. 설정/연결/회전/중단·만료·END에서 폐기하며 release는 no-op입니다. 미관측·overflow·상충 관측·정리 실패를 성공으로 숨기지 않습니다.
- 보호: PrivacyPolicy·F0-B·캡처 영역/불일치 버퍼 거부·동의/예산/메모리/해제·기존 OCR/패턴 계측은 변경하지 않았습니다. notification-state 이벤트는 payload를 읽지 않고 진단 코드만 관측하며 tree/OCR을 시작하지 않습니다. 스크롤·사용자 조작의 기존 무효화는 유지합니다. 화면 원문/이미지/실제 좌표/개인정보를 로그·파일에 쓰지 않습니다.

**확인된 모델 결과:** JVM의 14개 합성 DTO/primitive 시나리오에서 탐지 5, 보호 차단 4, 일반 비후보 3, 불확실 유지 1, 미해결 일반 전환 1을 기대 결과와 비교해 통과했습니다. 해당 고정 모델 집합의 미탐/오탐은 0입니다. 실제 Android 이벤트/창/IME/Toast를 실행한 결과가 아니며 보호/불확실을 true negative에 포함하거나 모든 앱의 precision/recall로 환산하지 않습니다.

최종 로컬 실행(JDK 17):

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
python3 -B tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

**BUILD SUCCESSFUL**. F1 코드/관측 수명 검증은 1분 10초(196 tasks: 25 executed / 171 up-to-date), 연속 시험의 첫 Dialog 성공 전제 보강 후 최종 동일 명령은 **31초(196 tasks: 11 executed / 185 up-to-date)**입니다. JVM **175개**(본 앱 115/호스트 60), 실패/오류/건너뜀 0. PR #6 이후 순증 28개: detector 회귀 순증 11 + 고정 matrix 1 + 관측 세션 11 + 정답 집계 5. 양쪽 debug Lint 이슈 0, Kotlin 컴파일 경고 없음. Debug/Release·계측 APK 빌드, 새로운 호스트/외부 driver/provider의 DEX 포함·독립 Kotlin 런타임/driver 분리·release debug/합성 코드 부재 검사 통과. 초기 합성 문자열 Lint 경고는 resources로 옮겨 해소했습니다.

**실제 Android 결과는 미검증:** adb 기기 0대이며 새 API 37/35 F1 계측을 실행하지 않았습니다. 일반 앱 감지율, 빠른 연속 이벤트, full-width/bottom sheet/캔버스, 동일 경로 inline 교체, 가상화·freeform·TalkBack 호환성·지연/메모리는 후속 기기 검증입니다. PR #6의 일반 Dialog 큰 버퍼 및 전체 OCR 통합 실패도 그대로 미해결입니다. [F1_DETECTION_VALIDATION.md](F1_DETECTION_VALIDATION.md)에 고정 CASE별 기대 결과·실제 결과 해석과 Windows 설치/실행 명령을 기록했습니다. CI는 새 PR의 Checks에서 별도로 확인합니다. 자동 병합하지 않습니다. F5 유지/F6 제외/F10·F12·F13 필수도 유지합니다.

## PR #7 후속: API 37 BASELINE_BLOCKED 고정 진단

사용자 실제 보고: Windows API 37에서 SERVICE_CONNECTION_READY 뒤 SEQUENCE_SECOND를 제외한 13개 CASE가 BASELINE_BLOCKED, SEQUENCE_SECOND는 SEQUENCE_PREREQUISITE_MISSING. F1_SUMMARY는 UNOBSERVED=14, 탐지 성공/오탐/미탐 등은 0이며 전체 시험은 실패했습니다. 사용자는 APK·서비스·수집 동의·단일 host 허용·분석 시작 및 캡처 동의/앱 목록 OFF를 확인했습니다. **F1 정확도의 실패/성공으로 평가할 수 없는 baseline 미관측 결과**입니다.

확인된 결함은 baseline의 blocks 검사 직후 예외가 나서 실제 차단 근거를 출력하지 않는 점입니다. 원래 bridge에는 수집 단계/검사 필드 진단도 없었습니다. 실제 Android 차단 원인은 이 보고만으로 확정하지 못했습니다. 알려진 고정 baseline 문구/위젯 클래스 문자열은 JVM 보호 검사에서 차단을 재현하지 않았으나, Android의 입력 속성/메타데이터/창/geometry/수집 상태가 검증된 것은 아닙니다.

변경:

- baseline assertion 이전 F1_EVIDENCE와 F1_COLLECTION 출력, BASELINE/STIMULUS 단계 분리. 기존 BASELINE_BLOCKED/UNOBSERVED 및 연속 시험 전제 실패 판정 유지.
- Collector의 같은 실제 차단에 속한 사유·단계·CaptureDetail·대상/동일 앱 배경 트리·검사 필드·원인 범주·관련 창 종류·frame 상태를 enum tuple로 전달. 독립 시도의 마지막 사유들을 혼합하지 않음. 실제 값/노드/창 ID/좌표/예외 메시지는 출력하지 않음.
- 기존 F0-A 필드/문자 검사를 동일 순서의 helper로 옮겨 진단만 부여. 비밀번호·editable·nonzero inputType·민감/불확실 텍스트·수집 상한은 그대로 차단. 시간/노드/깊이/자식 제한도 원래 상한과 short-circuit 순서 유지. PrivacyPolicy·F0-B·캡처/가림/동의/허용/예산·F1 판정·공통 계약 및 fixture 동작은 변경하지 않음.
- baseline은 실제 collection 결과를 요구하고 raw SCROLL/notification 이벤트만으로 통과하지 않음. 이후 각 실제 자극/결과를 따로 요구하는 기존 검사 유지. 새 진단 문자열은 host의 enum allowlist에 없는 값을 출력하지 않으며 malformed 값도 반사하지 않음.
- JVM 회귀 9개 추가: 기존 필드 보호 결과 동등성, non-editable/nonzero inputType 보호 유지, password/editable 구분, 텍스트 보호/상한·출처, 고정 baseline 문자열 검사, 창별 실패 tuple 결박/초기화, 고정 코드 형식·유출 방지, raw 이벤트만으로 baseline 통과 금지.

최종 JDK 17 실행:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
python3 -B tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

**BUILD SUCCESSFUL (1분 20초, 196 tasks: 35 executed / 161 up-to-date)**. JVM **184개**(본 앱121/호스트63), 실패/오류/건너뜀0. 양쪽 debug Lint 이슈0. Debug/Release·계측 APK 빌드 및 새 baseline formatter/readiness DEX 포함·독립 런타임/driver 분리·release debug/합성 구현 부재 패키징 검사 통과. GitHub Actions는 기존 PR #7의 해당 커밋 Checks에서 확인합니다.

연결 Android 기기 0대로 **새 진단의 실제 API 37 출력, baseline 통과, 14개 실제 F1 자극 및 OCR 성공은 확인하지 않았습니다**. 원인 미확정 상태에서 임의의 보호 예외/fixture 변경/성공 변환/timeout 확대는 하지 않았습니다. [F1 문서의 최신 baseline 조사와 Windows 명령](F1_DETECTION_VALIDATION.md)에 해석·재설치 절차가 있습니다. 제출할 자료는 F1_EVIDENCE/F1_COLLECTION/F1_CASE/F1_SUMMARY 고정 코드이며 전체 Logcat/원문/이미지는 요청하지 않습니다. 새 PR/자동 병합은 하지 않습니다.


## PR #7 후속: SNAPSHOT_PASS 예외 위치와 수집 시도 분리

사용자 API 37 재실행(`f3c13dd` 설치): SERVICE_CONNECTION_READY 통과 뒤 13개 baseline이 `INVALID_GEOMETRY` 집계와 `SNAPSHOT_PASS/COLLECTION_FAILED/TARGET/NONE/FRAMEWORK_FAILURE/VERIFIED` 차단을 보고했습니다. UNMARKED_OVERLAY에 WINDOWS/TARGET_MISSING도 한 번 있으며 최종 UNOBSERVED=14, Tests run 1 / Failures 1입니다. baseline 문제가 남아 실제 F1 감지율은 측정하지 못했습니다.

**코드로 확인:** INVALID_GEOMETRY는 완전 수집 후 Ready를 F1 평가할 때, SNAPSHOT_PASS 예외는 Blocked를 반환할 때 발생합니다. 따라서 서로 다른 수집 시도입니다. 이전 F1_EVIDENCE는 같은 관측 구간의 집합이어서 두 결과가 함께 보였습니다. FRAMEWORK_FAILURE라는 일반 catch 명칭도 Android framework 자체 원인을 입증하지 못합니다. TARGET_MISSING은 활성 root와 별도 window 목록 사이의 불일치 거부이며 전환 가능성만으로 주원인을 확정하지 않습니다.

**이번 변경:**

- Collector의 실제 경계에 OP와 EXCEPTION을 추가했습니다. 보호용 필드/텍스트 getter와 검사, bounds 읽기/모델 변환, action 읽기/불변 set 구성, 노드 DTO, 구조/본문/outline/앱 지문 SHA-256, 최종 digest·token·snapshot·DetectionFrame 구성을 분리합니다. 일반 예외는 COLLECTION_EXCEPTION이며 정해진 예외 타입 범주만 반환합니다. framework/앱 원인으로 추정하지 않습니다. 기존 guard는 원래 보호 사유와 EXCEPTION=NONE입니다.
- 경계 helper는 inline으로 적용하며 실패 시 가장 안쪽 경계의 enum만 보존합니다. 원래 예외를 다시 던져 부분 결과 전체 폐기를 유지합니다. Throwable/메시지/stack·사용자 값은 진단 객체에 보관하지 않습니다.
- debug F1 수집 시작 시 세션 내부 순번을 발급하고 결과를 한 번만 소비합니다. F1_ATTEMPT는 한 시도의 ASSESSED 또는 BLOCKED만 표시합니다. 집계 F1_EVIDENCE에는 SCOPE=AGGREGATE를 명시합니다. 실제 창/노드/화면 버전/시간을 번호로 변환하지 않습니다. RESET 이전 진행 중 결과·다른 세션/소유 창 결과는 다음 구간에 섞이지 않습니다. 원래 45초/256 records에 추가하여 256 attempts 상한도 적용하며 초과는 UNOBSERVED입니다.
- host 출력은 enum별 allowlist와 1~256의 익명 순번만 허용합니다. 공개 common model/BCD 계약은 유지하고 관측 IPC는 debug 전용입니다. release hook은 no-op입니다.
- 새 JVM 12개: 예외 타입 분류/동일 예외 전파·메시지 미보관, 안쪽 위치/필드 보존, 실제 bounds 생성 거부와 getter 구분, 기존 보호 guard 보존, 정상 경계 상태, 서로 다른 수집 결과의 분리, RESET 중 진행 중 시도 폐기, 중복/다른 세션 ticket 거부, 관측 상한, 출력 형식과 유출 방지 및 상세 미관측 표현입니다. bounds 생성 거부 회귀는 진단 분류를 검증하며 실제 API 37 오류의 원인이라는 증거가 아닙니다.

**미확정:** API 37의 실제 예외 위치/유형은 새 F1_ATTEMPT 실측이 필요합니다. 코드의 재현 가능한 원인 오류를 아직 확인하지 못해 보호 판정/fixture/감지 휴리스틱/재시도를 수정하지 않았습니다. F0-A/F0-B, 비밀번호·편집/민감 화면, 시스템/가림/좌표 검사, 캡처 동의·앱별 허용·ROI/불일치 버퍼 거부, 메모리·시간 상한·해제는 그대로입니다. baseline 차단/UNOBSERVED/연속 전제/실제 자극의 성공 조건과 timeout을 유지합니다. 자동 행동은 없습니다. adb 연결 기기 0대로 새 API 37 런타임·baseline 복구·F1 정확도·실제 캡처/OCR 성공을 주장하지 않습니다.

[최신 진단 해석과 Windows 빌드/설치/계측 명령](F1_DETECTION_VALIDATION.md)을 갱신했습니다. 실제 사용자 화면·전체 Logcat·좌표·이미지를 요구하거나 저장하지 않습니다. 기존 feature/f1-popup-detection 및 PR #7에서만 계속하며 새 PR/자동 병합은 하지 않습니다.

로컬 최종 검증(JDK 17):

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
python3 -B tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

**BUILD SUCCESSFUL (1분 16초, 196 tasks: 31 executed / 165 up-to-date)**. JVM **196개**(앱130/호스트66), 실패/오류/건너뜀0, 양쪽 debug Lint 이슈0, Kotlin 컴파일 경고0. Debug/Release·계측 APK 빌드와 실제 DEX의 새 attempt formatter 포함, host 독립 Kotlin 런타임, driver의 앱 내부 클래스 의존 부재, release debug/합성 probe 부재 패키징 검사 통과입니다. CI는 이 커밋의 기존 PR #7 Checks에서 별도 확인합니다. 빌드/패키징 성공은 실제 Android 수집/감지/캡처/OCR 성공을 뜻하지 않습니다.
