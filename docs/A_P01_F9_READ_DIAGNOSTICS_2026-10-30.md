# P01: F9 screen null / 표시 재검증 UNAVAILABLE 진단

## 확인된 사실과 미확정 원인

브랜치 `fix/a-samsung-p01-lifetime-20261028`, 기준 HEAD `223d79f03294ef5943ae14dff03c738e22b57073`. 시작 worktree는 clean이었다. P23 및 다른 worktree는 수정하지 않는다. 이번 변경은 로컬 진단 보강이며 commit/push하지 않는다.

사용자 삼성 실측에서 ATTEMPT=4/5/6은 CONTENT/대상 BOUNDS/STATE 재검증 UNAVAILABLE 뒤 표시·음성이 중단됐다. ATTEMPT=7은 C 제거·새 F0·원본 팝업/버튼 비교 뒤 MANUAL_ELIGIBILITY용 screen.read()가 null이었다. **NEW_WINDOW 검사 실패라는 증거가 아니다.**

정적으로 확인한 진단 공백:

- 운영 F9ServiceConnection은 기존 `onRejected(stage)` callback을 연결하지 않았다. 이 callback은 Android 계측 fixture에서만 사용됐다.
- 한 stage에 여러 null/guard 조건이 있었고, 일반 예외·100ms 한도·root/window 해제 실패를 구별할 정보가 부족했다.
- consume가 정상 반환한 뒤 recycle 실패로 healthy=false가 되면 read()는 null이지만 기존 callback은 completed=true라 호출되지 않았다. 후속 read도 계속 null이었다.
- 표시 재검증은 native 전후 확인 실패와 실제 F9 scan/현재성/시간/내용 비교 실패를 UNAVAILABLE 하나로 합쳤다.

위는 코드상 진단 누락이다. **이번 삼성 기기의 실제 null 원인이 오버레이 잔존/포커스 복구/root/tree/시간/정리 중 어느 것인지 아직 확정하지 못했다.** 조건을 완화하거나 기다렸다 재시도하는 기능 수정은 하지 않았다.

## 실제 호출 경로

수동: native C 제거 + 창 부재 확인 → 사용자 접촉 종료 → 새 전체 collector F0 → 동일 팝업·버튼 → executeUser → `readFor(MANUAL_ELIGIBILITY)` → 기존 prepareUser/B → F9 `readFor(PRE_CLICK)` → 기존 press → `readFor(POST_CLICK)`.

자동/page 호출은 AUTO_ELIGIBILITY/PAGE, 표시 전용 재검증은 DISPLAY_REVIEW, 실제 press scan은 PRESS로 구분한다. `F9ScreenPort.readFor()`는 A 내부의 목적 라벨이다. 기존/fake port 기본 구현은 원래 read() 1회를 그대로 호출하고 Android port는 동일 scan에 목적 enum만 전달한다. 추가 수집·getter·OCR·IPC·sleep·시계 조회를 하지 않는다.

F9ReadObservation은 실제 Android scan의 guard/checkpoint/catch/finally에 연결됐다. 첫 실패 stage/detail을 고정 enum으로 남기고 자원 해제를 모두 시도한 뒤 보고한다. 별도 scan의 증거를 합치지 않는다. 기존 onRejected(stage)의 호출 조건은 유지한다. 각 guard와 복합 조건은 원래 short circuit 순서를 보존해 분리했다. 역전 Rect도 기존 DTO 생성 실패와 동일하게 null로 종료하며 보정하지 않는다.

## Debug 로그

기존 `A_ManualPrompt` 태그. 다음은 형식 예시이며 실제 삼성 관측값이 아니다.

```text
A_F9_READ;ATTEMPT=7;PURPOSE=MANUAL_ELIGIBILITY;STAGE=FOCUSED_WINDOW;DETAIL=ACTIVE_NOT_FOCUSED;OUTCOME=REJECTED;RELEASE=NONE;CALLBACK_RETURNED=NO
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=5;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=UNAVAILABLE;POINT=NATIVE_BEFORE;DETAIL=LIVE_SET_UNCONFIRMED
```

### F9 scan 분류

