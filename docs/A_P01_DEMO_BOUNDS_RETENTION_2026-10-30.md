# P01 Debug 시연: 검증된 bounds 변화에서 표시 유지 — 2026-10-30

## 범위·기준

- worktree `/workspace/a-samsung-p01`, branch `fix/a-samsung-p01-lifetime-20261028`, HEAD `0f94927c63655d1acd751108b1f224ec7c5f34d7`.
- 기존 미커밋 `A_P01_STRUCTURAL_ROOT_REVIEW_2026-10-30.md`를 수정/삭제하지 않는다. 그 문서는 이전의 엄격 비교 조건에서 작성한 보류 판단이다. 이번 사용자 지시(팀 합의)는 **시연의 표시 유지**에 한해 검증된 geometry 변화를 허용하도록 범위를 변경했다. 현재 로컬 AGENTS.md에는 지시에서 언급한 6-0 문구가 없으며, AGENTS.md 자체는 변경하지 않았다.
- 새 Debug 진단만 추가하는 변경이 아니다. 실제 표시 비교/retire/retain 경로를 연결했다. P01 합성 앱, C #61/#62, P23 및 다른 worktree는 수정하지 않는다.
- 삼성 제공 최신 증거: SHOWN 후 약139ms, ROOT/window 함께 확대, 두 시점 각각 ROOT_WINDOW=EQUAL, 비bounds DTO 일치, 같은 ID/package/type/flags/layer. 실제 layout인지 cache/OEM 표현 변화인지 확정하지 않는다.

## 실제 호출 경로

1. C가 사용한 `DecisionCandidateWiring`의 **실제 B 분석**이 admission을 통과하면 그 분석을 표시 수명 동안 보관한다. 임의 후보나 가짜 ATTEMPT를 만들지 않는다.
2. 기존 `reviewDisplayedEvent`가 살아 있는 C/native generation·display lease·원본 NEW_WINDOW를 확인한다. Debug 시연 기능 ON + 같은 target ID의 정확히 WINDOWS_CHANGE_BOUNDS 단독 이벤트 + 같은 token의 표시 분석/단독 NEW_WINDOW detection/단일 후보인 경우만 별도 bounds 비교를 선택한다. EVENT 자체가 동일성 허가는 아니다.
3. 기존 retire callback을 먼저 실행해 Runtime/F9 실행 증거를 폐기한다. native-before → 기존 F9 read **1회** → native-after/currentness/deadline 검증을 그대로 거친다. strict read 후 두 번째 read로 재시도하는 방식이 아니다.
4. AndroidF9ScreenPort의 DISPLAY_REVIEW 기존 full scan(소유권·보호·occlusion·전체 노드·endpoint·해제)을 사용하고, 불일치 시 별도 `ManualDemoBoundsPolicy`로 관측된 표시 동등성을 검사한다. Ready/새 token/F0 완료 증명은 생성하지 않는다.
5. 검증된 경우에만 `BOUNDS_RETAINED`를 반환해 기존 `reviewManualDisplay`가 C 제거/TTS stop을 실행하지 않는다. 일반 `SAME`과 구분한다. null/예외/취소/해제 실패/native 변화/timeout이면 기존 UNAVAILABLE/제거다.

## 별도 시연 정책의 필수 증거

- 두 complete 입력의 같은 package/target ID/APPLICATION/frame. 원본/새 창 목록 고유성, 다른 창의 type/layer/bounds/focus 등은 엄격하게 비교한다. 확인된 C 창만 기존 규칙대로 제외한다. LISTED에서 target active/focused의 기존 허용 범위 외에는 metadata 예외가 없다.
- target window는 양수이고 frame 안의 bounded window다. root는 두 입력 모두 preorder path 0, nonclickable, 각각 target window와 정확히 같은 Rect여야 한다. 모든 노드의 양수 bounds는 해당 target window 안에 있어야 한다. 잘못된 clipping/영역 밖 노드는 허용하지 않는다.
- 노드 수·순서/path·view ID·class·text·description·clickable·enabled·actions는 모든 위치에서 정확히 같아야 한다. 추가/삭제/본문·버튼 변경이나 다른 창 개입은 거부한다.
- 실제 표시 B 분석의 후보가 하나이며 E2/CLOSE, 명확한 닫기 라벨, 같은 경로/ID/원본 bounds, 지원 widget, enabled/clickable/CLICK, fresh에서 visible이어야 한다. 같은 라벨의 다른 조작 후보가 있으면 거부한다. 위험한 본문/패키지는 기존 F9ContentSafety로 거부한다.
- 허용 geometry는 아래 **두 형태뿐**이다. 양쪽 root/window가 같다는 사실만으로 승인하지 않는다.

| 형태 | 표시 유지 조건 | 거부 예 |
|---|---|---|
| 팝업 전체 이동 | target window와 **모든 노드**가 정확히 같은 dx/dy로 이동, 크기 불변 | 버튼 하나만 이동, 일부 노드만 이동, 비균일 이동 |
| root/window 크기 변경 | 실제 크기가 바뀌고 **모든 자식 bounds는 그대로**, 버튼 포함 모든 자식이 새 영역에도 포함 | 버튼 재배치, 줄바꿈 등 자식 geometry 변화, 잘린 자식, root만/창만 변화 |

