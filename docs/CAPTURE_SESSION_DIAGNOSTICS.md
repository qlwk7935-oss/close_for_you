# API 37 캡처·OCR 실패: 세션별 진단과 재시험

기준: PR #4 병합 `main` cf52770 → `fix/capture-session-diagnostics`. 이 문서는 기기 성공 보고서가 아니라 코드 조사와 재시험 절차입니다.

## 확인한 사실과 아직 모르는 원인

사용자 보고(Windows Medium Phone API 37.0): 독립 합성 팝업 표시, BundledKoreanOcrTest 3개, SERVICE_CONNECTION_READY 및 연결 전용 시험 성공. 전체 시험은 20초 안에 OCR_MATCH 없이 `BLOCK=SYSTEM_WINDOW;EVIDENCE=REGION_UNCERTAIN`로 실패했습니다.

코드에서 확인한 진단 결함:

1. 이전 provider는 **모든 패키지**의 PopupEvent.Blocked를 마지막 BLOCK으로 저장했습니다. 반면 EVIDENCE는 세션 이전 값까지 남는 전역 AccessibilityRuntime.status에서 가져왔습니다. 두 값의 토큰/화면/시도가 같다는 검사가 없었습니다. 따라서 주어진 문자열은 합성 Dialog의 SYSTEM_WINDOW 차단이나 캡처 API 호출 실패를 증명하지 않습니다. 실제로 다른 시도의 값이 섞였는지는 이전 문자열만으로 복원할 수 없습니다.
2. REGION_UNCERTAIN은 영역 결정뿐 아니라 확대 상태, 캡처 전 재검증, HardwareBuffer 크기/범위 불일치, 반환 프레임 경계 불일치에서도 나옵니다. 이전 코드만으로 이들을 구별할 수 없었습니다.
3. SYSTEM_WINDOW도 이벤트 패키지의 빠른 제외(본 앱/android/IME), collector의 비앱 대상 창, 활성/포커스 비앱 창, 격리 검사 중 외부 앱 창/다중 디스플레이 등 여러 경로가 있습니다.

**미확정:** API 37에서 실제 대상 root가 Dialog였는지, active 상태·창 레이어·경계가 어땠는지, 캡처 버퍼가 창 크기와 달랐는지. 이 환경은 adb 연결 0대, emulator 바이너리 및 KVM 없음입니다. API 37 호환성 결함을 입증하지 못했으므로 Dialog 크기나 보호 조건을 추측으로 변경하지 않았습니다. 전체 캡처·OCR 성공도 주장하지 않습니다.

## 수정한 관측 경로

서비스의 실제 경로는 이벤트 → 토큰 무효화/빠른 패키지 보호 → 180ms debounce → collector(전체 트리 F0-A 포함) → 팝업 후보 → 재검사 F0-A → 캡처 허용/예산 → 영역 → 캡처 API → 캡처 후 재검사 → OCR → 최신 화면 재검사 → F0-B → Runtime.publish입니다. 후보 검출 전에도 민감 노드 보호가 수행됩니다.

- debug provider의 BEGIN~END/30초 만료 사이에만 A 내부 CaptureDiagnosticSession을 설치합니다. 설정은 읽기만 하며 기존 signature 권한·고정 호스트 호출자·명시적 opt-in·합성 앱 단독 허용 조건을 유지합니다. release에는 provider 진입점이 없습니다.
- collector가 **실제 root 패키지를 합성 호스트로 확인한 시도만** 진단을 시작합니다. 호스트의 배경 Activity도 같은 패키지이므로 이를 Dialog라고 단정하지 않습니다. TARGET/GEOMETRY/영역 판정 코드를 함께 봅니다. 이벤트 창과 root 창의 SAME/DIFFERENT/UNKNOWN 관계도 표시합니다.
- 각 시도는 독립 순번을 부여하고 BLOCK/EVIDENCE를 함께 초기화합니다. 재검사는 같은 시도를 사용하고, 새 시도 이후 이전 비동기 콜백은 덮어쓸 수 없습니다. 다른 패키지의 빠른 제외 이벤트는 IGNORED 횟수만 올리며 호스트의 BLOCK으로 사용하지 않습니다. 이 횟수는 전체 OS 이벤트 수나 창 유형 판정이 아닙니다.
- RESULT는 전역 status를 읽지 않습니다. 같은 시도의 불변 진단 한 개만 반환합니다. 성공은 같은 screenVersion/windowId의 실제 Candidate, 현재 extract 성공, 실제 API 호출 표시, F0-B 통과, 실제 OCR 출처 텍스트에 `점검` 포함을 모두 요구합니다. OCR 본문은 IPC로 전달하지 않습니다. 성공 결과와 해당 진단은 함께 고정하여 이후 다른 시도와 섞지 않습니다.
- 원문/이미지/좌표/패키지 목록/노드/화면 지문/PID/실제 창 ID는 진단에 담지 않습니다. 내부 상관관계 확인에 필요한 화면 버전/창 ID는 메모리에서만 사용합니다. 1개 최신 시도의 enum/boolean 성격 코드와 제한된 횟수만 보유하며 END/만료에 폐기합니다. 스크린샷 저장·원문 로그·네트워크 전송은 추가하지 않았습니다.
- 연결 10초, 결과 20초, 캡처 1초, OCR 3초, 토큰 5초 및 빈도/메모리 제한은 그대로입니다. 취소는 CANCELLED로 기록하고 늦은 이미지 콜백은 기존 해제 경로로 처리합니다. **진단은 캡처 허가나 B의 행동 판단을 변경하지 않습니다.** PrivacyPolicy와 B·C·D 공통 데이터/포트는 변경하지 않았습니다.

