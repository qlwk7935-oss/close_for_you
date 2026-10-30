# P01 PRE_CLICK matches 첫 실패 진단 — 2026-10-30

## 범위와 증거 수준

- worktree `/workspace/a-samsung-p01`, branch `fix/a-samsung-p01-lifetime-20261028`
- 기준 HEAD `b40d88a8b0f9aaa7b283f785a56fc1fe86b5b915`, 시작 clean.
- 사용자 제공 삼성 실측: 엣지 패널 OFF, 2026-10-30 11:56:14 ATTEMPT=6. MANUAL_ELIGIBILITY COMPLETE, CAPTURE_CURRENT~TARGETS_IN_POPUP PASSED, B APPROVED/EXECUTE, PRE_CLICK COMPLETE/CALLBACK_RETURNED=YES, F9 SCREEN_UNVERIFIED, 클릭 미시도.
- 이 시도에서 이전 OCCLUDING_WINDOW와 달리 read 자체를 통과한 것은 확정이다. 엣지 패널이 이전 가림의 유일한 원인이었다거나, 새 실패가 page TTL이라는 것은 아직 입증되지 않았다.
- 실제 삼성/에뮬레이터 실행 없음. 로컬 JVM/컴파일과 실측을 구별한다. 커밋·푸시·PR·병합 없음.

## 실제 호출 경로와 검사 차이

`ManualChoiceRefresh.afterRemoval()/collect()` → native 제거/quiet 이후 서비스의 실제 새 F0 → 기존 전체 동일성/대상 비교 → `F9ServiceSession.executeUser()` → `prepareUser()` → B USER_CHOICE 판단 → `F9AutoCloser.execute()/executeOnMain()` → PRE_CLICK read → `matches(reference, initial)` → B receipt 검사 → PRESS read/즉시 재검사 → 기존 1회 ACTION_CLICK → POST_CLICK.

`COMPLETE / CALLBACK_RETURNED=YES`는 그 read의 창·노드·geometry·보호 검사와 callback 반환을 뜻한다. 참조 snapshot과 일치하거나 클릭이 허가됐다는 뜻은 아니다. 정상 반환한 뒤에도 `matches()`는 별도로 false를 반환할 수 있다. SCREEN_UNVERIFIED는 null PRE_CLICK, initial matches 거부, 최종 PRESS가 실제 실행하지 않은 경우에 공통으로 사용돼 기존 로그로 정확한 분기를 확정할 수 없었다. PRE_CLICK COMPLETE 후 바로 거부된 이번 증거에서는 initial matches가 우선 후보지만, 완전한 후속 PRESS 로그가 없다면 다른 거부 경계를 관측했다고 추가하지 않는다.

`prepareUser()`가 검사하는 새 수집의 5초 현재성·전체 nodes/windows·현재 bounded popup·라벨 후보는 **독립적인 popup 이전 page proof**의 유효성을 인증하지 않는다. B 승인 후에도 이 별도 F9 검사가 실패할 수 있다.

## matches의 기존 검사 순서와 고정 코드

원래 `||` / `&&`의 좌→우 short circuit을 같은 순서의 조기 return으로 나눴다. 비교식·한도·clock 조회 위치·허용 조건은 유지한다. 아래 첫 실패만 남기고 이후 조건을 진단 목적으로 더 계산하지 않는다.

