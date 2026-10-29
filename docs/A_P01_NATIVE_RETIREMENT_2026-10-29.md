# 삼성 P01 수동 선택: native 창 제거 대기와 지연 이벤트

## 근거와 범위

작업 브랜치는 `fix/a-samsung-p01-lifetime-20261028`, 수정 전 HEAD는 `98afa5a25dd55f0ff83e64345dcd7a9c99b12cb2`다. P23 및 다른 worktree는 변경하지 않는다.

사용자 삼성폰 ATTEMPT=4에서는 선택창 유지와 음성 완료 후 CHOICE_ACCEPTED가 관측됐지만, OVERLAY_REMOVAL 대기에서 `BARRIER_REJECTED / RETIRED_ID_EVENT / NATIVE=REGISTERED`로 종료됐다. 새 F0/B/F9 클릭 단계는 도달하지 못했다. `MANUAL_END=COMPLETED / UNVERIFIED_TARGET / F9=NONE`는 거부 결과 처리가 완료됐다는 뜻이며 원본 팝업 닫힘 성공이 아니다.

`OverlayCleanupBarrier.observeWindowEvent()`는 서비스의 일반 무효화보다 먼저 실행된다. 제거 중인 ID에 대한 순수 REMOVED는 처리 영수증을 만들고, 제한된 LAYER/이미 detach된 CONTENT는 대기하지만 그 외 이벤트는 RETIRED_ID_EVENT로 barrier를 영구 실패시킨다. C는 removeView 전에 retirement를 등록하고 remove 반환과 실제 onViewDetachedFromWindow를 각각 통지한다. 이에 따라 remove 반환 이후 도착한 C의 CLICK/포커스/상태 이벤트가 native 제거 확인보다 먼저 barrier에 들어갈 수 있다. barrier만 고쳐도 일반 서비스 무효화에서 선택 의도가 다시 취소될 수 있어 두 경계를 함께 수정했다.

사용자 로그에는 실패를 일으킨 이벤트 종류/패키지/발생 시각이 없다. **실제 원인이 CLICK이었다거나 실제 native detach가 이미 완료됐다고 확정하지 않는다.** REGISTERED는 마지막 처리한 native 진행 상태다. 이번 수정은 코드로 재현 가능한 지연 이벤트 경로를 처리하며 삼성폰 해결 여부는 미검증이다.

## 구현

`ManualPopupConnection.compatibleRetirementEvent()` → 공유 `inspectManualRetirementEvent()` → `F9ServiceConnection.observeEvent()` → `OverlayCleanupBarrier.observeWindowEvent()` 순서로 한 번 판정한다. 같은 primitive 판정을 서비스의 일반 무효화에도 전달한다.

호환 판정에는 다음 조건이 모두 필요하다.

- 현재 서비스의 건강한, 명시적으로 접수된 선택이며 원본 token과 일치
- C 상태 HIDDEN, 동일 native 객체의 owner/generation/retirement ID 등록 확인
- event ID가 그 retirement ID와 일치하며 원본 대상 ID와 구분되고 before 집합에 없었음
- 같은 표시 세대의 시작 이후이고 미래가 아닌 event 시각, 기존 30초 표시 수명 이내
- 자체 package 확인. 예외적으로 package 없는 WINDOWS_CHANGED는 **동일 native 제거 완료와 독립 전체 창 목록의 부재까지** 확인된 경우만 허용. 타인 package와 package 없는 CLICK/CONTENT는 허용하지 않음
- 이미 cleanup이 끝났다면 동일 native 제거 완료와 현재 전체 창 목록 ABSENT를 다시 확인

CLICK, 포커스, CONTENT, WINDOW_STATE 및 비구조적 WINDOWS_CHANGED 마스크에 한정한다. ADDED, REMOVED 혼합, 알 수 없는 마스크, 터치·스크롤·텍스트 편집은 이 경로에 포함하지 않는다. 순수 REMOVED의 기존 처리는 유지한다.

호환 이벤트는 **선택 의도만 보존**한다. 기존 Runtime/F9 실행 증거는 일반 서비스 무효화로 폐기하며 해당 이벤트를 새 F0 증거로 사용하지 않는다. 호환 판정 자체는 receipt/permit을 만들지 않고, detach를 흉내 내거나 timeout을 갱신하지 않는다. cleanup 완료 전에는 모든 새 수집/실행이 막힌다.

실제 다음 단계 허용 조건은 변경하지 않았다: 같은 native 객체의 remove 반환 성공 + HIDDEN + 실제 detach + 동일 owner/generation/ID + 전체 창 목록 ABSENT + 원래 5초 제거 제한. ID가 여전히 존재하거나 재사용됐거나 조회/해제가 불명확하면 허용하지 않는다. 유효한 제거 후에만 `ManualChoiceRefresh.afterRemoval()`의 **새 F0 → 원본 팝업/대상 비교 → B USER_CHOICE 재판정 → F9 클릭 직전 검증 → 최대 1회 클릭 → 닫힘 확인**으로 진행한다.

F0 보호/100ms 예산, 새 실행 증거의 5초 TTL, 결제·구매·동의·권한·구독·삭제 등 위험 대상 거부, 자동 실행 기능 OFF, 좌표 클릭/재시도 금지는 유지한다. C 소스, B 정책, collector, F9ServiceSession/AutoCloser 실행 코드는 변경하지 않았다.