## 실패 문자열 읽기

`CAPTURE_RESULT_TIMEOUT:ATTEMPT=…;STAGE=…;REACHED=…;BLOCK=…;EVIDENCE=…;DETAIL=…;GEOMETRY=…;API=…;TARGET=…;RELATED=…;EVENT_WINDOW=…;IGNORED=…`

STAGE는 최신 시도의 마지막 단계이고 REACHED는 **그 시도에서만** 관측된 단계입니다. 새 시도가 시작되면 이전 시도의 진행 이력도 지웁니다. PUBLISHED는 안전한 트리만 반환한 경우에도 나올 수 있으므로 단독으로 OCR 성공으로 보지 않습니다.

| 코드/필드 | 의미와 다음 조사 |
|---|---|
| NO_HOST_ROOT, ATTEMPT=0 | BEGIN 이후 합성 root를 확인한 수집 시도가 없음. 빠른 제외/이벤트 debounce/호스트 전면 표시부터 확인. 특정 창의 보호 차단으로 단정하지 않음 |
| COLLECTION + TREE_PROTECTION | 후보 검출 전 노드 F0-A 차단. 비밀번호·입력칸·민감 문맥 검사 유지 |
| NO_POPUP_SIGNAL | 수집은 완료했지만 새 후보 근거가 없어 분석하지 않음 |
| POPUP_DETECTED → F0_A → F0_A_PASSED | 후보 확인과 초기 재검사 통과. 이후 캡처 허가·민감정보 부재의 보증은 아님 |
| NON_APPLICATION_FOCUS + RELATED=INPUT_METHOD/SYSTEM/ACCESSIBILITY_OVERLAY | 호스트 root를 검사했으나 다른 비앱 창이 active/focused. SYSTEM_WINDOW 유지 |
| TARGET_NOT_APPLICATION / FOREIGN_APPLICATION / SECONDARY_DISPLAY | 대상 유형/격리 조건 때문에 SYSTEM_WINDOW. 창 목록이나 앱 이름을 출력하지 않음 |
| REGION_TARGET_INACTIVE / REGION_TARGET_MISSING | 대상 창 active/단일 창 매칭 조건 실패 |
| REGION_NO_BOUNDED_DIALOG + LARGE_OR_FULL | 창이 화면 대비 제한 영역이 아니며 신뢰할 단일 Dialog 노드 경계도 없음. 배경 Activity나 API의 창 경계 차이인지 추가 확인 필요 |
| REGION_MULTIPLE_DIALOGS | 서로 다른 유효 Dialog 경계가 둘 이상. 임의 선택 안 함 |
| REGION_OCCLUDED + RELATED | 영역에 겹치는 동일/상위 레이어 창의 유형. 기존 API 34+ 비활성·비포커스 접근성 오버레이 제외 규칙만 유지 |
| GEOMETRY=BOUNDED/LARGE_OR_FULL/TOO_SMALL/OUTSIDE/EMPTY | 대상 창과 화면의 상대적 범위 관계. 좌표/면적 실수치는 제공 안 함 |
| MAGNIFIED / REGION_REVALIDATION / BUFFER_GEOMETRY / FRAME_GEOMETRY | 같은 REGION_UNCERTAIN이어도 확대/사전 영역 재검사/실제 버퍼 크기·포함 관계/프레임 경계 불일치를 구별 |
| CAPTURE_REQUEST, API=NONE | 파이프라인 요청 단계일 뿐 Android 호출의 증거 아님 |
| CAPTURE_API, API=WINDOW 또는 DISPLAY | 실제 takeScreenshotOfWindow(API 34+) 또는 takeScreenshot(API 30~33) 호출 지점 도달. 반환/성공 보증 아님 |
| CAPTURE_RETURNED → OCR → OCR_RETURNED | 제한 영역 이미지 반환, 실제 OCR 요청, OCR 반환. 이후 최신 화면 재검사나 F0-B에서 차단될 수 있음 |
| F0_B / F0_B_PASSED | 독립 이미지 텍스트 보호 검사 시작/통과. 실패 시 OCR은 다른 담당자에게 전달되지 않음 |
| CANCELLED | 화면/터치/TTL/서비스 상태 변경 등으로 분석 취소. 이전 진행 흔적을 성공으로 재사용하지 않음 |
| EVENT_WINDOW=DIFFERENT | 이벤트 발생 창과 수집한 active root 창이 다름. 원인/창 소유자를 단독 확정할 수 없음 |