| 순서 | 고정 코드 / 기존 조건 |
|---|---|
| 1 | SCENE_INCOMPLETE: read.complete=false |
| 2 | SNAPSHOT_INCOMPLETE: 원본 COMPLETE 아님 |
| 3~5 | PACKAGE_MISMATCH, PROTECTED_PACKAGE, WINDOW_ID_MISMATCH |
| 6~7 | PAGE_DIGEST_UNAVAILABLE, SCREEN_BOUNDS_MISMATCH |
| 8~9 | NODES_MISMATCH, WINDOWS_MISMATCH: 기존 전체 목록의 정확한 동등성 |
| 10~11 | NODE_COUNT_INVALID(1..1200), PROTECTED_TEXT |
| 12 | TARGET_WINDOW_UNAVAILABLE: 대상 id의 정확히 한 창 확인 불가 |
| 13~16 | TARGET_WINDOW_TYPE, TARGET_WINDOW_INACTIVE, TARGET_WINDOW_UNFOCUSED, TARGET_WINDOW_OUTSIDE_FRAME |
| 17 | pageWindowId==windowId인 경우만 PAGE_PROOF_UNAVAILABLE |
| 18~20 | 같은 분기의 PAGE_PACKAGE_MISMATCH, PAGE_WINDOW_EQUALS_POPUP, PAGE_DIGEST_BLANK |
| 21~24 | PAGE_TIMESTAMP_NEGATIVE, PAGE_TIMESTAMP_AFTER_CAPTURE, PAGE_TIMESTAMP_IN_FUTURE, PAGE_PROOF_EXPIRED |
| 25 | FULLSCREEN_WITH_HIDDEN_PAGE |
| 26 | TARGET_NODE_UNAVAILABLE: candidate path의 정확히 한 노드 확인 불가 |
| 27~28 | CLOSE_NODE_COUNT, CLOSE_NODE_MISMATCH |
| 29~33 | CANDIDATE_EVIDENCE, CANDIDATE_ROLE, CANDIDATE_LABEL, CANDIDATE_VIEW_ID, CANDIDATE_BOUNDS |
| 34~39 | NODE_DISABLED, NODE_NOT_CLICKABLE, WIDGET_UNSUPPORTED, NODE_NOT_VISIBLE, CLICK_ACTION_UNAVAILABLE, NODE_OUTSIDE_WINDOW |
| 40~42 | LABELS_EMPTY, LABEL_NOT_PRESENT, LABELS_NOT_ALL_CLOSE |

NODE_NOT_CLICKABLE와 LABELS_EMPTY는 정상적인 불변 입력에서 앞의 unique clickable close 노드 조건에 가려진다. 이 두 조건을 일부러 통과시키거나 첫 실패라고 출력하기 위해 순서를 바꾸지 않는다. 회귀는 해당 입력이 앞의 CLOSE_NODE_COUNT에서 거부됨을 확인한다. enum 42개가 각각 Android에서 도달 가능하다는 주장은 하지 않는다.

## pageBeforePopup의 별도 TTL

`AndroidF9ScreenPort.scan()`은 앱 창 2개이면 비활성 앱 창을 base로 고르고, 앱 창 1개이면 active 자신을 base로 고른다. 후자에서 `scene.pageWindowId == scene.windowId`이다. 이것은 화면 뒤 page가 현재 읽혔다는 증거가 아니므로 matches는 다음을 요구한다.

- `F9ServiceSession.observePage()`에서 이전 full-frame 일반 화면을 독립 read한 `pageBeforePopup`이 존재.
- 같은 앱, popup과 다른 window, 비어 있지 않은 digest.
- page의 원래 캡처 시각이 음수가 아니고 새 popup 캡처보다 늦지 않으며 미래가 아님.
- **현재 시각 - page의 원래 시각 < 5000ms**. 정확히 5000ms부터 거부.
- popup이 full-frame이 아님.

새 F0의 실제 시각/토큰은 수동 선택 이후 갱신되지만, 이 독립 배경 증거의 원래 시각은 갱신되지 않는다. 음성 청취 후 6초 이상 기다리면 fresh popup은 새로워도 page proof는 만료될 수 있다. 앞선 `invalidate(permissionOrInteraction=true)`는 session revision 변경과 page=null도 수행하므로 만료 대신 부재일 수도 있다. 새 수집이 complete라는 이유로 이 page를 만들어 채우지 않는다.