이는 일반 reflow/scale/임의 애니메이션을 허용하는 정책이 아니다. 예를 들어 popup 크기 변경으로 닫기 버튼까지 재배치되면 현재 시연 정책도 거부한다. 전체 팝업 이동에 따라 버튼이 같은 이동을 하는 것과 버튼의 독립 재배치는 구분한다. 실제 웹/11번가에서 이 형태가 관측됐다고 주장하지 않는다.

같은 descriptor·창 ID를 가진 미관측 교체/ABA의 native 세대를 인증하지는 않는다. 관측된 ADDED/REMOVED·혼합 mask·다른 대상·CONTENT/STATE·터치/스크롤은 새 완화 분기에 들어오지 않는다. 이 경우 기존 무효화 또는 엄격 재검증이 적용된다. 선택창의 지속 표시가 동일 native popup의 인증은 아니다.

## 반복 이벤트·수명·실행 격리

애니메이션은 받은 이벤트마다 기존 한 번의 bounded scan으로 판정한다. 자체 재시도/자동 polling/지연 후 승인/원본 baseline 교체/lease 연장은 없다. 원래 30초 display lease와 각 read의 100ms/currentness/native 전후 검사는 유지된다. read 중 창 집합이 바뀌거나 endpoint가 불안정하면 안전하게 제거할 수 있으며, 모든 애니메이션에서 유지된다고 보장하지 않는다.

표시가 유지돼도 Runtime/F9 token은 이미 무효화되어 있다. original snapshot/token/수집 시각, B 후보 bounds, 분석·자격을 갱신하지 않는다. 화면 좌표나 노드를 보정하지 않으며 Android handle을 새로 보관하지 않는다. 판정은 이미 읽은 immutable DTO로만 수행한다.

수동 선택 이후 C 실제 제거/전체 부재 → quiet → 새 F0 → 원본과의 엄격 snapshot/target 비교 → 새 B 판정 → F9 PRE_CLICK/PRESS/SCREEN_MATCH → 최대1회 클릭/실제 popup 소멸 확인을 유지한다. **이 작업은 클릭 자격을 확대하지 않는다.** geometry가 원본과 계속 다르면 표시가 유지돼도 새 F0의 SNAPSHOT_MATCH에서 클릭0으로 끝날 수 있다. 이 제한을 해제하거나 원본 token/시간을 새로 복사하지 않았다. 5초 실행 증거 TTL, 자동 실행/SKIP/F12 OFF도 그대로다.

## Debug/Release와 로그

`src/debug/.../ManualDemoBoundsFeature.kt`는 `enabled=true`인 이 Debug 시연 빌드의 명시적 정책이다. 별도 Android UI/설정 우회를 만들지 않았다. Release source set은 `false`이며 서비스 배선과 Android reader 두 경계에서 비활성화한다. 기존 `ManualDisplayContinuity.assess`의 기본값은 엄격 비교다.

형식 예시(실측값 아님):

```text
A_MANUAL_DISPLAY_REVIEW;...;RESULT=BOUNDS_RETAINED;POINT=COMPARISON;DETAIL=NONE;...
A_MANUAL_PROMPT;...;POINT=DISPLAY_RETAINED;CAUSE=DEMO_BOUNDS_RECONFIRMED
```

새 원문·패키지·창 ID·좌표·token·지문·예외를 출력하지 않는다. 기존 Debug sink의 제한된 기록을 재사용한다. Release 진단 로그는 OFF다.

## 회귀 범위

실제 공유 selector → native 전후/currentness 경계 → ManualDisplayContinuity/별도 정책 → reviewManualDisplay의 유지/제거를 테스트한다. P01 ROOT/window 확대, 전체 이동, 축소/영역 포함, 연속 이벤트, 버튼 독립 이동/이름/동작 변경, 같은 ID에서 내용/구조 교체, 다른 창/모호한 소유권/위험 본문/미관측 후보, timeout·취소·cleanup 오류, Debug source set만 활성화됨을 검사한다.

기존 P01 전체 Flow/planner/lease/TTS/B/수동 refresh/F9 회귀도 재사용해 두 경로를 추가했다: 표시 유지 후 6초 이상 C/TTS 완료·원본 시각 보존·변경된 새 F0에서는 클릭0, 표시 유지 후 새 F0/B가 통과해도 마지막 F9 read 변화 시 클릭0. 원래 정상 닫힘/중복 클릭 방지 회귀도 유지한다.

P23 다른 worktree의 `DelayedContentEvent(...boundsOnly=true)` 경로를 읽기 전용으로 확인했다. 이 브랜치에는 그 최신 구현이 포함되어 있지 않아 해당 구현의 통합 테스트를 재실행했다고 주장하지 않는다. 새 selector는 기존 manual presentation/B 분석/NEW_WINDOW가 없는 대기 상태를 가로채지 않는 회귀를 추가했고 P23 파일 hash/status를 보존한다. 실제 P23 장면 회귀는 별도 브랜치/기기 검증이 필요하다.

