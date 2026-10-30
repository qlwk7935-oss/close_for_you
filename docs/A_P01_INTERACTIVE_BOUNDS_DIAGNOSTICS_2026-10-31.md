# P01 최초 interactive 자식 bounds 거부 진단 — 2026-10-31

기준 HEAD `c71e7667e0ce9cb6df355160fafa55cd4df0cc3c`, 브랜치 `fix/a-samsung-p01-lifetime-20261028`.
사용자 삼성 실측은 SHOWN 뒤 약144ms에 POLICY_INTERACTIVE_BOUNDS로 표시가 제거된 상태다.
이번 작업은 원인 분리를 위한 Debug 진단이며 삼성/LG/에뮬레이터를 실행하지 않았다.

## 실제 연결과 출력

`checkedLayout()`이 실제 최초 POLICY_INTERACTIVE_BOUNDS를 반환하기 직전에 동기 observer를 호출한다.
observer에는 그 비교의 ordinal과 이미 검증된 native presence만 일시 전달한다. 전체 원본/fresh action 목록의 정확 비교와 객체 결합 검증은 기존대로 먼저 통과해야 한다.
원본/fresh DTO, 실제 표시된 B 후보, 그 presence로 Debug variant가 고정 enum 두 개를 만든다.
ordinal/path/ID/좌표/label/해시/토큰/원시 Android 객체는 코드에 저장하거나 출력하지 않는다.
native metadata를 재조회하지 않으며 추가 Android getter, 수집, 시계, OCR, IPC 또는 재시도가 없다.

