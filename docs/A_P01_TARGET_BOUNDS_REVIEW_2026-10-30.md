# 삼성 P01: 대상 창 BOUNDS 재검증과 선택 없는 native cleanup

## 증거 수준

브랜치 `fix/a-samsung-p01-lifetime-20261028`, 기준 `36ab2c59740302d20226ea8dd98e261328f95989`. 시작 worktree는 clean이다. P23 및 다른 worktree는 수정하지 않는다. commit/push하지 않는다.

사용자 삼성 로그는 SHOWN 직후 대상 창 ID에 대한 순수 BOUNDS 이벤트가 NOT_OWNED로 분류되고 WINDOW_SET_INVALIDATED → dismiss → CLEANUP_FAILED로 이어졌음을 입증한다. 이벤트 ID가 원본 대상과 같다는 것은 C 소유 이벤트라는 뜻이 아니다. **C 추가로 발생한 알림인지 실제 대상 팝업이 바뀐 것인지 로그만으로는 확정할 수 없다.**

직접 제거 원인은 코드로 확인했다. 기존 `manualDisplayReviewEvent()`는 CONTENT/STATE만 받았으므로 `TYPE_WINDOWS_CHANGED / WINDOWS_CHANGE_BOUNDS`는 이미 존재하는 전체 화면 재검증 경계를 건너뛰고 일반 무효화로 들어갔다.

cleanup에 대해서는 제공 로그에 `A_MANUAL_CLEANUP;PHASE=FIRST;SITE=...;BARRIER=...`가 없다. **이번 삼성 실패의 첫 사유를 추정으로 확정하지 않는다.** 코드/JVM에서는 선택 없이 제거된 C 창의 지연 STATE/CONTENT/포커스/BOUNDS가 기존 선택 전용 retirement proof를 통과하지 못해 RETIRED_ID_EVENT를 발생시키는 경로를 재현했다. 이 재현 가능한 문제만 수정했다. 실제 remove 실패·ID 불확실성·detach 누락·창 조회/해제 실패는 계속 차단한다.

## 변경

### 1. 순수 대상 BOUNDS는 재검증 요청

실제 driver 경로:

`onAccessibilityEvent` → 기존 shouldIgnore(소유권) → `reviewDisplayedEvent(type, changes, eventWindow)` → `manualDisplayReviewEvent` → 기존 실행 증거 무효화 → `reviewManualDisplay` → 현재 native 소유권 확인 → 기존 `AndroidF9ScreenPort.verifyDisplayedPopup` → native 소유권 재확인 → 유지/제거.

추가 대상은 **원본 대상 ID와 같은 창의 순수 WINDOWS_CHANGE_BOUNDS**뿐이다. 다른 창·누락 ID·ADDED/REMOVED/혼합/불명 마스크는 기존 일반 무효화로 처리한다. 이벤트 원인이나 패키지 불일치만으로 C 이벤트라고 재분류하지 않는다.

기존 재검증은 같은 C native generation/소유권을 전후 확인하고, 원본 대상의 전체 트리/창 집합/범위/frame/package/보호 내용을 현재 값과 비교한다. target의 active/focused만 C로 이동할 수 있다는 기존 표시 전용 규칙을 유지한다. 실제 bounds·본문·버튼·frame·다른 창 변화는 CHANGED, 불완전/소유권/geometry/조회·시간 불확실성은 UNAVAILABLE로 제거한다. clamp·좌표 보정·허용 오차·timeout 증가는 없다.

SAME일 때 C 표시와 진행 중 안내만 유지한다. Runtime/F9의 오래된 실행 증거는 먼저 무효화하며 복구하지 않는다. 원본 캡처 시각, 5초 실행 TTL, 30초 표시 lease를 갱신하지 않는다. 사용자 선택 후 새 F0/B/F9가 여전히 필요하다. 순간 표본의 동일성은 이벤트의 실제 원인이나 미관측 ABA/지속 불변성을 증명하지 않는다.

### 2. cleanup 대기와 선택 의도를 분리