| STAGE | 실제 분기를 가리키는 DETAIL |
|---|---|
| MAIN | MAIN_THREAD_REQUIRED, READER_UNHEALTHY |
| GEOMETRY / WINDOWS | GEOMETRY_UNAVAILABLE, WINDOW_COUNT, NONDEFAULT_DISPLAY |
| ACTIVE_WINDOW | ACTIVE_APPLICATION_UNCONFIRMED(0개/복수 포함), DISPLAY_TARGET_UNCONFIRMED |
| FOCUSED_WINDOW | ACTIVE_NOT_FOCUSED |
| OVERLAY_WINDOW | ACCESSIBILITY_OVERLAY_PRESENT, INPUT_METHOD_PRESENT, OTHER_ACTIVE_OR_FOCUSED |
| APP_COUNT / BACKGROUND_WINDOW | APPLICATION_COUNT, BACKGROUND_LAYER, BACKGROUND_BOUNDS |
| WINDOW_BOUNDS / OCCLUSION | WINDOW_OUTSIDE_FRAME, OCCLUDING_WINDOW |
| ROOT | ROOT_UNAVAILABLE, ROOT_PACKAGE_UNAVAILABLE, ROOT_WINDOW_MISMATCH, ROOT_PACKAGE_MISMATCH, PACKAGE_PROTECTED, APP_NOT_ALLOWED |
| TREE | node/depth/child/text/action 한도, node package/window, 보호 필드/텍스트, 문자열 변경, 자식 누락/변경, background root 누락/불일치 |
| ENDPOINT | END_WINDOWS_CHANGED, END_FRAME_UNAVAILABLE, END_FRAME_CHANGED, END_ROOT_UNAVAILABLE, END_ROOT_WINDOW_MISMATCH, END_ROOT_PACKAGE_MISMATCH |
| TARGET | refresh/owner/text/description/viewId/class/bounds/visible/enabled/clickable/보호 필드/actions/metadata 검사 각각의 TARGET_* 코드 |
| 실제 실패 stage 공통 | TIME_LIMIT, CHECKPOINT_FAILED, CANCELLED, EXCEPTION, NODE_ALIAS, REVERSED_BOUNDS |

OUTCOME은 COMPLETE / IDENTITY_ONLY / REJECTED / CANCELLED / EXCEPTION / CLEANUP_FAILED다. RELEASE는 NONE / NODE / WINDOW / BOTH로 별도 보존한다. 최초 거부 뒤 release도 실패하면 최초 detail을 바꾸지 않고 RELEASE와 CLEANUP_FAILED를 함께 남긴다. 취소가 원래 있었다면 그 취소를 전파하고 CANCELLED를 보존한다. 기존 recycle 예외에 대한 sticky healthy=false 처리는 변경하지 않았다.

IDENTITY_ONLY는 보호/미허용 앱의 내용 없는 이탈 관측이며 full F0/실행 증거가 아니다. COMPLETE는 scan callback이 정상 반환했다는 뜻이며 클릭 승인·닫힘 성공이 아니다. **CALLBACK_RETURNED=NO도 클릭 0회를 뜻하지 않는다.** PRESS callback이 Android 호출 뒤 예외로 끝날 수 있으므로 실제 클릭 여부/결과는 기존 CLICK_ATTEMPT/CLICK_RESULT를 사용한다.

ACCESSIBILITY_OVERLAY_PRESENT는 창 유형만 확인한 것이다. C 잔존인지 TalkBack/다른 소유 창인지 단정하지 않는다. ACTIVE_APPLICATION_UNCONFIRMED 역시 후보 0개와 복수를 추가 조회해 구분하지 않는다. EXCEPTION은 해당 stage의 framework/일반 예외이며 예외 원문/클래스로 원인을 추측하지 않는다.

### 표시 재검증 분류

POINT는 CHECKPOINT / NATIVE_BEFORE / SCREEN / NATIVE_AFTER / COMPARISON / NOT_CHECKED다. 첫 실패 point/detail을 이후 검사·정리 실패로 덮지 않는다.

- CURRENTNESS_FAILED / TIME_LIMIT: 기존 현재성 우선·원래 100ms checkpoint, 단일 기존 시계 조회
- NATIVE_ID_UNAVAILABLE / BEFORE_SET_UNCONFIRMED / LIVE_SET_UNCONFIRMED / NATIVE_ID_CHANGED: 해당 전후 native 확인
- SCREEN_READ_UNAVAILABLE / SCREEN_RESOURCE_FAILURE: F9 scan 반환 또는 해제 건강 상태. 같은 DISPLAY_REVIEW 목적의 A_F9_READ를 함께 확인
- TARGET_EQUALS_NATIVE / WINDOW_LIST_UNCONFIRMED / OVERLAY_WINDOW_UNAVAILABLE / TARGET_WINDOW_UNAVAILABLE / OVERLAY_LAYER / COMPETING_WINDOW / ACTIVE_OR_FOCUS_UNCONFIRMED: 기존 대상 선택 조건
- INCOMPLETE / INVALID_GEOMETRY / PACKAGE_CHANGED / WINDOW_CHANGED / FRAME_CHANGED / NODES_CHANGED / WINDOW_SET_CHANGED / ORIGINAL_WINDOW_UNAVAILABLE / PROTECTED_CONTENT: 실제 immutable 비교 결과
- CANCELLED / EXCEPTION / UNKNOWN: 해당 경계에서만 확인 가능한 실패. 구별 근거 없는 원인은 UNKNOWN 유지

