# A 담당 F1: 팝업 후보 감지와 고정 합성 검증

기준: PR #6 병합 후 `main`의 `db63c83`, 새 브랜치 `feature/f1-popup-detection`. A의 감지·보호 및 독립 합성 fixture 범위입니다. B 분류/행동, C 안내/음성, D Room을 구현하지 않습니다. **후보 감지 ≠ 광고 판정 ≠ 자동 실행 허가**입니다. 캡처/OCR 보호와 PR #6의 불일치 버퍼 거부를 유지합니다.

## PR #7 API 37 실측: baseline 차단 조사

사용자가 Windows API 37에서 실제 실행한 결과는 SERVICE_CONNECTION_READY 이후 13개 CASE의 BASELINE_BLOCKED와 SEQUENCE_SECOND의 SEQUENCE_PREREQUISITE_MISSING, 종합 **UNOBSERVED=14 / FAILURES (1 test, 1 failure)**입니다. APK·실제 서비스·수집 동의·단일 host 허용·분석 시작과 캡처 동의/목록 OFF를 확인했다고 보고했습니다. **F1 감지 정확도의 미탐/오탐 결과가 아니라, 실제 자극 전 baseline에서 멈춘 결과**입니다. 이전 JVM 모델 14개 성공을 이 실측의 성공으로 대체하지 않습니다.

확인된 진단 결함: baseline의 blocks 확인 직후 예외를 던져 기존 F1_EVIDENCE 출력에 도달하지 않았습니다. 또한 F1 bridge는 차단 사유만 보유해 수집 단계/검사 필드를 구분하지 못했습니다. 이번 수정은 baseline assertion **전에** BASELINE 근거와 F1_COLLECTION을 출력하고, 실제 자극 결과는 PHASE=STIMULUS로 분리합니다.

**기기 차단 원인은 아직 미확정**입니다. 코드의 고정 baseline 문구/기본 클래스 문자열만으로는 JVM 보호 검사에서 차단을 재현하지 못했습니다. 이는 실제 노드의 inputType/editable/password 속성, 생성된 메타데이터, 배경 창, 시스템/다른 앱 창, geometry/수집 오류가 안전하다는 증명이 아닙니다. PrivacyPolicy·F0-A/F0-B의 판정·상한·순서와 fixture의 동작을 바꾸지 않았습니다. 증거 없이 특정 단어를 허용하거나 편집/민감 속성을 무시하지 않습니다.

새 `F1_COLLECTION`은 한 번의 실제 차단에 속한 enum tuple을 그대로 전달합니다. 여러 시도의 마지막 STEP/FIELD를 섞지 않으며 다른 대상 창의 차단은 계속 제외합니다. 코드 외 원문/이미지/좌표/창 ID/노드/예외 메시지는 포함하지 않습니다.

| 필드 | 의미와 후속 판단 |
|---|---|
| PHASE | BASELINE 또는 STIMULUS. 전제 실패와 실제 F1 자극 결과를 구분 |
| BLOCK | PASSWORD / EDITABLE_FIELD / SENSITIVE_CONTENT / SYSTEM_WINDOW / UNREADABLE_TREE / TRUNCATED_TREE 등 기존 보호 사유 |
| STEP / DETAIL | ROOT / PACKAGE_GATE / WINDOWS / GEOMETRY / PROTECTION_PASS / SNAPSHOT_PASS와 기존 고정 CaptureDetail. 수집·보호 실패 위치 |
| TREE | TARGET / SAME_APP_BACKGROUND / NONE. 같은 앱의 가려진 배경 트리에서 발생한 보호도 계속 유지 |
| FIELD / CAUSE | INPUT_TYPE·EDITABLE_FLAG·PASSWORD_FLAG·TEXT·VIEW_ID·CLASS_NAME·HINT 등 검사 출처와 NON_ZERO_INPUT_TYPE / SENSITIVE_TEXT / UNCERTAIN_TEXT / 제한·미확인 수집 코드. 실제 속성값이나 문자열은 반환하지 않음 |
| RELATED / FRAME | 관련 창 종류와 화면 frame의 검증 상태만 반환. 창 정체성이나 실제 치수 없음 |