## 삼성·LG 재시험

이번 수정본은 삼성/LG/에뮬레이터에서 미실행이다. 선택창 유지나 실제 popup 닫힘 성공으로 보고하지 않는다. source 반영 뒤 Windows에서:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "<시험용 삼성폰 serial>"
& $adb devices
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

서비스/허용 앱/수집 동의/음성 안내는 기존 UI에서 사용자가 직접 설정한다. adb로 접근성/동의 설정을 강제 변경하지 않는다. TalkBack ON/OFF 각각 P01에서 BOUNDS_RETAINED와 DEMO_BOUNDS_RECONFIRMED, 선택창 6초 이상 유지, 음성 완료, 표시 횟수/중복 여부를 확인한다. 실패하면 기존 DETAIL·NODE_MISMATCH·cleanup FIRST/MASK를 구분한다.

표시가 유지돼도 닫기 선택은 기존 새 F0/F9 검사에 의해 거부될 수 있다. 실행을 원할 때 사용자가 명시적으로 선택하며 SNAPSHOT_MATCH/B_DECISION/F9_VALIDATION/CLICK_ATTEMPT/CLICK_RESULT를 분리해 확인한다. 이 테스트를 위해 버튼을 자동으로 누르거나 실패한 클릭을 반복하지 않는다.

삼성 유지 성공 후 LG 벨벳에서는 같은 ON/OFF·6초·TTS 완료, 내부 포커스, 외부 터치·내용 변경/다른 popup 시 제거, 같은 팝업 전체 이동과 버튼 독립 이동의 구분, 30초 만료, 최신 재검증 거부 시 클릭0을 확인한다. 시스템/입력창 가림, 원격 native 목록 지연과 취소/서비스 재연결도 별도 확인한다.

## 남은 별도 장애

cleanup의 ADDED 거부/RETIRED_ID_EVENT/HEALTH latch는 전혀 바꾸지 않았다. 정상 bounds 유지 경로는 제거를 시작하지 않지만, 실제 제거가 필요한 상황의 ADDED 문제는 여전히 실패할 수 있다. 실제 native detach/전체 창 부재 확인을 대체하거나 HEALTH를 초기화하지 않는다. 동일한 old callback/클릭을 재시도하지 않는다.

## 최종 로컬 검증

실제 완료 결과는 아래에 기록한다. 기존 미커밋 구조 검토 문서 및 다른 worktree는 보존한다. 커밋·스테이징·푸시·병합하지 않는다.

- 신규 회귀 19개(정책17 + 실제 P01 Flow2). 최종 앱 JVM Debug **1,350개**, Release **1,335개**, 실패·오류·skip 0. variant 중복을 고유 테스트로 합산하지 않는다.
- 호스트55개·합성 앱26개는 이번 전체 명령에서 UP-TO-DATE 결과 재사용, XML 실패·오류·skip 0 확인.
- 앱 Debug/Release APK 빌드 성공. 앱 AndroidTest·호스트 Debug/AndroidTest·합성 앱 Debug는 UP-TO-DATE 산출물 재사용.
- 앱 Debug/Release·호스트 Debug Lint 성공, 각 XML issue 0. 기존 패키징·DEX 격리 검사 통과.
- 실제 컴파일된 Feature class 상수는 Debug=true/Release=false. 실제 Debug prompt/geometry 진단 marker는 Debug DEX에 있고 Release DEX에는 없다.
- 최종 Gradle BUILD SUCCESSFUL: 4분3초, 250 task 중 41 실행/209 UP-TO-DATE. 로그 `/workspace/a-p01-demo-bounds-full.log`는 저장소 밖에 있다.
- 최초 대상 테스트 컴파일에서 이 프로젝트가 BuildConfig를 생성하지 않아 실패했다. 테스트를 기존 Debug/Release 소스셋 logger factory와 feature 상수의 일치 확인으로 수정한 뒤 대상/전체 검증이 통과했다. 남은 테스트 실패는 없다. 기존 다른 담당 테스트의 coroutine opt-in 컴파일 warning은 이번에 임의 수정하지 않았다.
- Android port 파일의 기존 CRLF를 보존했다. 공백 검사는 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check`로 통과하고 신규 파일의 줄 끝 공백/EOF도 별도 확인한다.
- 삼성/LG/에뮬레이터 실측은 미실행. 표시 유지·음성 완료의 단말 성공, 원본 popup 닫힘, 최신 P23의 실제 기기 회귀는 미검증이다.

이번 변경은 **11개 파일(수정6/신규5)**이다. 보존한 기존 미커밋 구조 검토 문서 1개는 이 수에 포함하지 않는다.

1. `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
2. `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
3. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt`
4. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
5. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptTrace.kt`
6. `app/src/test/java/kr/closeforyou/accessibility/integration/P01SamsungSequenceRegressionTest.kt`
7. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsPolicy.kt` (신규)
8. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsFeature.kt` (신규)
9. `app/src/release/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsFeature.kt` (신규)
10. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsPolicyTest.kt` (신규)
11. `docs/A_P01_DEMO_BOUNDS_RETENTION_2026-10-30.md` (신규)
