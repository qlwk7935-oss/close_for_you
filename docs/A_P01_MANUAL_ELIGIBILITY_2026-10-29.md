# P01 수동 닫기: 실행 자격 검사와 고정 코드 진단

## 실제 증거와 미확정 사항

브랜치 `fix/a-samsung-p01-lifetime-20261028`, 작업 기준 HEAD `b66bed504c9c995a75c2caa652949afdfedd4446`. 시작 작업 트리는 clean이었다. P23 및 다른 worktree는 수정하지 않는다. 이번 변경은 commit/push하지 않는다.

사용자 삼성폰 로그에서는 C 제거/사용자 접촉 종료/새 F0/원본 팝업·대상 비교/B EXECUTE 승인이 완료됐으나, F9 진입 전에 `ELIGIBILITY_UNAVAILABLE / POLICY_DENIED`로 클릭 0회였다. 이는 executeUser의 `prepared.autoEligibility == null`을 입증한다. **어느 필수 검사가 실패했는지는 이전 로그로 확정할 수 없다.**

기존 `F9EligibilityAdapter.prepare()`는 자동/수동 모두 호출됐다. 초기 TTL·토큰·scene 조건을 만족한 뒤에도 다섯 검사 중 하나가 빠지면 eligibility를 null로 반환한다. 특히 POPUP_SCOPE_CONFIRMED가 `NEW_WINDOW`라는 이력 신호에 묶여 있었다. 이 때문에 현재 별도 창이 검증돼도 이력 신호가 SAME_WINDOW_OVERLAY이면 차단될 수 있다. 이것은 코드상 확인된 결합이며, 이번 삼성 로그의 실제 탈락 조건이었다고 단정하지 않는다. 실제 노드/창/포커스/본문 불일치라면 새 코드에서도 거부한다.

## 변경 경로와 불변식

수동: 기존 native C 제거 확인 → 접촉 종료 → **새 collector F0 수집** → 원본/새 전체 팝업·버튼 비교 → `executeUser()` → 기존 `screen.read()` → `prepareUser()` → 실제 B USER_CHOICE 정책 → F9 최종 검사 → ACTION_CLICK 최대 1회 → 사후 원본 소멸 확인.

`prepareUser()`는 명시적 CLOSE_ONCE, 같은 새 token, 버튼 의미 확인, 단일 E2 라벨 닫기 후보 및 candidate ID 결합을 요구한다. NEW_WINDOW 또는 SAME_WINDOW_OVERLAY 신호가 있어야 하며 INSUFFICIENT_EVIDENCE/FULL_SCREEN_TRANSITION은 거부한다. 현재 일치하는 별도 APPLICATION 창이 active/focused이고 frame 안에 들어가며 fullscreen이 아닌지를 별도로 확인한다. 실제 같은 Activity 안의 Dialog subtree나 fullscreen을 실행 범위로 추가하지 않는다.

B의 원래 후보 생성기는 수정하지 않았다. SAME_WINDOW_OVERLAY에서는 B가 실제 unambiguous Dialog subtree를 찾아 후보를 생성해야 한다. A가 가짜 NEW_WINDOW 신호나 후보를 넣지 않는다. B 후보가 없으면 수동 경로도 실행되지 않는다.

AutoEligibility라는 공통 DTO와 다섯 검사 의미는 변경하지 않았다. 수동 경로 역시 검증된 다섯 검사만 개별적으로 추가한다. 이 값 자체는 실행 허가가 아니며 B USER_CHOICE의 ALLOW_ONCE/EXECUTE, 동일 후보·token·새 수집 시각 및 F9 재검사가 여전히 필요하다. 전체 검사 통과를 일괄 주입하지 않는다.

자동 `handle()`은 기존 `prepare()`를 계속 사용하고 NEW_WINDOW 요건을 유지한다. 자동 실행 feature OFF, 자동 SKIP OFF, F12 반복 OFF를 변경하지 않았다. 수동 eligibility를 raw source에 발행하거나 자동 요청에 재사용하지 않는다.

## null 조건과 진단 의미

기존 Debug `A_ManualPrompt` / `A_MANUAL_ACTION`에 `STEP=ELIGIBILITY`를 추가한다. 같은 기존 ATTEMPT에 MODE/CHECK/STATE/DETAIL만 결합한다. 원문·package·창 ID·좌표·token·지문·시각·예외 원문을 저장/출력하지 않는다. 추가 Android getter/수집/OCR/IPC/시계 조회가 없다. Debug sink의 실패가 판단을 바꾸지 않고 Release 팩토리는 기존처럼 null이다.