읽는 순서:

1. WINDOWS의 NON_APPLICATION_FOCUS/FOREIGN_APPLICATION 등이면 차단을 유지합니다. 전용 합성 에뮬레이터에서 키보드·다른 창·남은 시험용 입력 화면 등 준비 조건을 검토합니다. 다른 앱 허용 추가나 시스템 창 무시는 해결책이 아닙니다.
2. GEOMETRY의 DISPLAY_GEOMETRY_UNCERTAIN이면 FRAME 코드와 기존 화면 geometry 시험으로 조사합니다. 좌표 보정·전체 화면 캡처 우회는 하지 않습니다.
3. PROTECTION_PASS/SNAPSHOT_PASS이면 TREE/FIELD/CAUSE로 입력 속성·민감/불확실 텍스트·수집 제한을 구분합니다. NON_ZERO_INPUT_TYPE도 기존 규칙대로 차단합니다. 해당 고정 fixture의 Android 속성이 실제로 입증된 뒤에만 fixture 수정 여부를 검토합니다.
4. FRAMEWORK_FAILURE/MISSING_CHILD/TIME_LIMIT/NODE_COUNT_LIMIT/DEPTH_LIMIT/CHILD_COUNT_LIMIT 등은 보호 불확실성으로 계속 차단합니다. 시간·개수·깊이 제한을 구분하는 코드만 추가했으며 기존 순서와 상한은 같습니다. 제한을 늘리거나 부분 수집을 성공 취급하지 않습니다.
5. COLLECTION=NONE은 해당 소유 창에 연결된 상세 차단 기록이 없다는 뜻입니다. 정상 수집 성공이 아니며 APK 버전 혼합/수집 미관측을 확인합니다.

baseline은 실제 collection/F0-A 결과를 요구합니다. SCROLL/notification 같은 raw 이벤트만으로 baseline을 통과하지 않습니다. baseline이 통과해도 RESET 후 각 실제 자극과 탐지/차단 결과를 별도로 요구하고, 기존 8초/최소 2초 관측·실패 판정·연속 전제는 유지합니다. BASELINE_BLOCKED는 계속 UNOBSERVED이며 새 계측 자체가 성공으로 바꾸지 않습니다.

재시험은 아래 Windows 절차로 **본 앱 debug APK + host debug APK + host 계측 APK 모두** 새로 빌드/설치합니다. 개인정보 없는 고정 화면에서만 실행하고, 제공할 결과는 F1_EVIDENCE의 PHASE=BASELINE, F1_COLLECTION, F1_CASE, F1_SUMMARY의 고정 코드입니다. 전체 Logcat/스크린샷/좌표 dump는 수집하지 않습니다. 개발 환경에서는 실제 API 37을 실행하지 않았으며 새 원인 코드와 F1 시나리오 성공은 미검증입니다.

## 수정 전후

| 기존 근거 | 문제 | 변경한 A 내부 판정 |
|---|---|---|
| 새 application window ID | 일반 Activity 전환도 NEW_WINDOW 후보가 됨 | 화면 안에 포함되고 최소 32×24, 면적 92% 미만, 가로 96%·세로 90% 미만인 창만 bounded-window 근거. 전체 화면 전환은 UNCERTAIN |
| 새 bounded subtree + 자식 2개 이상 | 스크롤/일반 layout 추가·이동을 overlay로 오탐 가능 | 자식 수만으로 후보 승격하지 않음. 명시적 Dialog 클래스/해제 의미 또는 새 pane + 같은 창의 안정적인 겹치는 비조상 peer 근거 요구 |
| bounds/childCount까지 포함한 outline 지문 | 동일 Dialog의 이동·내부 배치 변화가 새 overlay가 됨 | 세션 salt + path/view ID/class의 식별자 사용. 위치·자식 수·본문은 새 인스턴스 증거로 사용하지 않음 |
| pane title만 있으면 overlay 의미 | 일반 페이지 pane 제목도 후보가 될 수 있음 | pane 자체 경계, 신규 등장, 안정된 겹치는 peer, 같은 창 baseline을 함께 확인. 전체 화면/조상만 있는 pane은 후보 아님 |
| 첫 화면/전체 전환을 부족한 근거의 Candidate로 발행 | 실제 팝업 수·캡처 시도와 일반 화면 변화가 섞임 | 첫 일반 화면은 baseline. 전환/이름 없는 구조 변화는 별도 불확실 판정, Candidate 발행·캡처를 시작하지 않음 |

