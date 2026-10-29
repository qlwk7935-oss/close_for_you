# 삼성 P01: 가림 창과 반복 안내의 원인 구분

기준 worktree `/workspace/a-samsung-p01`, 브랜치 `fix/a-samsung-p01-lifetime-20261028`, HEAD `c4aeec4d3462032f91c1a94344a1e81a597c4aa0`. 시작 상태 clean. 이 작업은 로컬 진단 보강이며 commit/push/merge하지 않는다. P23 및 다른 worktree는 보존한다.

## 증거 수준

사용자 삼성 실측 ATTEMPT 2/4에서는 native-before의 소유 창 조회가 ONE/COMPLETE/OWNED_CANDIDATE/MATCH였다. 이후 같은 표시 재검증의 `OCCLUSION / OCCLUDING_WINDOW` 거부 → SCREEN_READ_UNAVAILABLE → 표시·음성 취소가 확인됐다. 이번에는 cleanup이 REMOVAL_RECEIPT → OVERLAY_REMOVED로 완료됐고 CLEANUP_FAILED가 보고되지 않았다. 이전 retired-ID 실패를 이번 실패 원인으로 재사용하지 않는다.

OCCLUDING_WINDOW의 코드상 조건은 다음과 같다. Android의 한 실제 window 목록을 기존 순서로 any 순회해 **처음 true인 창**에서 중단한다.

- 대상 창 ID와 다르고, 표시 재검증에 주어진 정확한 native C ID와 다르다.
- TYPE_APPLICATION이 아니다.
- layer가 대상 이상이다.
- raw bounds가 대상과 양의 면적으로 겹친다. 경계 접촉만으로는 true가 아니다.
- 기존의 제한된 passiveSystemBar 예외가 적용되지 않았다.

따라서 코드상 첫 거부 창은 위 관계를 만족한다. 정확한 C 창은 ID로 제외된다. 그러나 다른 시점의 native-before MATCH만으로 이 scan의 원자적 동일성이나 이벤트 출처를 인증하지 않는다. 이전 대상 선택 단계가 IME/다른 accessibility overlay/경쟁 active·focused 창을 거부한다는 조건도 그대로 유지한다.

기존 passiveSystemBar 예외는 `targetPath == null && activeBounds == frame`인 읽기에서만 검사한다. **bounded Dialog를 표시 재검증할 때는 activeBounds가 frame과 다르면 해당 helper를 호출하지 않는다.** 이를 이번 삼성 기기의 실제 bar 정체나 잘못된 가림으로 확정하지 않는다. SYSTEM이라도 알림·다른 시스템 창일 수 있고, OTHER는 알려지지 않은 비앱 창 범주일 뿐이다. inactive라는 사실만으로 가림이 없다고 인정하지 않는다.

근본 원인의 실제 창 유형/형태가 아직 관측되지 않았으므로 **허용 예외나 지연·재시도·timeout 조정은 추가하지 않았다.** 코드상 명백한 판정 오류가 확인됐다고 주장하지 않는다.

## 변경 및 Debug 로그

실제 scan이 공유 `f9OccludingWindow`와 `f9PassiveSystemBar`를 호출한다. 기존 조건과 getter short circuit 순서를 보존하고, 이미 평가한 scalar 값만 고정 코드로 변환한다. 기존 root package/window owner 메타데이터 검사도 그대로다. 추가 getter/root traversal/수집/OCR/IPC/시계 조회가 없다.

첫 거부 창에 대해서만 기존 A_F9_READ 행에 다음 필드를 추가한다. 예시는 **실측 결과가 아닌 형식 예시**다.

```text
A_F9_READ;ATTEMPT=2;PURPOSE=DISPLAY_REVIEW;STAGE=OCCLUSION;DETAIL=OCCLUDING_WINDOW;OUTCOME=REJECTED;RELEASE=NONE;CALLBACK_RETURNED=NO;OCCLUDER_TYPE=SYSTEM;LAYER=ABOVE;OVERLAP=PARTIAL;PASSIVE_BAR=NOT_CHECKED_BOUNDED_TARGET
```

