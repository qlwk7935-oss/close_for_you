# A 담당 개발 상태 — 1차 기반과 2차 캡처/OCR

기준일: 2026-10-16. Android 코어와 최초 공통 계약을 제안하는 단계입니다. **실기기·에뮬레이터·TalkBack 시험은 미실시**이며 제조사별 호환성·팝업 감지 성능·사용성 개선을 주장하지 않습니다. 원문 설계와 검증 계획은 보존하고 최신 팀 결정을 적용합니다.

A 2차는 PR #1 병합 후 최신 `main`(`f52be56`)에서 만든 `feature/core-capture-ocr`입니다. [캡처/OCR 구현·자원 수명·시험 절차](CAPTURE_OCR.md)가 현재 캡처 경로의 상세 기준입니다. 기존 공통 계약은 유지하고 캡처는 별도 동의·앱별 허락 기본 꺼짐으로 연결했습니다.

## 구현 범위

| 항목 | 현재 상태 | 담당·남은 작업 |
|---|---|---|
| Kotlin + Compose Android 프로젝트 | A 1차 소스·빌드 설정·Gradle Wrapper 구성 | A: 최종 Gradle 결과 아래 기록 |
| 공통 모델 8종·인터페이스 | 최초 계약과 불변 DTO, Android 노드 객체를 계약에서 제외 | 전원: PR 리뷰로 계약 합의·변경 |
| 접근성 서비스 등록·활성화 안내 | 서비스 선언·메타데이터, 목적·제한 안내, 시스템 설정 이동 | A/C: 실제 설정 화면·TalkBack 탐색 확인 |
| F0-A 분석 보호 | 앱별 허용 기본값 없음, 비밀번호·입력·민감 징후·판정 불가 차단 구조 | A/D: 보호 시나리오 확장·오차단/누락 측정 |
| F0-B 캡처 후 보호 | 2차: 영역 제한·OCR 후 독립 보호 검사, 민감/불명 OCR 미전달 | A/D: 이미지 민감 신호·OCR 누락·native 메모리 수명 실기기 검증 |
| F1 팝업 감지 | 새 창·동일 창 덮개·전면 전환의 후보 감지 기반 | A/D: 실제 팝업과 일반 전환 구분 실험·캔버스/WebView 한계 확인 |
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
| 최종 결과 | **BUILD SUCCESSFUL in 57s**. 필수 명령 실제 실행, JVM 시험 43개 통과(실패/오류/건너뜀 0), Lint `No issues found` (오류/경고 0) |
| 계측 APK | `:app:assembleDebugAndroidTest` 추가 실행 성공. 합성 OCR/수명 3개 및 명시적 opt-in 서비스 캡처 1개의 계측 테스트 소스·APK 컴파일 확인, 실제 실행 0개 |
| 기기 상태 | 연결된 Android 기기 없음, 실행 가능한 emulator/KVM 없음. 실제 캡처·모델 추론·TalkBack 시험 미실시 |
| 외부 계약 | B/C/D 공통 모델·메서드·enum 시그니처 변경 없음. `PopupContracts.kt`는 설명 주석만 갱신 |

2차 신규 JVM 시험 23개와 기존 20개를 함께 실행했습니다. APK 안의 한국어 `Kore_ctc` 모델/native OCR 라이브러리 포함, 병합 매니페스트의 인터넷/마이크/일반 오버레이 권한 없음, 버전별 메타데이터의 캡처 능력 활성화·제스처 비활성화도 확인했습니다. 캡처/글자의 파일 저장·원문 로그·외부 행동 호출이 자체 production 소스에 없음을 정적으로 점검했습니다. 초기 Lint 경고 2건은 SDK 35 callback IntDef에서 누락된 secure-window 코드를 별도 API 34+ 분기로 처리하고 Bitmap KTX 접근을 적용하여 해소했습니다. 최초 전체 빌드의 ML Kit native 라이브러리 디버그 심볼 미제거 안내는 라이브러리를 그대로 패키징한다는 뜻이며, 빌드 실패는 없었습니다.

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

노드 처리 한도는 이벤트 debounce 180ms, 후보 TTL 5초, 창 32개, 두 번의 노드 순회 합산 최대 2,400 방문, 깊이 60, 노드별 텍스트 4,096자, 복사할 전체 텍스트 32,768자, 동기 수집 시간 예산 100ms입니다. 2차 캡처/OCR 한도는 캡처 1초·OCR 3초, 동시 1개, 2초 간격·분당 6회·프로세스당 30회, 소스 800만/ROI 200만 픽셀입니다. **모두 실측 전 임시값**입니다. 노드 보호 실패는 전체 차단하고, 캡처/OCR 실패는 최신 보호를 재검사하여 안전한 트리만 남기거나 전체 차단합니다. 노드 읽기는 Main 스레드에서 동기 수행하고 OCR 추론은 ML Kit의 비동기 Task로 실행하므로 지연·탐색 영향은 실기기에서 측정해야 합니다.

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