## 진단

기존 Debug 태그 `A_ManualPrompt`에 offer당 최대 16개 고유 enum 조합을 추가한다. 예시는 실제 관측값이 아니다.

```text
A_MANUAL_RETIREMENT;ATTEMPT=4;EVENT=CLICK;CHECK=COMPATIBLE;RESULT=INTENT_ONLY
```

CHECK는 EVENT_TYPE / CHOICE / DISPLAY / PACKAGE / TIME / NATIVE / ABSENCE / READ_FAILED / COMPATIBLE이다. COMPATIBLE은 제거 성공 또는 클릭 승인 로그가 아니다. 원문·좌표·package·ID·token·지문·시각·예외 원문은 기록하지 않는다. 로그 오류는 판단을 바꾸지 않으며 Release 팩토리는 기존처럼 null이다.

## 회귀 및 검증

신규 `ManualRetirementFlowTest` 20개는 실제 공유 classifier, native lifetime, cleanup barrier, 표시 수명, 선택 무효화, ManualChoiceRefresh, 실제 B 정책/F9 coordinator를 사용한다. Android reader/버튼 포트/native callback은 가짜이며 물리 클릭이나 실제 framework detach를 JVM에서 검증했다고 주장하지 않는다.

주요 경우: remove 반환 후 CLICK 도착 → detach 전 새 F0/클릭 0회 → 실제 detach+ABSENT 뒤 새 F0/B/F9 및 닫힘 확인 1회; 중복 선택; detach 없는 ABSENT; PRESENT/UNKNOWN/조회 실패; ID 재사용/중복 removal/혼합 마스크; 타인/불명 package; 표시 만료·이전/미래 시각·다른 세대; 기존 deadline 유지; 새 화면 위험 버튼/동의 철회/선택 무효화; 대기 취소; 진단 실패; 원래 취소 보존.

최초 대상 실행의 신규 timeout 회귀 1건은 외부 ManualChoiceRefresh timeout이 내부 barrier waiter보다 먼저 완료되는 기존 timer 순서를 예외로 잘못 기대해 실패했다. 테스트 기대를 null/새 F0·클릭 0회 및 늦은 제거 확인 DEADLINE 거부로 바로잡았으며 운영 timeout/성공 조건은 수정하지 않았다. 이후 대상 회귀와 최종 전체 검증 결과를 완료 보고에 기록한다.

최종 로컬 검증:

- 신규 회귀 20개: Debug/Release 각각 통과
- 앱 JVM Debug 1,092개 + Release 1,077개: 실패·오류·skip 0. 두 variant의 공통 테스트를 중복 포함한 실행 결과이며 서로 다른 테스트 2,169개라는 뜻은 아님
- 테스트 호스트 55개/합성 팝업 앱 26개 결과: 실패·오류·skip 0, 이번 Gradle 호출에서는 UP-TO-DATE 재사용
- 앱 Debug/Release, AndroidTest APK, 합성 팝업 앱 Debug 빌드 통과
- 앱 Debug/Release 및 호스트 Lint: 이슈 0
- 실제 APK DEX/패키징 격리 검사 통과. 새 A_MANUAL_RETIREMENT 및 기존 A_MANUAL_ACTION 로그 prefix는 Debug DEX에 있고 Release DEX에는 없음
- 변경 파일 공백 검사 통과. P23 포함 다른 worktree 9개의 HEAD/status 및 기존 수정 파일 해시 동일
- adb 연결 기기 없음. Android 계측·삼성폰·에뮬레이터 실행 없음

## 삼성폰 재실측

이 수정본을 적용한 Windows 소스에서 빌드한다. 이번 작업은 commit/push하지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
# 더 최신 재현 앱이 설치돼 있으면 임의로 덮지 말고 그 버전을 구분한다.
# & $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

폰 UI에서 접근성 서비스, 수집 동의, 시험 앱 허용, 분석 시작 및 C 음성 설정을 직접 확인한다. 다른 PowerShell 창에 같은 adb/serial을 설정한 뒤:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack OFF/ON 각각 안내 완료 및 6초 이상 표시 후 **C의 닫기 선택을 사람이 명시적으로** 누른다. adb로 원본 닫기 버튼/좌표를 누르지 않는다. OVERLAY_REMOVAL 완료 → 새 MANUAL_REFRESH → SNAPSHOT_MATCH/TARGET_MATCH → B_DECISION → F9_VALIDATION → CLICK_ATTEMPT/CLICK_RESULT를 구분한다. C 창만 사라진 것을 원본 팝업 닫힘으로 기록하지 않는다. 실제 원본 소멸과 CLOSED_CONFIRMED를 함께 확인하고 중복 입력 시 클릭 최대 1회를 확인한다.

소유권/실제 detach/전체 창 부재 확인 실패, 외부 앱 조작, 동의 철회, 팝업/버튼 변경, 위험 버튼이면 새 F0 또는 실행 단계에서 거부하고 클릭 0회여야 한다. Samsung callback 순서, 실제 제거, 새 F0 및 원본 닫힘은 기기 검증 전 미확정이다. 원본 14개 UNOBSERVED 및 기존 Scroll/Window 문제를 해결했다고 주장하지 않는다.
