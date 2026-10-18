# F1 독립 F0 보호 우선순위 계측

기준: `feature/f1-popup-detection` / `094dc09`. 이 변경은 testOnly `capture-test-host`와 JVM/Android 시험 및 이 문서만 추가합니다. 운영 collector/service/provider/F0/F1/DTO/지문/예산과 원래 14개 화면·정답을 변경하지 않습니다. 커밋·푸시는 하지 않습니다.

## 질문과 네 조건

실제 서비스가 ScrollView의 앞선 일반 숨김 자식의 geometry를 처리하기 전에, 뒤의 숨김 보호 필드를 F0 pass에서 차단하는지 관측합니다. 각 조건은 별도 Activity 인스턴스와 별도 BEGIN/END 세션입니다. Activity는 exported=false이며 독립 호스트에만 포함됩니다.

| fixture | 별도 샘플에서 요구할 조건 | 같은 서비스 ATTEMPT에서 요구할 결과 |
|---|---|---|
| CONTROL | 일반 첫 노드 REVERSED_Y, 숨김, Scroll 조상 / 뒤 노드는 일반 | SNAPSHOT_PASS / UNREADABLE_TREE / MALFORMED_NODE_BOUNDS / NODE_BOUNDS_VALIDATE 및 REVERSED_Y·NOT_VISIBLE·SCROLL_ANCESTOR·DESCENDANT |
| PASSWORD | 일반 첫 노드 조건 동일 / 뒤 노드는 숨김 password, 비editable | PROTECTION_PASS / PASSWORD / PASSWORD_FLAG / NODE_FIELD_CHECK |
| EDITABLE | 일반 첫 노드 조건 동일 / 뒤 노드는 숨김 editable, 비password | PROTECTION_PASS / EDITABLE_FIELD / EDITABLE_FLAG / NODE_FIELD_CHECK |
| SENSITIVE_TEXT | 일반 첫 노드 조건 동일 / 뒤 노드는 숨김 고정 합성 민감 문구, 비입력 | PROTECTION_PASS / SENSITIVE_CONTENT / TEXT / SENSITIVE_TEXT / NODE_TEXT_CHECK |

모든 서비스 결과는 TREE=TARGET, DETAIL=TREE_PROTECTION, FRAME=VERIFIED, EXCEPTION=NONE도 요구합니다. 암호/입력 값은 비워 두고, 민감 문구도 실제 계정·번호가 아닌 고정 합성 문구만 씁니다. 다른 라벨/description/hint는 안전한 고정 어휘 또는 빈 값만 허용합니다. 노드 ID·Activity 명칭에도 보호 키워드를 넣지 않습니다. 실제 TextView password transformation / EditText / TextView를 쓰며 접근성 노드나 Rect를 덮어쓰지 않습니다. 키보드 표시·입력·서비스 클릭·제스처 요청은 없습니다.

## 실제 노드 관계와 출처

1. native 관측은 소유한 두 View가 생성한 실제 AccessibilityNodeInfo에서 속성·원래 Rect 형태를 읽고, View parent 체인에 소유 ScrollView가 있는지 검사합니다. native의 두 노드 목록 순서는 **접근성 순서의 근거가 아닙니다**.
2. UiAutomation은 소유 native 창 ID로 한 application window를 선택하고, root부터 실제 `getChild(index)`를 preorder로 순회합니다. 두 resource ID가 정확히 한 번씩 존재하고 FIRST가 LATER보다 앞서며 실제 Scroll 조상 아래 있고 숨김인지 검사합니다. 모든 노드의 package/window 소유권과 보호 필드·라벨 대조를 검사합니다. View 추가 순서로 원격 순서를 추정하지 않습니다. 누락·중복·역순·다른 조기 보호 필드가 있으면 실패합니다.
3. 둘은 NATIVE_SEPARATE_SAMPLE / UIAUTOMATION_SEPARATE_SAMPLE이며, 서비스 ATTEMPT와 원자적으로 결박되지 않습니다. 관측 전후에 조건을 다시 확인하지만 그 사이의 변화를 완전히 배제하지 못합니다. native와 UiAutomation 사이도 원자적이지 않습니다.
4. 기존 signature/고정 caller/실제 설정/소유 창 provider를 그대로 사용합니다. RESET은 관측 기록만 비웁니다. 실제 소유 contentDescription 변경으로 이벤트를 발생시키고, 이후 RESULT의 시도별 tuple만 검사합니다. 서비스 결과·노드·bounds·설정은 주입하지 않습니다.