반대로 현재 read가 독립 배경 창도 반환하면 위 분기를 실행하지 않는다. 이 경우 오래된 page proof가 있어도 그것만으로 거부하지 않는다. 이는 기존 코드와 같다. `PAGE_WINDOW_EQUALS_POPUP`은 **별도 proof의 window가 popup과 같음**이고, 정상적인 hidden-page 분기 진입 조건인 scene의 두 id 일치 자체를 실패로 부르는 코드가 아니다.

## 전체 노드·창 목록과 C 생성/제거

- `scene.nodes != snapshot.nodes`는 path/viewId/class/text/description/clickable/enabled/bounds/actions 등을 포함한 기존 목록 동등성이다. 순서나 해당 필드가 다르면 NODES_MISMATCH다. 어떤 화면 원문/값이 바뀌었는지는 출력하지 않는다.
- `scene.windows != snapshot.windows`도 id/type/bounds/active/focused/layer와 순서를 포함한다. 동일 집합이어도 순서 또는 포커스/layer 변경이면 거부할 수 있다.
- 수동 참조 snapshot은 표시 전의 오래된 원본이 아니라 C 제거+quiet 후 **실제 새 F0**이다. 따라서 C가 한번 생성·제거됐다는 이력만으로 불일치가 필연적인 구조는 아니다. overlay 잔존은 별도 native 부재/실제 F9 read 검사에서도 거부한다.
- 다만 새 F0와 PRE_CLICK은 서로 다른 read다. 포커스 복구, 시스템 창 집합/순서, 대상 bounds/트리의 변동이 그 사이에 있을 수 있다. endpoint 검사는 각 read 내부 경계이며 read 사이의 원자성을 보장하지 않는다. 원인을 C 생성 이벤트라고 확정할 수 없다.
- 이 정확한 비교를 정렬·부분집합·필드 무시로 바꾸지 않았다. 이번 로그만으로 불필요한 불일치인지 실제 화면 변화인지 아직 판별할 수 없다.

## 최소 진단 구현과 출력

기존 `A_MANUAL_ACTION`의 F9_VALIDATION/REJECTED 행에, matches에서 실제 거부한 경우에만 `SCREEN_MATCH=<고정 enum>` 하나를 추가한다. 별도 Logcat 행은 추가하지 않는다.

실측값이 아닌 형식 예시:

```text
A_MANUAL_ACTION;ATTEMPT=6;STEP=F9_VALIDATION;STATE=REJECTED;REASON=NONE;SAFETY=NONE;F9=SCREEN_UNVERIFIED;MISMATCH=NONE;OUTCOME=NONE;NEXT=NONE;SCREEN_MATCH=PAGE_PROOF_EXPIRED
```

- PAGE_PROOF_UNAVAILABLE / PAGE_PROOF_EXPIRED면 독립 배경 증거의 부재/원래 TTL 만료를 분리한다.
- NODES_MISMATCH / WINDOWS_MISMATCH면 새 F0와 read의 전체 목록 불일치다. 개별 노드나 실제 식별자는 남기지 않는다.
- NODE_NOT_VISIBLE / CANDIDATE_* 등은 같은 첫 실패 표의 의미다.
- `SCREEN_MATCH` 필드가 없으면 matches의 false를 관측하지 않은 것이다. read 불가, settings/TTL/B guard, PRESS의 `fresh != initial` 등 다른 기존 경계를 해당 로그와 함께 확인한다. 필드 부재를 matches 통과로 인증하지 않는다.
- PRE_CLICK/PRESS 어느 read 다음의 실패인지는 같은 attempt의 기존 A_F9_READ PURPOSE와 순서로 구분한다. 두 read에서 모은 값을 결합하지 않는다.

실제 driver는 Debug `ManualPromptLog.create()`가 만드는 trace로 연결된다. Release create()는 여전히 null이고 formatter/Logcat 출력은 Debug 소스에만 있다. 진단은 실행 호출별 nullable enum 하나만 보유하며 첫 값 이후 덮어쓰지 않는다. 원문·좌표·패키지·창 ID·timestamp·지문·token·Throwable를 저장하지 않는다.