| CHECK | 거부 DETAIL (위에서부터 최초 불일치) |
|---|---|
| CAPTURE_CURRENT | NEGATIVE_CAPTURE, CLOCK_REVERSED, TTL_EXPIRED |
| TOKEN_BINDING | DETECTION_TOKEN, ANALYSIS_TOKEN |
| SCREEN_AVAILABLE | SCENE_UNAVAILABLE |
| MANUAL_CHOICE | CHOICE_TOKEN, CHOICE_KIND, CHOICE_MEANING, CHOICE_TARGET |
| F0_PASSED | SCENE_INCOMPLETE, PACKAGE_CHANGED, WINDOW_CHANGED, FRAME_CHANGED, NODES_CHANGED, WINDOWS_CHANGED, PACKAGE_PROTECTED, TEXT_PROTECTED |
| TREE_COMPLETE | DEPENDENCY_FAILED(F0 불일치), SNAPSHOT_INCOMPLETE |
| POPUP_SCOPE_CONFIRMED | 선택 결합 실패, NEW_WINDOW_REQUIRED(자동만), DETECTION_UNCERTAIN, POPUP_WINDOW_UNAVAILABLE, WINDOW_TYPE, WINDOW_INACTIVE, WINDOW_UNFOCUSED, WINDOW_UNBOUNDED |
| DECISION_TEXT_SCOPED | DEPENDENCY_FAILED(범위 미확립), TEXT_NOT_COMPLETE, CLASSIFICATION_UNSAFE |
| TARGETS_IN_POPUP | DEPENDENCY_FAILED(범위 미확립), TARGET_UNAVAILABLE, TARGET_OUTSIDE_POPUP |

성공한 개별 검사는 STATE=PASSED/DETAIL=NONE이다. 초기 TTL/token/scene 전제가 실패하면 후속 검사는 NOT_ATTEMPTED/DEPENDENCY_FAILED이며 검사 완료로 간주하지 않는다. 다른 독립 검사가 PASSED여도 하나가 빠지면 eligibility는 null이다. 진단 결과는 승인 입력으로 사용하지 않는다. 조건 내부의 여러 불일치는 첫 사유만 기록하므로 전부를 열거했다고 주장하지 않는다.

예시(실제 삼성 관측값이 아님):

```text
A_MANUAL_ACTION;ATTEMPT=4;STEP=ELIGIBILITY;STATE=REJECTED;REASON=NONE;SAFETY=NONE;F9=NONE;MISMATCH=NONE;OUTCOME=NONE;NEXT=NONE;MODE=USER_CHOICE;CHECK=F0_PASSED;DETAIL=WINDOWS_CHANGED
```

위 경우 창 집합 비교가 실제 실패한 것이므로 그대로 중단한다. 창 비교를 정렬/생략하거나 focus/geometry/보호 조건을 낮춰 통과시키지 않는다.

## 후속 F9 검사와 결과 해석

새 수집 시각 기준 엄격한 5초 TTL, native cleanup, 앱 허용·동의, 사용자 조작, 서비스 세대, token/revision, 전체 노드/창 비교, 현재 보이는 단일 안전 닫기 대상, 즉시 클릭 전 전체 재조회와 ACTION_CLICK 1회 장부는 변경하지 않았다. 원본 과거 DTO의 시각/token만 바꾸는 경로는 없다.

배경 page가 Android 창 목록에서 사라졌으면 기존 F9는 독립 사전 page proof와 그 원래 5초 TTL을 여전히 요구한다. 이 증거가 없거나 오래됐으면 SCREEN_UNVERIFIED로 차단될 수 있다. 이번 작업에서 이 보호를 해제하지 않았다. prepareUser 통과만으로 실제 클릭 성공을 약속하지 않는다.

결제·구매·동의·권한·구독·삭제·사기 문구 및 모호한 라벨 차단을 유지했다. 클릭 반환 true와 CLOSED_CONFIRMED는 별개다. STILL_PRESENT/UNKNOWN/LEFT_APP는 닫힘 성공이 아니다. 좌표 클릭·클릭 재시도·자동 반복을 추가하지 않았다.