최초 observe는 기존 2초 최소/3초 제한 안에서 기대 차단을 요구합니다. 이후 native/원격 노드 검사가 모두 끝난 직후 **같은 session과 ownedWindowId로 RESULT를 한 번 더 조회**합니다. 이 응답의 code가 OBSERVED이고 overflow가 없으며 전체 attempts가 ProtectionPriorityContract 판정을 통과한 경우에만 성공 CASE와 count를 기록합니다. 최초 성공 보고서는 최종 성공 판단에 재사용하지 않습니다. 최종 미관측·잘못된 code·중복 ordinal·TIME_LIMIT·TARGET_MISSING·다른 차단도 실패입니다.

관측 종료점은 이 **최종 RESULT 응답의 스냅샷**입니다(`CUTOFF=FINAL_RESULT_SNAPSHOT`). 그 뒤 END까지의 새 결과나 진행 중인 수집 완료를 포함한다고 주장하지 않습니다. RESULT와 END를 원자적으로 묶거나 세션을 drain하는 기존 API는 없습니다. 마지막 응답 이후의 변화를 없다고 추정하지 않으며, END 정리 실패는 여전히 전체 실패입니다. 추가 RESULT는 재시도/시간 제한 완화가 아니고, 기존 3초 제한은 최초 observe 구간에 적용됩니다. framework/IPC 자체의 blocking을 강제로 중단하는 기능을 추가한 것도 아닙니다.

대조군의 서비스 tuple 자체는 숨김 Scroll 자식의 역전을 함께 보고합니다. 민감 조건은 첫 F0 거부에서 끝나므로 **그 서비스 시도에서 앞선 노드 Rect도 역전이었다는 직접 증거는 제공하지 않습니다**. 기존 API는 차단한 정확한 노드 identity·당시 자식 순서도 반환하지 않습니다. 그러므로 결론은 별도 fixture 관측과 해당 보호 차단의 일치이며, 같은 순간의 전체 트리/검사 순서 완전성이나 특정 노드와의 원자적 결합 증명이 아닙니다. 운영 hook을 추가해 이 한계를 가리지 않습니다.

## 실패·수명·출력

- 준비 관측 최대 3초는 fixture 조건 관측용이며 기다렸다는 이유로 유효성을 인정하지 않습니다. 서비스 관측도 최대 3초(최소 2초)이며 운영 deadline을 바꾸거나 수집을 재시도하지 않습니다.
- 실제 root/노드/순서/숨김/역전 조건 미확립, 창 불일치, 다른 보호 원인, TIME_LIMIT, TARGET_MISSING, Ready/F1 평가, unknown 진단, 중복 ordinal, overflow 및 결과 미관측은 INCONCLUSIVE/최종 JUnit 실패입니다. 기대 결과 하나 옆에 다른 시도의 실패가 있어도 통과하지 않습니다.
- 필수 opt-in 누락도 실패하며 skip/Assumption을 성공으로 계산하지 않습니다. 한 fixture 실패 후 나머지를 관측해도 네 조건 모두와 정리가 성공해야 전체 통과합니다.
- 원격 순회는 256노드/깊이60/자식256 상한입니다. Android 자식/root/window는 finally에서 해제하며, 세션 END와 Activity 종료는 NonCancellable finally에서 각각 시도합니다. BEGIN의 key는 취소로 유실되지 않게 저장합니다. 실패한 정리와 flags 복구는 최종 실패입니다. 프로세스 종료·framework의 해제/IPC 자체 실패까지 완전한 정리를 보장하지 않습니다. 기존 provider의 45초 만료도 유지합니다.
- 테스트 전용 PriorityReleaseScope는 native/remote 자식·root·window의 일반 Exception 해제 실패를 고정 enum으로만 기록하고 다음 해제를 계속 시도합니다. 동일 window 객체 참조는 목록에서 한 번만 해제하며 equals가 같은 별도 객체는 각각 해제합니다. Android가 recycle한 node 인스턴스를 재사용할 수 있으므로 노드는 전체 순회의 identity 집합으로 묶지 않고 각 취득/finally 경계에서 한 번씩 해제합니다. identity 집합은 window 목록 해제 안에서만 만들고 즉시 비웁니다.
- 검사 본문이 실패/취소되면 그 예외가 해제 예외로 대체되지 않으며 원본 예외/원문을 suppressed cause나 결과 객체에 저장하지 않습니다. 이미 본문이 실패했다면 그 실패를 유지하고, 정상 본문에서 해제만 실패했다면 FRAMEWORK_RELEASE_FAILED 또는 고정 취소로 실패합니다. UI 스레드 경계도 고정 실패 종류와 취소를 보존합니다. VM의 치명적 Error를 복구하려고 삼키지 않으며, 해제 API 자체가 실제 자원을 반환했는지 또는 프로세스 중단 시 완전 정리를 보장하는 것은 아닙니다.
- UiAutomation에는 DON'T_SUPPRESS_ACCESSIBILITY_SERVICES를 사용하고 원래 serviceInfo.flags를 복원합니다. 사용자 설정·동의·허용 앱·권한을 변경하지 않습니다.
- 출력은 고정 fixture/단계/사유/진단 enum과 세션 내부 attempt ordinal뿐입니다. 원문·좌표·경로·창 ID·토큰·지문·예외 원문은 출력/파일/DB에 남기지 않습니다. 원시 노드는 즉시 해제하고 비교용 문자열은 지역 RAM에서만 사용합니다. 알려지지 않은 Exception/AssertionError는 최상위 고정 실패로 바꿔 원문을 JUnit에 전달하지 않습니다.