숨겨진/화면 밖 노드와 scrollable 조상의 자손은 overlay 근거로 사용하지 않습니다. bounded native Dialog는 실제 트리에 Dialog 클래스나 비활성 배경 창이 없어도 창 경계를 근거로 후보가 될 수 있습니다. 화면 회전·앱 변경·보호 차단/설정 변경 때 baseline을 재사용하지 않습니다.

`DetectionAssessment`는 A 내부의 `POPUP_CANDIDATE / NO_POPUP / UNCERTAIN`과 고정 사유입니다. `NO_POPUP`은 알고리즘이 후보를 내지 않았다는 뜻이며 임의 사용자 화면의 정답 보증이 아닙니다. 불확실 전환은 기존 FULL_SCREEN_TRANSITION/INSUFFICIENT_EVIDENCE 신호로 식별하지만 공통 Candidate로 승격하지 않습니다. 불확실 후보를 사용자에게 안내할 별도 A/C 연동 경로는 TODO입니다. 공통 PopupSnapshot/PopupDetection/PopupEvent/인터페이스의 형식은 바꾸지 않았습니다.

F1의 창/pane 근거는 캡처 ROI 허가가 아닙니다. 새 pane 근거를 PopupRegionResolver의 승인 조건으로 추가하지 않았습니다. 같은 Activity overlay를 F1에서 감지해도 기존 캡처 영역/버퍼 좌표 검증을 통과하지 못하면 OCR은 계속 차단됩니다.

## 독립 합성 시나리오와 정답

호스트 `kr.closeforyou.capturetest`에 별도 `DetectionScenarioActivity`와 실제 두 번째 Activity인 `SyntheticPlainActivity`를 추가했습니다. 기존 한국어 이미지·패턴·OCR fixture는 그대로입니다. 아래는 **테스트 정답과 기대 동작**이며 Android 기기에서 이미 실행한 결과가 아닙니다.

| CASE | 고정 화면/변화 | 기대 |
|---|---|---|
| DIALOG | 실제 Android Dialog | DETECTED |
| INLINE_OVERLAY | 같은 Activity의 bounded pane, 기존 문서 위에 겹침 | DETECTED |
| SEQUENCE_FIRST / SEQUENCE_SECOND | 같은 호스트에서 첫 Dialog → 새로운 두 번째 Dialog | 각각 DETECTED, 서로 다른 실제 창. 동일 창 업데이트와 구분 |
| SCREEN_TRANSITION | 일반 문서 → 실제 다른 Activity | 후보 아님. UNCERTAIN이면 UNRESOLVED로 별도 집계 |
| KEYBOARD | 빈 합성 입력 필드 + 실제 IME 표시 | 입력/시스템 보호 BLOCKED, 자체 창의 IME 가시성도 관측 |
| TOAST | 고정 문구의 실제 Android Toast | 후보 아님. host의 notification-state 이벤트 관측 필요 |
| SCROLL | 일반 문서의 실제 scrollTo | 후보 아님. 실제 SCROLL 이벤트 관측 필요 |
| LAYOUT_CHANGE | 기존 문단 padding·제목 배치 변경 | 후보 아님 |
| LOGIN | 합성 로그인 + 빈 password 필드 | PASSWORD/EDITABLE_FIELD/SENSITIVE_CONTENT 보호 |
| PAYMENT_INPUT | 합성 금융 입력 문맥 + 빈 필드 | SENSITIVE_CONTENT/EDITABLE_FIELD 보호 |
| PERMISSION_PROMPT | 가상 권한 선택 문구의 Dialog | 후보 감지. 기존 고정 선택 요약 경로만 가능. 실제 권한 요청·승인 없음 |
| UNCERTAIN_CONTENT | 허용 언어 밖 문자를 포함한 고정 합성 화면 | SENSITIVE_CONTENT 보호, 해석 불확실성 차단 |
| UNMARKED_OVERLAY | 동일한 카드 형식이지만 pane/Dialog 의미 없음 | UNCERTAIN 유지, 임의 캡처/자동 동작 없음 |