원래 matches의 비교 결과에서 바로 enum을 기록하므로 새 Android getter/수집/OCR/IPC/시계 조회가 없다. 원래 hidden-page clock 조회는 같은 위치에서만 수행한다. 첫 실패 뒤 추가 검사도 없다. PRE_CLICK 거부는 기존 complete에서 출력하고, PRESS accept 안에서는 메모리 대입만 한 뒤 반환하므로 검증과 ACTION_CLICK 사이에 진단 I/O가 없다. 실패 진단 sink 예외/취소는 기존 diagnosticWrite로 격리되고 실제 작업 취소는 기존 executor 경로로 전파된다.

## 안전 조건과 남은 문제

조건 삭제·TTL 증가·proof 갱신·재수집·자동 재시도·강제 포커스·native 소유권 추정·좌표/부모/제스처 fallback 없음. F0/100ms 예산, B 승인, PRESS 재수집/즉시 검사, 기존 reservation과 단일 클릭, POST_CLICK 실제 소멸 확인을 유지한다. 자동 실행/자동 SKIP/F12 OFF 유지. 공통 DTO와 B/C/D/Manifest/gradle.properties 변경 없음.

NATIVE_ID_UNAVAILABLE와 LIVE_COUNT=NONE 초기 종료, SYSTEM 가림, 원본 14개 UNOBSERVED 및 Scroll/Window 문제는 별도 미해결로 그대로 유지한다. 삼성 실제 첫 실패는 이번 Debug 버전 실측 전까지 미확정이다.

## 회귀와 검증 기록

`F9MatchDiagnosticsTest`는 별도 enum 변환 모형이 아니라 실제 F9AutoCloser.execute → PRE_CLICK → matches → 기존 terminal ManualActionTrace를 사용한다. Android port/clock과 승인 receipt는 명시적인 JVM 합성 fixture다. 실제 Android F0나 삼성 팝업 닫힘을 입증하지 않는다.

- 40개 도달 가능한 첫 실패 입력을 실제 executor에서 각각 거부; authorize/press/클릭 0회.
- 진단 on/off 모두 같은 거부·read/clock 횟수, 초기 단락 이후 clock 미조회.
- 6초 수동 선택의 fresh popup과 old page TTL 분리; 4999/5000/5001 경계.
- 현재 독립 배경이 읽히면 old page가 판정을 막지 않음.
- 목록 순서/포커스/layer/overlay 포함 변화, 중복 target 창/노드 거부.
- PRESS에서 page TTL이 만료되는 경우와 정상 accept→1회 action 사이 진단 I/O 부재.
- sink 실패/취소, 실제 read 취소, clock 예외, 다음 invocation으로 첫 실패 오염 방지.
- 기존 F9AutoCloser/ManualActionTrace 및 전체 회귀 유지.

초기 시험의 실패도 구분한다. 새 회귀의 진단 record 반사 검사는 기존 중첩 enum 코드와 Compose가 생성하는 static `$stable` 필드를 잘못 가정했다. instance 필드의 고정 코드만 검사하도록 고쳤다. 또 withContext의 coroutine stacktrace recovery가 취소 객체를 감쌀 수 있어 원래 취소 또는 그 cause가 보존되는지 검사하도록 수정했다. 이 두 실패는 운영 허용 조건을 변경해서 해결하지 않았다.

최종 실행 결과는 아래에 기록한다.

### 최종 실행 결과