기존 `A_MANUAL_DISPLAY_REVIEW` 한 줄에 아래 두 필드만 붙인다. 예시는 관측 결과가 아니다.

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=CHANGED;...;DEMO_BOUNDS=POLICY_INTERACTIVE_BOUNDS;INTERACTIVE_NODE=CLOSE_CANDIDATE;INTERACTIVE_CAUSES=CLICKABLE_DTO_ACTION_NATIVE_ACTION
```

| INTERACTIVE_NODE | 의미 |
|---|---|
| CLOSE_CANDIDATE | 최초 거부 행이 실제 B 단일 닫기 후보와 결합됨 |
| OTHER_CLICKABLE | 닫기 후보가 아닌 clickable 자식 |
| NONCLICK_NATIVE_CAPABILITY | clickable=false지만 native action이 있는 자식. 무해함/비실행성을 뜻하지 않음 |
| UNKNOWN | 분류에 필요한 입력 관계를 확인하지 못함 |

`INTERACTIVE_CAUSES`는 CLICKABLE / DTO_ACTION / NATIVE_ACTION의 일곱 가지 비어 있지 않은 조합을 각각 표현한다.
예를 들어 DTO_ACTION_NATIVE_ACTION은 clickable은 false이고 나머지 두 검사가 true임을 뜻한다.
일관성을 확인할 수 없으면 UNKNOWN이다. DTO action은 기존 CLICK/DISMISS projection이고 native action은 전체 목록의 존재 여부다.
UNKNOWN·누락·custom·다른 객체에 결합된 metadata는 기존 앞선 guard에서 거부되므로 실제 interactive 자식으로 잘못 승격하지 않는다.

probe는 최초 코드만 보존하고 trace는 실제 offer ATTEMPT별 최초 관련 거부 한 건만 출력한다.
일반 display 진단의16건 한도에 도달했어도 이 최초 거부를 한 번 기록할 수 있다. 쓰기 전에 latch를 세우므로 출력 예외/취소 시 재출력하지 않는다.
기존 실제 취소·실패·cleanup 전파에는 관여하지 않는다. 진단의 예외만 기존 diagnosticWrite 경계에서 격리한다.

분류와 formatter는 src/debug에만 있다. src/release에는 null을 반환하는 variant 연결 stub만 있으며 enum vocabulary/formatter/Logcat 호출이 없다.
Release ManualPromptLog는 계속 trace를 생성하지 않으며 Demo bounds 기능은 OFF다.

## 관측이 입증하는 것과 입증하지 않는 것

원본은 C 표시 전 수집에서, fresh는 표시 중 재검증에서 얻은 DTO다. 기존 SHOWN 및 review 로그로 전후 순서를 알 수 있고 기존 bounds 진단으로 관측상 변화를 설명할 수 있다.
이것은 원자적인 전후 프레임이나 동일 native 세대 인증이 아니다. C가 원인인지, 팝업 애니메이션/지연 layout/OEM 캐시가 원인인지 단순 시간 순서로 증명하지 못한다.
기존 NODE_MISMATCH/BOUNDS_NODE는 전체 DTO의 최초 불일치다. 새 INTERACTIVE_NODE는 실제 정책이 거부한 자식이며 서로 다른 노드일 수 있다.
최초 거부 뒤 다른 노드의 변화는 새 필드로 모두 열거하지 않는다.

## A/B 결정 기준

- CLOSE_CANDIDATE 또는 OTHER_CLICKABLE이면 자식 passive 허용을 넓히지 않는다. B(표시 전 bounded 안정성 확인)를 검토하되 고정 sleep/TTL 연장/기존 snapshot의 token·시각 복사로 승인하지 않는다.
- NONCLICK_NATIVE_CAPABILITY는 A(역할과 capability가 검증된 자식의 표시 전용 허용)를 검토할 후보일 뿐이다. 이 코드만으로 container/본문, SELECT 계열, 무해한 동작임을 알 수 없으므로 action 집합·역할·부모 관계·후보 배제·가림의 별도 증명이 필요하다. 증거가 없으면 계속 거부한다.
- UNKNOWN 또는 앞선 metadata 거부면 A를 적용하지 않는다. 수집 결합/완전성을 먼저 검토한다.
- B도 C 표시 후에만 발생하는 변화까지 해결한다고 보장할 수 없다. 기존 gate는 이미 fresh F0와 dispatcher 경계 확인을 하므로 단순 한 번 더 읽기/시간 지연은 안정성 증명이 아니다.

전체 translation에서는 버튼의 동반 이동을 허용하는 기존 동작을 이번 진단에서 바꾸지 않았다. 향후 '버튼 이동은 표시 예외에서도 거부'를 구현할 때 해당 기존 경로도 함께 검토해야 한다.

## 안전 조건과 검증

기존 표시 승인/거부 조건, ROOT·자식 capability 범위, 원본 token/수집 시각/5초 TTL, 실행 증거 retire, 새 F0/B/F9/SCREEN_MATCH/최대1회 클릭, Release 엄격 정책은 변경하지 않았다.
Cleanup RETIRED_ID_EVENT/ADDED/HEALTH는 별도 미해결 장애이며 여전히 차단한다.
표시가 없어지는 문제와 실제 닫기 성공을 이번 작업이 해결했다고 주장하지 않는다.

새 회귀는 실제 공유 selector/registered-read/policy/probe/report 경로를 기존 fixture로 재사용한다.
닫기/다른 버튼/native capability, 복합 원인, 최초 자식, UNKNOWN·foreign metadata·정보 누출, 진단 ON/OFF 결과 동등성, sink 실패·취소·cleanup, ATTEMPT당1회와 Release 무동작을 검증한다.
최종 로컬 검증 결과는 작업 보고에 기록한다. Android 실측 결과와 JVM 합성 결과를 혼동하지 않는다.

### 이번 로컬 검증 결과

- 신규 Debug 회귀18개, Release 무동작 회귀2개. 앱 전체 Debug1,480개 / Release1,449개, 실패·오류·skip0.
- 호스트55개·합성 앱26개 JVM은 기존 결과를 Gradle UP-TO-DATE로 재사용했으며 재실행했다고 주장하지 않는다.
- JDK17 offline 전체 작업 BUILD SUCCESSFUL(4분17초). 앱 Debug/Release APK 및 AndroidTest APK 처리 통과. 앱 Debug/Release·호스트 Debug Lint issue0.
- 기존 패키징/DEX 격리 검사 통과. 신규 분류 enum, formatter 문구와 조합 문구는 Debug에만 있고 Release에는 없다. Release bytecode는 수집 객체를 읽지 않고 null을 반환하는 연결 stub이다. 신규 JVM 시험은 운영 APK DEX에 없다.
- 실제 source/판정 비교와 예외·취소 회귀 통과. 최초 대상 실행 중 진단 ON/OFF의 진단 사유 자체까지 같아야 한다는 잘못된 시험 assertion을 수정했으며, 실제 verdict/제거/실행 증거의 동일성을 검증한다. 최종 전체 회귀는 모두 통과했다.
- CRLF-aware working/index 공백 검사와 신규 파일의 실제 space/tab 후행 공백 검사 통과. HEAD/index 보존, 기존 구조 검토 문서와 다른9개 worktree의 HEAD/status/미커밋 파일 hash 보존 확인.
- 실행 기록은 저장소 밖 `/workspace/a-p01-interactive-target.log`, `/workspace/a-p01-interactive-full.log`에 있다. 커밋·푸시·삼성 재실행 없음.