14개 CASE의 분모는 팝업 5 / 일반 변화 4 / 보호 4 / 불확실 1입니다. 일반 결제·동의·권한 요구 문구는 실제 개인정보/입력 화면과 다르게 취급하는 기존 규칙을 유지합니다. 위 LOGIN/금융 입력은 보호하고 PERMISSION_PROMPT는 중요 선택 근거만 남깁니다. B의 USER_DECISION 분류 엔진을 구현하거나 위험 버튼을 승인하는 시험이 아닙니다.

호스트의 버튼/계측 코드만 자체 시나리오를 표시·정리합니다. 접근성 서비스는 버튼 클릭·좌표 탭·뒤로 가기·포커스 이동을 실행하지 않습니다. 필드는 끝까지 비워 두고 실제 계정·번호·권한·네트워크 데이터는 사용하지 않습니다.

## 실제 서비스 관측 및 결과 집계

`ServiceDetectionScenariosOptInTest`는 **독립 호스트 프로세스**를 계측합니다. 서비스 앱을 계측/재시작하거나 Runtime을 다른 classloader에 로드하지 않습니다. 기존 실제 연결 preflight와 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`를 사용합니다. captureOcrService 플래그는 이 preflight의 기존 명칭이며 F1 시험의 캡처 허락이 아닙니다.

debug 전용 `SyntheticDetectionTestProvider`는 signature 권한과 고정 host 호출자를 검사합니다. 수집 동의·분석 시작·허용 앱 정확히 host 하나, **캡처 동의 꺼짐 + 캡처 앱 목록 비어 있음**을 요구합니다. 설정을 대신 바꾸거나 detector/예산을 초기화하지 않습니다. 실제 서비스의 수집 후 F0-A 차단과 F1 판정을 관측할 뿐 반환 값/토큰/분류를 주입하지 않습니다.

각 CASE는 새 관측 세션에서 시작합니다. 먼저 실제 일반 화면 baseline이 수집됐는지 확인하고 관측 기록만 RESET한 뒤 변화를 일으킵니다. SEQUENCE_SECOND는 첫 Dialog를 유지한 같은 Activity에서 새 창을 만듭니다. 호스트가 소유한 실제 창 ID는 IPC 메모리에서 상관관계 확인에만 사용하고 출력하지 않습니다. RESULT는 같은 소유 창의 판정·차단만 합칩니다. 다른 창의 마지막 BLOCK/EVIDENCE를 혼합하지 않습니다. Toast만 window ID가 없을 수 있어, 같은 고정 호스트의 해당 세션 notification 이벤트를 별도 집계합니다.

8초 안에 실측을 기다리며 최소 2초 관측 구간을 요구합니다. SCROLL/Toast/키보드는 해당 실제 자극을 확인하지 못하면 UNOBSERVED로 실패합니다. 프레임워크가 Toast 알림을 노출하지 않거나 IME가 설치/활성화되지 않은 환경은 성공 대신 미관측으로 보고해야 합니다. 원본 notification text는 읽지 않습니다. 일반 스크롤·터치는 기존 토큰을 계속 무효화하며 Toast는 수집/OCR을 시작하지 않습니다.

| GRADE | 의미 |
|---|---|
| DETECTION_SUCCESS | 고정 팝업 정답에서 실제 후보 관측 |
| MISSED_POPUP | 팝업 정답을 놓침/불확실로만 관측 |
| FALSE_POSITIVE | 일반 변화 또는 불확실 정답을 팝업으로 승격 |
| CORRECT_NON_POPUP | 실제 관측한 일반 변화에서 후보·불확실 없음 |
| SAFE_BLOCK | 보호 정답에서 해당 보호 사유를 확인. 단순 collection 오류로 대신 통과시키지 않음 |
| UNEXPECTED_BLOCK / SAFETY_FAILURE | 일반 화면의 차단 또는 보호 화면에서 후보 관측·차단 실패 |
| UNCERTAIN_RETAINED / UNCERTAINTY_LOST | 불확실 정답의 불확실성 보존/유실 |
| UNRESOLVED | 일반 전환 등에 불확실 판정. 오탐은 아니지만 true negative로 계산하지 않음 |
| UNOBSERVED | 자극/수집/결과 미관측 또는 관측 overflow. 성공 아님 |

후보와 보호 차단이 동일 대상 창에서 모두 나오면 CONFLICT이며 실패입니다. baseline 자체가 후보이면 BASELINE_FP로 별도 실패합니다. 세션/호스트 정리 실패도 최종 실패입니다. 한 CASE 실패 후에도 나머지 CASE를 시도해 개별 결과·종합 집계를 출력합니다. 명시적 전체 취소는 전파합니다.

연속 시험은 첫 Dialog의 실제 탐지와 정리가 검증된 경우에만 둘째 탐지를 집계합니다. 전제가 없으면 SEQUENCE_PREREQUISITE_MISSING/UNOBSERVED로 실패하며, 첫 표시 실패 뒤 생긴 첫 Dialog를 둘째 성공으로 잘못 세지 않습니다.

관측은 45초/256개 고정 코드로 제한하고 overflow는 미관측으로 실패합니다. 만료·END·설정 철회·연결 해제 시 참조를 폐기합니다. 실제 좌표·창 ID·패키지·노드·텍스트·스크린샷·RGB·해시·이미지는 반환/저장하지 않습니다. release에는 provider/session/호스트 구현을 포함하지 않고 관측 hook은 no-op입니다.

## Windows Android Studio API 37 절차

개인정보 없는 전용 에뮬레이터를 사용합니다. 실제 메시지·은행·결제 앱을 열지 않습니다. API 35에서도 아래 명령을 별도 실행할 수 있으나 버전별 실제 결과를 따로 기록합니다.

```powershell
git switch feature/f1-popup-detection
git pull --ff-only origin feature/f1-popup-detection
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # 실제 대상 serial 선택
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