- JDK 17 / 로컬 Android compile SDK 35, `BUILD SUCCESSFUL in 3m 40s`, 250 tasks(34 executed / 216 up-to-date).
- 신규 JVM 17개(40개 첫 실패 case를 반복하는 실제 executor 검사 포함), Debug/Release 모두 통과.
- 앱 전체 Debug 1,254개 / Release 1,239개: 각 실패·오류·skip 0. variant 공통 시험을 고유 시험 수로 합산하지 않는다.
- 호스트 55개 / 합성 앱 26개는 기존 UP-TO-DATE 결과 재사용.
- 앱 Debug/Release 및 AndroidTest APK 빌드 성공. 호스트 Debug/AndroidTest·합성 앱 Debug는 기존 산출물 재사용.
- 앱 Debug/Release + 호스트 Lint XML issue 0.
- 기존 APK/DEX 격리 검사 통과. 새 JVM 회귀 class_def는 Debug/Release 앱 APK에 없음.
- `;SCREEN_MATCH=`, `A_MANUAL_ACTION;`, `A_F9_READ;`는 Debug DEX에 존재, Release DEX에는 부재.
- 기존 CRLF 보존 공백 검사, 신규 파일 공백 검사, 정확한 변경 범위 검사 통과.
- P23 포함 다른 9개 worktree의 HEAD/status/기존 dirty 파일 SHA-256이 보존 기록과 일치. Manifest/properties/collector/AndroidF9ScreenPort/session/eligibility/refresh/ManualPopupConnection/native barrier의 파일 bytes는 HEAD와 동일.
- 실제 삼성·LG·에뮬레이터/P01 실행 없음. 삼성 root cause와 실제 닫힘은 미검증.

최종 검증 명령:

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

전체 Gradle log: 프로젝트 밖 `/workspace/a-p01-match-full.log`. 이번 최종 소스의 실행이며 이전 1,237/1,222개 결과와 구분한다.

### 변경 파일 (정확히 6개)

1. `app/src/main/java/kr/closeforyou/accessibility/action/F9AutoCloser.kt` — 기존 실패 순서를 유지해 첫 거부 enum을 실행 호출 안에 보관.
2. `app/src/main/java/kr/closeforyou/accessibility/action/F9ReadDiagnostics.kt` — F9MatchFailure 고정 enum.
3. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualActionTrace.kt` — 기존 terminal code의 nullable enum 필드.
4. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt` — 기존 Debug 한 줄에만 SCREEN_MATCH 추가.
5. `app/src/test/java/kr/closeforyou/accessibility/action/F9MatchDiagnosticsTest.kt` — 신규 17개 회귀.
6. `docs/A_P01_PRE_CLICK_MATCH_DIAGNOSTICS_2026-10-30.md` — 이 분석·검증 기록.

HEAD `b40d88a8b0f9aaa7b283f785a56fc1fe86b5b915` 유지, 수정 4개/신규 2개, staged 없음. 커밋·푸시·병합·다른 worktree 수정 없음.

## 다음 삼성 관측 방법

이번 변경은 아직 로컬에만 있으므로 원격 기존 HEAD를 다시 빌드하면 SCREEN_MATCH가 없다. 수정본이 반영된 Debug APK에서 기존 P01 시나리오를 실행하고 같은 ATTEMPT의 아래 순서만 확인한다.

1. MANUAL_REFRESH/SNAPSHOT_MATCH/TARGET_MATCH, MANUAL_ELIGIBILITY COMPLETE와 각 ELIGIBILITY 결과.
2. B_DECISION APPROVED/EXECUTE.
3. A_F9_READ PURPOSE=PRE_CLICK / COMPLETE / CALLBACK_RETURNED=YES.
4. 이어지는 F9_VALIDATION의 F9와 **SCREEN_MATCH**.
5. PRESS read가 있었다면 그 COMPLETE/REJECTED와 순서, CLICK_ATTEMPT와 실제 원본 소멸 결과도 따로 구분.

PowerShell의 제한된 tag 조회:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

원본 화면/좌표/패키지/창/토큰/지문 dump나 추가 수집은 필요 없다. PAGE_PROOF_EXPIRED가 나온 경우에도 TTL을 늘리거나 old proof 시간을 덮어쓰지 않는다. 기존 안전 차단을 유지한 채 별도 배경 증거 계약의 다음 작업을 판단한다.