DETAIL이 어떤 분기에서 나왔는지는 고정 코드로 확인할 수 있지만 OS의 잘못된 창 정보인지, 시스템 창이 실제 전면에 있었는지는 재현 결과 없이는 확정할 수 없습니다. 시스템 보호/보안 창/입력 보호 및 불확실 영역 차단을 제거해 통과시키지 않습니다.

## Windows PowerShell 재검증

프로젝트 루트에서, 개인정보 없는 시험 에뮬레이터 1대만 실행합니다. JDK 17, SDK 35 및 Build Tools 35.0.0은 유지합니다.

```powershell
git fetch origin
git switch fix/capture-session-diagnostics
git pull --ff-only
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
& $adbPath install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true -e connectionOnly true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

연결 검사 기대 결과: `SERVICE_CONNECTION_READY`와 `OK (1 test)`. 본 앱에서 접근성 서비스 켜짐, 수집 동의, 별도 캡처 동의, 분석/캡처 허용 앱 **kr.closeforyou.capturetest 하나만**, **허용 앱 분석 시작**을 직접 확인합니다. 그 후:

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

통과 기대: `SERVICE_CONNECTION_READY`, `CAPTURE_RESULT:`의 API=WINDOW(API 37), REACHED의 CAPTURE_API/OCR_RETURNED/F0_B_PASSED/PUBLISHED, 그리고 `OK (1 test)`입니다. 시험 자체가 OCR_MATCH 및 OCR 영역 수 > 0을 assert합니다. 이것은 기대 조건이며 이 클라우드에서 확인한 결과가 아닙니다. 실패하면 새 고정 코드 문자열만 기록합니다. 타임아웃은 여전히 20초입니다.

시험 중 터치·회전·화면 이동하지 않고, 완료 후 본 앱에서 일시정지/동의 철회합니다. 실제 메시지·은행·실제 결제 화면으로 재현하지 않습니다. 원본 dumpsys/노드 덤프/화면 이미지/Logcat 원문 전체를 기록하지 않습니다. 기존 OCR 모델 계측은 [SERVICE_CAPTURE_TEST.md](SERVICE_CAPTURE_TEST.md)의 본 앱 runner 명령으로 **따로** 실행한 후 서비스 연결을 다시 확인합니다.

## 로컬 검증 범위

회귀 단위 시험은 다른 패키지 이벤트 제외, 시도별 코드 초기화, 늦은 콜백/취소/END 격리, 현재 시도 매칭, 영역 거부 사유/상위 창 유형, F0-A/F0-B 단계와 비호출을 합성 DTO로 검사합니다. Android 캡처 API 실행 성공을 JVM Mock으로 주장하지 않습니다. 최종 빌드·테스트 수치는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 기록합니다.

최종 로컬 결과: 위 debug 명령과 추가 `:app:assembleRelease` 실행으로 BUILD SUCCESSFUL(28초), JVM 78개(실패/오류/건너뜀 0), 양쪽 debug Lint 이슈 0. DEX 패키징 검사와 release의 debug provider/권한 부재 확인 통과. 기기 계측은 컴파일만 했고 실행하지 않았습니다.