Android Studio의 Gradle JDK는 JDK 17, SDK는 Platform 35/Build Tools 35.0.0입니다. 에뮬레이터 실행 API 37과 compile SDK 35는 별개입니다.

본 앱 UI에서 수집 동의 → 접근성 서비스 실제 활성화 → 허용 앱을 **kr.closeforyou.capturetest 하나만** 추가합니다. 이전 OCR 시험에서 켠 **임시 화면 캡처·한국어 분석 동의를 끕니다**(캡처 앱 목록도 지워짐). `허용 앱 분석 시작`을 누릅니다. 키보드 시험을 위해 에뮬레이터의 화면 키보드가 실제 표시되도록 Android 설정에서 활성화합니다. adb로 동의·서비스 상태를 강제 설정하지 않습니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceDetectionScenariosOptInTest -e f1Detection true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

기대 출력 구조: SERVICE_CONNECTION_READY → 14개의 F1_CASE(각 EXPECTED/ACTUAL/GRADE/DETAIL) 및 F1_EVIDENCE의 고정 사유 → F1_SUMMARY → 모든 요구 관측과 기대 조건 충족 시 OK (1 test). F1_SUMMARY에는 미탐/오탐/차단/불확실/미관측 건수 및 OCR=NOT_TESTED;ACTIONS=NONE이 별도로 남습니다. **이 문서는 해당 API 37 시험을 이미 통과했다고 주장하지 않습니다.**

