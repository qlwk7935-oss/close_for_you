# F12 사용자 선택 기억 저장소 (D)

담당: 동준/D. `PopupRuleRepository`와 `PopupFingerprintProvider`의 실제 구현입니다. 저장소는 PR #15로 main에 병합되었습니다.

**상태:** CI(빌드·JVM 단위 시험·lint)는 통과했습니다. 계측 시험(`PopupRuleDatabaseTest`)은 CI에서 컴파일만 하며 기기 실행은 아직 하지 않았습니다. 서비스 경로(A/B) 연결은 별도 PR입니다.

## 구성

| 파일 | 내용 |
|---|---|
| `data/rules/PopupRuleEntity.kt` | Room 테이블 `popup_rules`. 지문·선택·허락·만료·실패·활성만 저장 |
| `data/rules/PopupRuleDao.kt` | 정확 일치 조회, 교체(같은 ID·같은 키 삭제 후 삽입), 철회, 실패 기록, 만료·손상 행 정리, 전체 삭제 |
| `data/rules/CloseForYouDatabase.kt` | DB 버전 1 (`closeforyou_rules.db`) |
| `data/rules/RoomPopupRuleRepository.kt` | `PopupRuleRepository` 구현. SQL 조건을 Kotlin에서 한 번 더 확인 |
| `data/fingerprint/DeviceKeyedFingerprintProvider.kt` | 기기 내부 키 HMAC 지문 |
| `data/fingerprint/KeyedHasher.kt` | Android Keystore HMAC-SHA256 키(내보낼 수 없음) |
| `data/RuleStorage.kt` | 조립 지점, 정리(`cleanUp`), "기억 전체 삭제"(규칙 삭제 + 지문 키 폐기) |

의존성: Room 2.7.1 + KSP. 루트와 `app/build.gradle.kts`에 플러그인·의존성을 추가했습니다(A 리뷰 완료).

## 지문

A의 `SnapshotToken` 지문은 세션 salt를 쓰므로 재사용하지 않습니다. `EvidenceExtractor`로 현재 F0-B 완료 분석을 다시 읽어 기기 키로 HMAC합니다.

- **본문**: 트리 글자·설명과 OCR 글자. NFC·공백 정리만 하고 숫자·날짜·금액은 그대로 둡니다. 날짜 하나만 달라도 다른 공지입니다.
- **구조**: 창 종류, 노드 경로·클래스·리소스 ID·클릭 가능 여부, 닫기 후보들의 역할·근거·상대 위치, 글자 출처.
- **대상**: 역할·근거·리소스 ID·라벨·노드 경로·상대 위치(팝업 창을 20×20 칸으로 나눈 위치). 버튼이 옮겨지면 달라집니다.
- 패키지 이름·앱 버전은 `RuleKey`에 그대로 들어갑니다. 앱이 업데이트되면 이전 기억은 일치하지 않습니다.

지문을 만들지 않는 경우(`FingerprintUnavailableException`, 호출자는 "기억 불가"로 처리):

| 코드 | 상황 |
|---|---|
| `TRUNCATED_SNAPSHOT` | 잘린 스냅샷 |
| `STALE_ANALYSIS` | 분석 토큰이 현재 스냅샷과 다름 |
| `INCOMPLETE_EVIDENCE` | 분석 한계에 `INCOMPLETE_EVIDENCE` 또는 `MOCK_ONLY` |
| `VISUAL_ONLY_TARGET` | E4(노드 없는 그림 X) 또는 노드 경로 없음 |
| `UNKNOWN_TARGET` | 대상이 현재 분석의 닫기 후보가 아님 |
| `IMAGE_TEXT_UNVERIFIED` | 라벨 없는 이미지가 있는데 OCR 글자가 없음 |
| `NO_CONTENT` | 결박할 글자가 없음 |
| `INVALID_APP_VERSION` | 앱 버전이 0 이하 |

## 저장 조건 (팀 합의 10/21)

- **만료일 필수, 최대 30일**: `RuleRetention.defaultExpiry(생성 시각)`으로 30일 뒤를 넣습니다. 만료일이 없으면 `MISSING_EXPIRY`, 30일을 넘으면 `EXPIRY_TOO_LONG`으로 저장을 거부합니다(`RuleRejectedException`).
- **허락 저장과 실행 금지 분리**: 사용자의 반복 허락(`repeatAutomationExplicitlyAllowed = true`, C의 [앞으로 항상 닫기])은 그대로 저장합니다. 자동 반복 **실행**은 `RepeatAutomation.EXECUTION_ENABLED = false`인 동안 B 정책·A 게이트가 막습니다.
- C의 [기억하기]는 저장 의사입니다. **실제 클릭이 필요한 기억은 A가 실행 성공을 확인한 뒤에만 `save`를 호출**합니다(호출하는 쪽 책임).
- **지문(`RuleKey`)은 클릭 전에 만듭니다.** `createKey`는 현재 분석의 닫기 후보를 다시 확인하므로, 클릭 뒤 팝업이 사라진 화면에서는 항상 `UNKNOWN_TARGET`으로 실패합니다. 클릭 전 같은 토큰에서 키를 만들어 두고, A가 실행 성공을 확인하면 그 키로 `save`, 실패·미실행이면 버립니다(B 리뷰 10/21).
- 기억으로 실제 실행을 시도했다가 실패하면 A의 결과를 받은 B가 `recordFailure`를 호출합니다. 실행하지 않은 경우는 성공으로 취급하지 않고 따로 구분합니다.
- `INCOMPLETE_EVIDENCE`는 A가 일괄 제거하지 않습니다. "근거가 충분한 경우" 기준을 A·D가 합의하기 전까지 지문 생성은 계속 실패(기억 불가)합니다.
- `INCOMPLETE_EVIDENCE` 해제 기준은 F0 보호 검사, 화면·토큰 현재성, 필요한 본문 근거의 완전성을 함께 봅니다.
- 닫기 후보는 B가 같은 토큰에 붙인 분석을 지문 생성기의 `EvidenceExtractor`로 전달합니다(연결: 준영, 런타임 조립: 우진). D는 후보 탐지를 구현하지 않습니다.