- OCCLUDER_TYPE: 기존 WindowType enum. SYSTEM / OTHER / INPUT_METHOD / ACCESSIBILITY_OVERLAY 등. APPLICATION은 해당 predicate에서 제외되므로 거부 창 코드로 기록되지 않는다. 그 밖의 기존 거부 경계를 우회하지 않는다.
- LAYER: EQUAL / ABOVE. 낮은 layer는 기존처럼 거부 후보가 아니다.
- OVERLAP: EQUAL / COVERS_TARGET / WITHIN_TARGET / PARTIAL. target 기준 포함 관계이며 원시 Rect는 기록하지 않는다.
- PASSIVE_BAR: NOT_CHECKED_TARGET_PATH / NOT_CHECKED_BOUNDED_TARGET는 외부 gate에서 검사하지 않았다는 뜻이다. 검사를 강제 실행해 true/false를 채워 넣지 않는다.
- 실제 helper 내부 실패는 NOT_SYSTEM / ACTIVE / FOCUSED / WIDTH / HEIGHT / EDGE / ROOT_UNAVAILABLE / OWNER_UNCONFIRMED / ROOT_WINDOW_MISMATCH. 내부에서도 후속 getter를 강제로 호출하지 않는다.
- PASSED는 기존 full-frame passive 예외가 통과했음을 뜻한다. 이 창은 가림 거부가 아니므로 거부 행으로 출력하지 않는다. 그 뒤 다른 창이 막으면 그 다른 창을 보고한다.
- getter 예외/취소가 코드 생성 전에 발생하면 occlusion 부가 필드는 없으며 기존 EXCEPTION/CANCELLED 진단을 사용한다. 첫 가림 코드와 최초 실패를 후속 release 실패가 덮지 않는다.

필드는 기존 scan 보고에 함께 포함된다. 별도 창별 로그나 반복 polling 로그는 없다. 기존 scan/offer별 개수 제한과 중복 억제를 유지한다. Release의 로그 팩토리는 null이며 formatter는 Debug source set에만 있다. enum 계산·유한 객체 할당의 CPU/메모리 비용과 Debug 출력의 타이밍 영향이 0이라고 주장하지 않는다.

## 외부 이벤트와 표시 재검증

`onAccessibilityEvent` → 기존 `shouldIgnore()` 소유권 판정 → `reviewDisplayedEvent()` 순서다. 소유 이벤트로 확인하지 못한 CONTENT/STATE도 무조건 target 변경으로 단정하지 않고, 다음 기존 조건에서 표시 전용 재검증을 한다.

- 현재 서비스/HEALTH 정상, 사용자 상호작용 중 아님, 선택 대기 중 아님
- 실제 C가 SHOWING이고 native 표시 수명 유효
- 원래 detection에 NEW_WINDOW 포함
- CONTENT 또는 WINDOW_STATE, 또는 원래 대상 ID의 순수 BOUNDS

먼저 Runtime/F9 실행 증거를 무효화한 후 native-before → 전체 대상 창/배경 트리·frame·보호/endpoint 비교 → native-after를 검사한다. SAME일 때 표시만 유지하고 실행 증거를 복원하지 않는다. CHANGED/UNAVAILABLE이면 C와 TTS를 정리한다. 조건에 맞지 않으면 기존 일반 무효화 경로다. 다른 패키지 이벤트라는 이유만으로 안전하게 무시하지 않는다.

ATTEMPT 1/3의 다른 패키지 STATE 뒤 제거는 제공된 로그만으로 그 재검증의 내부 실패까지 구별할 수 없다. TYPE/SYSTEM/OTHER도 실제 이벤트 발생 앱이나 C 자체 이벤트임을 인증하지 않는다.

## 반복 표시의 실제 코드 경로

`nativeProcessed()` 또는 `processed(receipt)`에서 실제 제거·전체 창 부재 확인 완료 → `reviewAfterCleanup()` → 같은 permit당 한 번만 review 요청 → 서비스 `observeAfterCleanup()`에서 기존 invalidate/clear 및 debounce → 새 F0/F1/evidence → `F9ServiceConnection.collected()` → `manual.offer()`.