## Windows API 37 실행

개인정보 없는 전용 에뮬레이터를 사용합니다. 프로젝트 루트 PowerShell:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # devices에서 확인한 API37 대상
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

UI에서 서비스 활성화, 수집 동의, host `kr.closeforyou.capturetest` 단독 허용, 분석 시작을 직접 확인합니다. 캡처 동의 OFF/캡처 허용 목록 없음 상태로 두고 기기를 직접 터치하지 않습니다. 다음 **단일 계측 명령**이 네 조건을 각각 실행합니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceProtectionPriorityOptInTest -e f1ProtectionPriority true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

기대 출력: SERVICE_CONNECTION_READY, fixture별 분리된 SAMPLE/ATTEMPT/CASE와 네 EXPECTED_BLOCK, SUMMARY=OBSERVATIONS_COMPLETE 및 OK (1 test). 실패 fixture·정리 실패가 하나라도 있으면 FAILURES입니다. 이 OK는 시험 범위의 보호 우선순위 관측이며 F0 완전성/F1 정확도/TTL/행동 안전성/캡처/OCR 성공이 아닙니다.

## 미검증과 기존 위험

앞선 구현의 로컬 검증은 신규 JVM15개 포함 전체485개(앱396/호스트89) 통과였습니다. P2 최소 수정은 JVM12개를 추가해 최종 조회와 해제 격리의 순수 Kotlin 경계를 검사합니다. Android에서 실제 Binder RESULT 호출 순서·노드 recycle·Activity 종료를 JVM에서 실행하는 시험은 아닙니다. 이번 전체 빌드/테스트 결과는 검증 완료 후 아래에 구분해 기록합니다. Release 재빌드·GitHub Actions·실제 계측 실행 결과를 뜻하지 않습니다.

P2 최종 로컬 검증: 요청한 Gradle 작업 모두 성공 후 양쪽 JVM을 `--rerun`으로 재실행했습니다. 전체497개(앱396/호스트101), 해당 회귀27개(기존15+추가12), 실패·오류·skip0입니다. 앱/호스트 Debug·호스트 AndroidTest APK, 양쪽 debug Lint(이슈0), 기존 패키징 검사와 새 driver/cleanup DEX 정의·운영 앱 격리, `git diff --check` 통과입니다. 최종 BUILD SUCCESSFUL(44초), 경고 출력 없음. 운영 소스·원본 시나리오91개와 기존 fixture/Manifest/ID 리소스 보존을 확인했습니다. 연결 기기가 없어 Android 계측은 실행하지 않았고 커밋·푸시도 하지 않았습니다.

실제 Android 계측은 개발 환경에서 실행하지 않았습니다. 원격 숨김 노드 노출, 실제 순서·필드값, password transformation의 서비스 표현, 이벤트·가림·시간 한도·정리 동작은 API37 실측이 필요합니다. API35 결과도 별도 검증 대상입니다. 처음 미관측 후 재실행 통과한 이전 MINIMAL 결과를 타이밍 원인 확정으로 해석하지 않습니다.

기존 P1 세 건(전체 F0 coverage/변경 탐지, 검사값·salt·token·지문의 동일 시도 결합, 서비스 세션·정책·Job 취소·현재성·TTL)은 미해결입니다. 기존 framework 정리 예외의 원래 오류 가림 P2와 취소 전파도 미해결입니다. 새 시험 역시 별도 샘플과 서비스 시도 사이의 TOCTOU 한계를 갖고 있으므로 원자성 증명에 사용하면 안 됩니다. Scroll 역전 Rect·Window/frame 불일치·TIME_LIMIT·TARGET_MISSING 및 원래14개 UNOBSERVED=14/F1_ACCURACY=NOT_TESTED 상태를 바꾸지 않습니다.