## 회귀

신규 `F9EligibilityAdapterTest` 24개는 실제 prepareUser/executeUser, B 후보 생성·정책 및 F9 coordinator를 사용한다. Android port/창·노드·클릭과 시계는 가짜다. 다음을 포함한다.

- 현재 별도 창 + 실제 B Dialog subtree 후보: NEW_WINDOW 없이 수동 자격, 자동 자격은 여전히 거부
- 원본 6초 후 실제 새 read 콜백·새 token·새 캡처 시각·재생성 B 후보 후 F9; 과거 시각의 token만 교체하면 거부
- TTL 정확히 5초부터 거부, 시각 역전/누락 scene/token 불일치
- incomplete/package/window/frame/node/window-list 비교 각각 거부 원인
- fullscreen/실제 same-window/창 누락·중복·비활성·비focus/잘못된 범위 거부
- 전체 본문 누락·E4/선택 불일치·범위 밖 대상/위험 문구 거부
- 클릭 직전 화면·동의·접촉·서비스·cleanup·TTL 변경 거부
- 승인 후 클릭 최대 1회, popup 잔존/관측 불가를 성공으로 승격하지 않음
- 취소 전파와 진단 출력 예외 격리

기존 F9ServiceSession의 same-window 거부 회귀는 신호 이름만으로 범위를 가정하지 않고 실제 fullscreen Activity 경계를 구성하도록 보강했다. 실제 SAME_WINDOW_OVERLAY 신호라도 현재 별도 창과 B 후보가 입증된 경우는 신규 양성 회귀에서 검증한다. B 정책/테스트 정답을 바꾼 것이 아니다.

초기 신규 회귀 fixture는 SAME_WINDOW_OVERLAY에 B가 요구하는 실제 Dialog subtree가 없어 후보 0개로 실패했다. 운영 B를 바꾸거나 가짜 후보를 주입하지 않고, 합성 snapshot에 실제 의미의 Dialog root를 추가해 수정했다. 이후 대상 101개가 통과했다. 전체 로컬 검증 결과는 완료 보고에 기록한다.

최종 로컬 검증 결과:

- 신규 24개: Debug/Release 각각 실패·오류·skip 0
- 앱 전체 JVM: Debug 1,116개 / Release 1,101개, 실패·오류·skip 0. 두 variant의 공통 테스트를 중복 포함하며 서로 다른 테스트 2,217개라는 뜻은 아님
- 호스트 55개 / 합성 팝업 앱 26개 통과 결과는 이번 Gradle에서 UP-TO-DATE 재사용
- 앱 Debug/Release, AndroidTest APK, 합성 앱 Debug 빌드 통과
- 앱 Debug/Release 및 호스트 Lint 이슈 0
- APK DEX/패키징 격리 통과. A_MANUAL_ACTION/A_MANUAL_RETIREMENT prefix Debug 존재 / Release 부재
- 공백 검사 통과. P23 포함 다른 9개 worktree의 HEAD/status/기존 수정 파일 해시 동일
- adb 연결 기기 없음. 삼성/LG/에뮬레이터 계측 미실행

## 삼성폰에서 확인할 것

수정본을 적용한 소스에서:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

기존 시험 앱과 사용자 UI 동의/접근성 활성화를 유지한다. 다른 PowerShell 창에서 같은 adb/serial을 설정한 뒤:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF 각각 6초 이상 표시·안내 완료 후 C의 닫기 선택을 사람이 명시적으로 한다. 기존 OVERLAY_REMOVAL/MANUAL_REFRESH/SNAPSHOT_MATCH/TARGET_MATCH 뒤 새 ELIGIBILITY 9개 검사, B_DECISION, F9_VALIDATION, CLICK_ATTEMPT/CLICK_RESULT를 확인한다. 최초 실패 CHECK/DETAIL을 보고하며 개인정보를 포함하는 일반 logcat 전체를 공유하지 않는다.

실제 원본 팝업 소멸과 CLOSED_CONFIRMED가 모두 있어야 성공으로 기록한다. 화면 교체·외부 조작·동의 철회·위험 대상에서는 클릭 0회여야 한다. 이번 환경의 삼성/LG 실기기 및 에뮬레이터 검증은 미실행이며, 원본 F1 14개 UNOBSERVED 및 Scroll/Window 문제 해결을 주장하지 않는다.