offer는 기존 closed/HEALTH/completedKey/cleanup/showing/selected 검사를 통과하고 popup scope가 있어야 한다. `ChoiceReviewBudget.admit()`는 기존 app/window/structure/content의 정확한 비교 키를 쓴다. 같은 key에는 최초 + recovery 1회만 허용하며, key가 달라지면 다음 identity 예산을 시작한다. 이는 토큰의 시간/version만 바뀐 것과 다르다. 서로 다른 identity가 계속 번갈아 관측되면 기존 정책상 전체 서비스에 대한 표시 횟수 상한은 없다. 이 관측을 막기 위해 새 팝업까지 일괄 억제하는 정책 변경은 하지 않았다.

PREPARATION/이전 evidence를 재사용하는 것이 아니다. offer 후 C Flow의 extractor는 기존 evidence/후보 연결을 얻은 다음 A presentation gate의 실제 전체 확인·현재성 검사를 통과해야 반환한다. 그 다음 B DecisionPopupClassifier → C ChoicePromptPlanner → 기존 A presenter guards → 실제 C present를 거친다. gate가 실패하면 표시하지 않는다.

쿠폰 안내와 '닫기 선택'은 서로 배타적인 결과가 아니다. 기존 C planner는 COUPON에서도 적격 닫기 후보가 있으면 PRESS_CANDIDATE를 포함한다. 원문이 없는 이번 로그로 B가 분류를 바꿨다거나 실제 팝업이 교체됐다고 결론 내리지 않는다.

새 Debug 행은 실제 budget 비교 결과와 실제 생성된 immutable prompt만 기록한다:

```text
A_MANUAL_REVIEW;ATTEMPT=3;POINT=ADMITTED;ADMISSION=IDENTITY_CHANGED;IDENTITY_CHANGE=CONTENT_CHANGED;SUMMARY=NOT_CHECKED;PRESS_OFFERED=NOT_CHECKED
A_MANUAL_REVIEW;ATTEMPT=3;POINT=SHOWN;ADMISSION=IDENTITY_CHANGED;IDENTITY_CHANGE=CONTENT_CHANGED;SUMMARY=LIKELY_COUPON;PRESS_OFFERED=YES
```

IDENTITY_CHANGE는 INITIAL / UNCHANGED / APP_CHANGED / WINDOW_CHANGED / STRUCTURE_CHANGED / CONTENT_CHANGED / STRUCTURE_AND_CONTENT_CHANGED. 여러 항목이 다르면 app → window → 두 digest/개별 digest 순서로 분류한다. 원본 문자열·ID·digest는 추가 보존/출력하지 않는다. NOT_CHECKED/UNKNOWN은 해당 관계를 연결할 근거가 없음을 뜻한다.

POINT는 ADMITTED / PRESENT_REQUESTED / SHOWN이다. SUMMARY는 C의 기존 PromptSummary enum이며 소리의 실제 재생 완료를 뜻하지 않는다. PRESS_OFFERED는 C prompt에 PRESS_CANDIDATE가 있다는 boolean이며 B 실행 승인이나 F9 클릭 허가가 아니다. prompt token이 현재 offer 원본과 다르면 admission 연결은 NOT_CHECKED/UNKNOWN으로 보고하며 정책 판정을 바꾸지 않는다. 실제 B/C 소스와 공통 계약은 수정하지 않았다.

## 유지한 안전 조건과 회귀

가림·입력창·경쟁 창을 무시하는 예외 없음. full F0·배경 보호·불완전 geometry 거부·100ms 한도·5초 실행 TTL·30초 표시 lease·native detach/전체 창 부재·sticky HEALTH·B 승인·새 수동 F0·F9 최종 검사·단일 클릭·실제 원본 소멸 확인은 그대로다. 자동 실행·SKIP·F12 OFF와 위험 버튼 거부를 유지한다. 실제 클릭 코드/Eligibility/수동 refresh 및 cleanup barrier를 수정하지 않았다.