## 조회 규칙

`findExact`는 다섯 키(패키지·버전·구조·본문·대상)가 모두 같고, 활성이며, 실패 0회, 스키마 1, 만료일이 있고 `생성 ≤ 현재 < 만료`일 때만 규칙을 돌려줍니다. 규칙이 일치해도 실행 허가가 아니며 B가 정책·최신성·사용자 허락을 다시 확인합니다.

- `save`: 같은 ID나 같은 키의 이전 선택을 지우고 새 선택만 남깁니다(철회 후 옛 허락 부활 방지).
- `revoke`: 비활성으로 남겨 목록에 "해제됨"으로 보이게 합니다.
- `recordFailure`: 기억으로 실행했다가 실패하면 호출. 이후 그 규칙은 일치하지 않습니다. **멱등**: 횟수를 더하지 않고 `failureCount = 1`로 표시하므로 같은 실패가 중복 전달되거나 앱 재시작 뒤 다시 와도 결과가 같습니다(A 리뷰 #23, 10/22). 실행 시도 identity는 D가 저장하지 않습니다.
- `purgeExpired`: 만료 행과 만료일이 없는 행 삭제. 조회는 호출하지 않아도 이런 행을 무시합니다.
- `cleanUp`: 아래 "정리" 참고.
- `RuleStorage.eraseAll`: 모든 규칙 삭제 + Keystore 지문 키 폐기.

## 정리 (C 리뷰 10/22)

`RuleStorage.cleanUp(context)` → `RoomPopupRuleRepository.cleanUp(now)`. 결과는 지운 개수(`RuleCleanUpResult(expired, unreadable)`)뿐이며 로그를 남기지 않습니다.

| 행 | 정리 |
|---|---|
| 만료됨(`만료 ≤ 현재`) | 삭제 |
| 만료일 없음 | 삭제(저장이 거부되므로 정상 경로로는 생기지 않음) |
| 읽을 수 없는 손상 행(알 수 없는 선택 값 등) | 삭제. 목록에 안 보여 사용자가 지울 방법이 없기 때문 |
| 해제됨·실패로 꺼짐 | 만료 전까지 남김(사용자가 목록에서 상태 확인) |
| 다른 스키마 버전(읽을 수 있음) | 만료 전까지 남김 |

- **호출 시점(제안)**: A 서비스 시작 시 한 번(우진), C 설정 화면을 열 때 한 번(재호). 조회 안전성은 호출 여부와 무관합니다.
- 손상 행 삭제는 ID를 500개씩 나눠 지웁니다(SQLite 바인딩 변수 한도).
- C 쪽 권장: 목록이 비어도 [전체 삭제]는 보이게 둡니다(정리 전 손상 행 대비).

## 시험

- JVM: `DeviceKeyedFingerprintProviderTest`(세션 간 동일, 날짜·시간·금액 변경, 버튼 이동·라벨 변경, 앱 버전, 기기 키, fail-closed 8종), `RoomPopupRuleRepositoryTest`(정확 일치, 만료, 철회, 교체, 실패, 정리, 손상 행, 정리 묶음 크기).
- 계측: `PopupRuleDatabaseTest`가 실제 Room SQL로 같은 시나리오(정리 포함)를 검사합니다. CI는 컴파일만 하며 실행은 기기에서 해야 합니다.
- 팝업 재현 앱의 공지 변형 N01~N07(C05·C09)로 실기기 검증 예정.

## F12 연결 PR 때 정할 것 (B 리뷰 10/21)

- **`recordFailure` 전달**: A 조립에서 B에 실패 기록 콜백을 넘기는 방향(우진 제안 10/21).
- **실패로 볼 결과**: `ATTEMPT_FAILED`, `OUTCOME_UNKNOWN`(실제로 눌렀을 수 있음)은 실패로 기록합니다. `NOT_EXECUTED`·`BLOCKED`·`STALE`(누르지 않음)은 실패도 성공도 아닙니다(A·B 합의 10/21).
- **규칙이 맞은 후보**: 닫기·건너뛰기가 함께 있으면 B는 후보가 1개가 아니어서 막습니다. 후보별 키로 `findExact`가 맞은 후보 id를 `DecisionContext`로 넘기는 방안(공통 계약 변경, 별도 PR).
- **[다시 묻지 않기]의 대상 없는 키**: `createKey`는 대상 버튼이 필수입니다. 닫기 버튼이 없는 팝업용 키를 어떻게 만들지는 C가 이 기능을 켤 때 정합니다(현재 C에서 꺼짐).

## 남은 일

- A/B 서비스 경로 연결: `InMemoryMockPopupRuleRepository`·`FixtureOnlyFingerprintProvider` 대체.
- C의 규칙 목록·해제·전체 삭제 UI 연동(`RuleStorage.eraseAll`, `RuleStorage.cleanUp`).
- 스키마 변경 시 Migration과 스키마 JSON 커밋.
- `INCOMPLETE_EVIDENCE` 해제 기준(A·D 합의).
- 실기기 계측 실행, C09 시나리오(앱 업데이트·버튼 위치 변경) 추가.