시험 중 화면을 터치하거나 다른 앱으로 이동하지 않습니다. opt-in 인자 누락으로 건너뛴 시험(assumption skip)은 검증 성공이 아닙니다. 실제 사용자 조작·TalkBack 탐색과 동시 실행하는 호환성 평가는 별도 후속 시험입니다.

F1_SETTINGS_REQUIRED면 캡처 동의·허용 앱·일시정지를 UI에서 확인합니다. SERVICE_CONNECTION_TIMEOUT은 기존 [서비스 연결 진단](SERVICE_CAPTURE_TEST.md)을 확인합니다. STIMULUS_OR_RESULT_UNOBSERVED는 실제 IME/Toast/scroll/창 수집 중 무엇을 관측하지 못했는지 F1_EVIDENCE의 고정 코드로 구분하고, timeout 확대나 Mock 성공 주입으로 우회하지 않습니다. 종료 후 `화면 분석 일시정지` 또는 동의 철회를 합니다.

특정 화면을 수동으로 표시하려면 다음처럼 고정 CASE 이름만 전달한 뒤 호스트의 `실험 실행`을 누릅니다. SEQUENCE_FIRST는 같은 버튼을 두 번째 누르면 새 Dialog를 표시합니다. 민감 화면이어서 B/C/D 이벤트가 없는 경우도 정상 보호 결과입니다.

```powershell
& $adbPath -s $serial shell am start -n kr.closeforyou.capturetest/kr.closeforyou.capturetest.DetectionScenarioActivity --es scenario INLINE_OVERLAY
```

## 검증 수준과 남은 문제

JVM의 `F1SyntheticMatrixTest`는 실제 정책의 합성 DTO/primitive 입력 14개와 정답을 비교합니다. 목표 집계는 탐지 5, 안전 차단 4, 일반 비후보 3, 불확실 유지 1, 미해결 일반 전환 1이며 미탐/오탐은 별도입니다. 이는 **모델 시험의 작은 고정 집합**입니다. Android 이벤트 발생·실제 창/노드 구조·Toast/IME·Dialog 레이어·에뮬레이터 정확도를 증명하지 않습니다. `PopupCandidateDetectorTest`는 중복/연속/이동/스크롤 조상/숨김/경계/앱 변경·회전을, `DetectionTestSessionTest`는 창/앱 격리·충돌·미관측·취소/만료·상한을 검사합니다. 정답 집계 자체의 오분류를 막는 host JVM 시험도 있습니다.

최종 실제 실행 결과는 [개발 상태](DEVELOPMENT_STATUS.md)의 F1 기록을 확인합니다. 기기가 없어 API 37 실측 정확도·제조사별 성능/지연/메모리·TalkBack 사용성은 미검증입니다. 합성 사례 성공률을 모든 앱의 precision/recall로 보고하지 않습니다. 보호 차단과 불확실을 true negative에 넣어 점수를 높이지 않습니다.

TODO: full-width bottom sheet/전체 화면 이미지/캔버스·가상화 트리·의미 없는 overlay, 동일 경로에서 중간 제거 없이 교체되는 inline 팝업, freeform/분할 화면의 작은 일반 Activity와 Dialog 구분, 복잡한 pane/부모 가시성 및 빠른 이벤트 병합으로 인한 미탐. 현재 bounded-window와 pane 근거도 휴리스틱이므로 오탐 가능성을 0이라고 주장하지 않습니다. 불확실 상태의 사용자 안내와 실제 지원 범위는 후속 A/C 검토가 필요합니다.

PR #6의 더 큰 버퍼 좌표 대응 및 기존 전체 캡처/OCR 통합 실패는 **그대로 미해결**입니다. 기존 OCR/패턴 계측을 삭제·skip·성공 변환하지 않았고 이 F1 suite가 그 통과를 대신하지 않습니다. 다시 OCR 시험을 할 때는 기존 별도 캡처 동의 절차를 따라야 합니다. F5 유지, F6 영상 줄거리 제외, F10/F12/F13 필수 후속 구현 요구도 그대로입니다.