신규 JVM은 실제 scan의 공유 occlusion/passive callback 경로, short circuit/getter 호출 횟수·순서, 원래 예외·취소/해제/보고 경계, 최초 거부 창 고정, budget/동일 offer 연결 및 C planner의 실제 immutable prompt를 검사한다. Android getter/native/TTS는 실제 기기에서 실행한 것이 아니다. 초기 후보/그림자·시스템 창의 실제 형태, 순간 표본 사이 변화 및 Android 창 목록의 원자성은 JVM으로 보장하지 않는다.

## 삼성 재시험

이번 작업은 push하지 않는다. 변경본을 전달받은 Windows 소스에서:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

기존 사용자 UI 접근성/수집 동의/시험 앱 허용을 확인한다. 설정을 adb로 강제하지 않는다. 다른 PowerShell 창에 같은 adb/serial을 설정하고:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF에서 첫 SHOWN → 첫 DISPLAY_REVIEW의 같은 scan A_F9_READ 가림 필드 → 표시 제거 사유 → cleanup 완료 → 다음 ADMITTED/SHOWN을 함께 확인한다. 로그는 작업별 순서로 보되 서로 다른 scan이나 서비스 세대의 증거를 결합하지 않는다.

SYSTEM + NOT_CHECKED_BOUNDED_TARGET만으로 status/navigation bar라고 결론 내리지 않는다. 외부 이벤트의 원인과 그 scan에서 발견한 가림 창이 동일하다고 주장하지 않는다. 표시가 유지되면 6초 후 명시적 선택의 새 F0/B/F9 및 실제 원본 소멸도 별도 확인한다. 표시 제거 자체를 닫힘 성공으로 기록하지 않는다. 이 환경의 삼성/LG/에뮬레이터 실측은 미실행이다. 일반 logcat 전체나 타 앱 원문은 수집/공유하지 않는다.

## 최종 로컬 검증 결과

- 신규 JVM 22개(가림/passive/scan 보고 15개 + review/admission/실제 C prompt 코드 7개), Debug/Release 모두 통과.
- 앱 전체 JVM Debug 1,204개 / Release 1,189개, 실패·오류·skip 0. variant 공통 테스트를 중복 포함하므로 고유 테스트 수로 합산하지 않는다.
- 호스트 55개 / 합성 앱 26개는 기존 통과 결과를 이번 Gradle에서 UP-TO-DATE 재사용했다.
- 앱 Debug/Release APK, AndroidTest APK 컴파일·빌드 성공. 합성 앱 Debug 빌드는 UP-TO-DATE 재사용. 전체 Gradle BUILD SUCCESSFUL, 4분 9초.
- 앱 Debug/Release 및 호스트 Lint 이슈 0. 기존 C 테스트의 ExperimentalCoroutinesApi opt-in 경고는 해당 팀 소스를 변경하지 않고 유지했다.
- APK/DEX 격리 통과. A_F9_READ/A_MANUAL_REVIEW/OCCLUDER_TYPE=/PASSIVE_BAR=/A_MANUAL_CLEANUP_EVENT prefix는 Debug에 있고 Release에는 없음.
- diff 및 신규 파일 공백 검사 통과. source 변경 9개(기존 6개 + 신규 테스트 2개/문서 1개), staged 없음.
- 실제 press 및 root/tree/endpoint/자원 해제 코드, F9 실행·Eligibility·수동 fresh F0, cleanup barrier/서비스/collector/Manifest/gradle.properties가 기존 HEAD와 동일함을 확인했다. B/C/D 및 공통 계약 변경 없음.
- P23 포함 다른 worktree 9개의 HEAD/status/기존 미커밋 파일 해시 동일. commit/push/merge/PR 변경 없음.
- adb 연결 기기 없음. 이번 버전의 삼성/LG/에뮬레이터 계측 미실행. 실제 창 정체, 오분류 여부, 조기 제거·반복 안내 해결은 아직 입증하지 못했다.