기존 `compatibleRetirementEvent()`의 선택 의도 보존 조건은 바꾸지 않았다. 새 `nativeCleanupRetirementEvent()` / 공유 `inspectNativeCleanupRetirement()`는 선택 없이 무효화로 제거된 C에도 적용되는 **제거 대기 전용**이다.

- 이미 cleanup pending, C HIDDEN, 현재 서비스의 exact native 객체/owner/generation/retirement ID 등록이 필요하다.
- 이벤트 ID와 retired native ID 일치, before 집합에는 없었으며 원본 대상과 구분돼야 한다.
- 자체 package를 확인한다. package 없는 WINDOWS_CHANGED는 기존처럼 실제 native 제거 완료와 전체 창 부재까지 먼저 증명된 경우만 통과한다. 타인 package·불명확한 CLICK/CONTENT는 허용하지 않는다.
- 기존 제한된 비구조적 이벤트만 받는다. ADDED/혼합 REMOVED/터치/스크롤/불명 이벤트는 제외한다.
- 서비스는 이 판정을 barrier에만 전달한다. 일반 이벤트 처리는 실행하고, 이 판정을 선택 보존/shouldIgnore 입력으로 넘기지 않는다.

WAIT_ONLY는 실제 removal 영수증/완료/permit이 아니다. 동일 native의 remove 성공 반환 + 실제 detach + HIDDEN + 전체 창 ABSENT + 기존 5초 cleanup deadline이 모두 확인되기 전에는 새 F0/다음 표시/클릭으로 진행하지 않는다. 이미 실패한 barrier와 HEALTH를 초기화하지 않으며 클릭 권한을 만들지 않는다.

### 3. 원인 로그