틀린 소유권/불완전 트리/잔존 overlay/포커스 불확실성/보호·동의 실패가 정상화되지는 않는다. UNAVAILABLE/CHANGED는 기존처럼 표시 제거와 음성 정리를 수행한다. SAME도 이전 Runtime/F9 실행 증거를 복원하지 않는다.

## 안전 및 한계

화면 원문·package·좌표·창 ID·token·지문·시각·예외를 새 진단에 저장/출력하지 않는다. scan별 enum/boolean만 보존한다. Debug formatter만 추가하며 Release ManualPromptLog는 계속 null이다. 실제 APK DEX에서 새 prefix 부재를 검사한다.

scan 내부 반복에서는 로그를 쓰지 않고 기존 비교 결과만 메모리에 표시한다. 전체 scan/해제 후 보고한다. offer별 고유 read 코드 최대 24개, 표시 코드 최대 16개이며 반복 코드는 억제된다. 로그 부재를 성공이나 호출 부재로 해석하지 않는다. 진단의 제한된 CPU/메모리 비용이 경계 타이밍에 전혀 영향 없다고 보장하지 않으며, 원래 한도를 늘리지 않는다. Binder/framework를 강제로 중단하는 보장이나 서로 다른 read의 원자성을 추가하지 않는다.

새 F0, 제거 확인, B 승인, 원래 5초 TTL, 동의/앱 허용, F9 최종 전체 검증, 단일 클릭 장부, 실제 팝업 소멸 확인은 유지한다. 자동 실행 OFF·자동 SKIP OFF·F12 반복 OFF, 위험 버튼 거부도 변경하지 않는다. B/C/D, 공통 DTO, Manifest, gradle.properties 및 P23 작업물은 변경하지 않는다.

## 검증 범위

신규 JVM은 실제 scan의 공유 F9ReadObservation.run/guard/checkpoint/release/report, 실제 표시 probe/대상 선택/비교/소유권 callback/checkpoint/예외 경계를 검증한다. 실제 B/F9 session 회귀에서 manual/automatic/page/클릭 전후 목적 라벨이 정확하고 읽기/클릭 횟수가 늘지 않음을 확인한다. cancellation/최초 실패+여러 release 실패/정상 반환 후 release 실패/로그 실패/비정상 reader/표시의 전후 소유권·시간·내용 실패를 구분한다.

JVM은 실제 Android getter/Binder의 각 실패를 재현한 것이 아니다. Android scan 분기 연결은 소스 검토와 AndroidTest 컴파일로 대조하며 삼성 실측은 별도로 필요하다. 최종 전체 JVM·Debug/Release 빌드·Lint 결과는 완료 보고에 기록한다.

최종 로컬 검증 결과:

- 신규 회귀 27개(Read 진단 13개 + 표시 진단 12개 + 실제 session 목적 전달 2개), 관련 대상 suite 128개 통과
- 앱 전체 JVM Debug 1,160개 / Release 1,145개: 실패·오류·skip 0. variant 간 공통 테스트를 중복 포함한다
- 호스트 55개 / 합성 앱 26개 통과 결과는 이번 Gradle에서 UP-TO-DATE 재사용
- Debug/Release APK, AndroidTest APK 컴파일·빌드, 합성 앱 Debug 빌드 통과
- 앱 Debug/Release 및 호스트 Lint 이슈 0, APK/DEX 격리 통과
- A_F9_READ/A_MANUAL_DISPLAY_REVIEW/A_MANUAL_ACTION prefix Debug 존재 / Release 부재
- diff 및 신규 파일 공백 검사 통과. P23 포함 다른 worktree 9개 보존
- adb 연결 기기 없음. 삼성/LG/에뮬레이터 계측 미실행

## 삼성 재시험

수정본 적용 소스에서:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

기존 사용자 UI 동의·접근성·시험 앱 허용을 유지한다. 별도 PowerShell 창에 같은 adb/serial을 설정하고:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF를 구분해 기록한다. 표시가 사라지면 DISPLAY_REVIEW의 POINT/DETAIL과 같은 목적 A_F9_READ STAGE/DETAIL/RELEASE, 첫 A_MANUAL_CLEANUP을 함께 확인한다. 수동 선택 시에는 SESSION_ENTRY 뒤 **PURPOSE=MANUAL_ELIGIBILITY** 행을 우선 확인한다. 이후 PRE_CLICK/PRESS/POST_CLICK, CLICK_ATTEMPT/CLICK_RESULT를 구분한다. 새 로그의 종류/상태를 보기 위해 원본 버튼을 adb로 누르거나 동의·보호를 우회하지 않는다.

실제 popup 소멸과 CLOSED_CONFIRMED 없이는 닫힘 성공으로 기록하지 않는다. 이번 환경의 삼성/LG/에뮬레이터 검증은 미실행이며, 원본 14개 UNOBSERVED·Scroll/Window 문제 해결을 주장하지 않는다.