기존 Debug 태그 `A_ManualPrompt`에 다음 고정 코드가 추가된다. 예시는 실측 결과가 아니다.

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=SAME
A_MANUAL_RETIREMENT;ATTEMPT=1;EVENT=WINDOW_STATE;CHECK=CLEANUP_WAIT;RESULT=WAIT_ONLY
```

DISPLAY_REVIEW는 SAME/CHANGED/UNAVAILABLE을 cleanup 전에 기록하므로 제거 과정에서 예외가 나도 재검증 결과가 사라지지 않는다. 검증/취소 예외는 UNAVAILABLE로 진단하고 원래 예외는 보존한다. 로그 출력 실패는 판정을 바꾸지 않는다. offer별 유한 enum 조합 최대 16개로 제한한다. Release 팩토리는 null이며 formatter는 Debug에만 있다.

기존 `A_MANUAL_CLEANUP` 첫 실패 journal은 서비스 수명 동안 reset하지 않는다. 새로 소유 창 reader의 기존 onUnhealthy 경계에도 `SITE=OWNED_WINDOW_READ / ISSUE=READ_OR_RELEASE_FAILED`를 기록하고 원래 HEALTH 실패를 그대로 유지한다. 그 callback은 조회/해제 실패를 합쳐 보고하므로 둘 중 하나라고 추정해 세분화하지 않는다.

삼성 재실측에서 반드시 보존할 첫 행과 구분:

- PHASE=FIRST / SITE / ISSUE / BARRIER / NATIVE: 실제 최초 실패
- FIRST_SITE / FIRST_ISSUE / FIRST_BARRIER / FIRST_NATIVE: 뒤의 CHAIN에서도 같은 최초 원인
- OVERLAY_REMOVE/REMOVE_FAILED_STATE: 실제 C remove 실패 상태
- BARRIER/RETIRED_ID_EVENT: retiring ID의 허용되지 않은 이벤트
- BARRIER/DEADLINE, WAITING_ID/WAITING_NATIVE_COMPLETION/WAITING_WINDOW_ABSENCE: 완료되지 않은 단계
- OWNED_WINDOW_READ/READ_OR_RELEASE_FAILED: 소유 창 조회/해제 실패에 의한 HEALTH
- TTS_STOP, CALLBACK_DISMISSED, GUARDED_PRESENTER, EVENT_HANDLER 등의 기존 고정 site도 그대로 유지

원문·package·창 ID·좌표·token·지문·시각·예외 원문은 새 진단에 저장/출력하지 않는다.

## 회귀와 로컬 검증

신규 `ManualBoundsCleanupTest` 15개와 실제 C Flow 회귀 2개를 추가했다. 실제 Android와 공유하는 route/review/continuity/native/barrier/reader/trace 함수를 사용한다. 실제 B/C Flow/guard를 통과하는 표시 유지·제거 회귀도 포함한다. Android 창·노드·TTS·클릭은 가짜 port이며 삼성폰 실측이 아니다.

검증 범위: 순수 대상 BOUNDS와 혼합/다른 창 구분, raw bounds/트리/frame/다른 창 변경, 불완전·부재·잘못된 geometry 거부, 표시만 유지하고 실행 증거 폐기, 실제 C Flow/TTS 정리 미호출, 변경 후 오래된 콜백 거부, 선택 없는 removal 지연 이벤트, native detach/부재 전 permit 0개, deadline 불변, 타인/불명 소유권·재사용 ID·실제 remove 실패 차단, 첫 cleanup 실패 보존, 독립 release 시도, 원래 취소/검사 예외 보존, 진단 예외 격리.

초기 신규 테스트 2건은 DTO가 금지하는 역전 Rect를 생성한 fixture와 기존의 고정 CollectionCleanupFailure 포장을 원본 remove 예외로 잘못 기대해 실패했다. 각각 양수 조건을 위반하는 0 높이 DTO와 기존 fail-closed 포장 계약을 검사하도록 테스트만 수정했다. 운영 geometry·예외·성공 조건은 통과 목적으로 변경하지 않았다. 최종 대상/전체 JVM·빌드 결과는 완료 보고에 기록한다.

최종 로컬 검증:

- 신규 회귀 17개(새 파일 15개 + 실제 C Flow 2개), 대상 102개 통과
- 앱 전체 JVM Debug 1,133개 / Release 1,118개: 실패·오류·skip 0. variant 간 공통 테스트를 중복 포함한다
- 호스트 55개 / 합성 앱 26개는 기존 통과 결과를 이번 Gradle에서 UP-TO-DATE 재사용
- Debug/Release APK, AndroidTest APK, 합성 앱 Debug 빌드 통과
- 앱 Debug/Release 및 호스트 Lint 이슈 0, APK/DEX 격리 통과
- A_MANUAL_DISPLAY_REVIEW/A_MANUAL_RETIREMENT/A_MANUAL_ACTION prefix는 Debug에 있고 Release에는 없음
- diff 및 신규 파일 공백 검사 통과. 수동 Eligibility/새 F0/F9 실행 코드는 HEAD와 동일
- P23 포함 다른 worktree 9개의 HEAD/status/기존 수정 해시 동일
- adb 연결 기기 없음. 삼성/LG/에뮬레이터 계측 미실행

## 삼성 재시험

수정본을 적용한 Windows 소스에서:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

기존 사용자 UI 동의/접근성 활성화/시험 앱 허용을 유지한다. 다른 PowerShell 창에 같은 adb/serial을 설정하고:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF 각각 SHOWN 후 대상 BOUNDS에 DISPLAY_REVIEW가 발생하는지 확인한다. SAME이라면 재표시 없이 안내 완료와 6초 이상 유지돼야 하며, 사용자 명시적 닫기 선택 후 OVERLAY_REMOVAL → 새 F0 → B → 수동 ELIGIBILITY → F9 최종 검사 → 원본 소멸을 따로 확인한다. C 제거만으로 닫힘 성공을 기록하지 않는다.

CHANGED/UNAVAILABLE, 외부 터치·스크롤·동의 철회·팝업 교체·위험 대상에서는 기존 제거/클릭 0회 보호를 확인한다. CLEANUP_FAILED가 나오면 POINT 한 줄만이 아니라 **그 전에 나온 PHASE=FIRST 상세 행과 후속 CHAIN 전체**를 함께 확인한다. 타 앱 원문이 포함될 수 있는 일반 logcat 전체는 수집/공유하지 않는다. 이번 환경에서 삼성폰·에뮬레이터 실행은 미실행이며 성공을 주장하지 않는다.
