# 팀원별 연동 방법

이 문서는 A 1차의 **공통 계약 v1 제안**입니다. [개발 규칙](../AGENTS.md)과 [최신 결정](README.md)을 먼저 읽습니다. B·C·D는 불변 DTO와 인터페이스만 사용하며 `AccessibilityNodeInfo`, `AccessibilityEvent`, `Bitmap`을 보관하거나 전달하지 않습니다. 공통 계약 변경은 영향 범위를 적은 PR에서 팀 리뷰로 합의합니다.

## 패키지 소유권과 브랜치

소스 루트는 `app/src/main/java/kr/closeforyou`이며 현재 하나의 Android `app` 모듈입니다. 패키지 경계가 별도 Gradle 모듈의 접근 제어를 대신하지 않으므로 다른 담당자의 구현에 직접 의존하지 않습니다.

| 담당 | 브랜치 | 패키지 | 책임 |
|---|---|---|---|
| A 우진 | 1차 `feature/core` 병합, 2차 `feature/core-capture-ocr` | `accessibility`, `app` | 서비스·노드/캡처 소유권, 보호, OCR, 후보 감지, 토큰, 동의 설정·전체 조립 |
| 공동 계약/A 제안 | PR로 합의 | `core/model`, `core/contract` | Android 객체가 없는 모델과 포트 |
| B 준영 | `feature/decision` | `decision` | 분류·안전 판단·대상 후보, A 행동 게이트를 통한 실행 요청 |
| C 재호 | `feature/ui-voice` | `ui/voice` | TalkBack 안내·사용자 선택·누르고 말하기 명령, 추후 설정 UI |
| D 동준 | `feature/database-qa` | `data` | Room, 지문·규칙·허락 취소, QA·시험 앱·성능 측정 |
| 공용 시험용 | 담당 PR에서 협의 | `testing/mock` | 합성 fixture, 실행하지 않는 Mock, 연동 예제 |

`app/MainActivity.kt`와 `app/CorePreferences.kt`의 설정 화면은 A의 최초 실행·동의·분석 중지 기능입니다. C가 설정 UI를 확장할 때 이 조립 경계를 함께 리뷰합니다. `CorePreferences`는 수집 동의·일시정지·앱별 허용만 저장하며 D의 F12 팝업 선택 기억을 대체하지 않습니다.

## 공통 모델

[PopupModels.kt](../app/src/main/java/kr/closeforyou/core/model/PopupModels.kt)에 정의합니다. 컬렉션은 `ImmutableList`/`ImmutableSet`을 사용합니다. 작성 중인 가변 컬렉션은 `toPersistentList()`/`toPersistentSet()`으로 복사한 뒤 전달합니다. 불변 데이터여도 화면 원문이 포함되므로 로그·DB·예외·분석 도구에 통째로 출력하면 안 됩니다.

| 모델 | 의미·주의 |
|---|---|
| `PopupSnapshot` | 현재 화면의 임시 노드·창 DTO, 시각·범위·토큰·완전성. 장기 저장 금지 |
| `PopupAnalysis` | 추출 글자와 출처/품질·닫기 후보·분석 한계·영상 신호. 광고 표시와 실행 허가는 별개 |
| `CloseCandidate` | 경로/ID/라벨/범위·E1~E4 근거·역할. 노드 핸들이나 실행 권한이 아님 |
| `PopupClassification` | `ADVERTISEMENT`, `NOTICE`, `USER_DECISION`, `UNKNOWN` |
| `ActionDecision` | `DENY`, `NEEDS_CONFIRMATION`, `ALLOW_ONCE`와 사유·토큰·대상 ID |
| `ActionResult` | 미실행/차단/오래됨/실패/기계적 성공/불명. 팝업 소멸이 의미상 안전함을 보장하지 않음 |
| `UserChoice` | 이번 선택·버튼 의미 확인·동일 화면 반복 자동화의 별도 명시 허락 |
| `PopupRule` | 정확한 `RuleKey`, 선택·허락·버전·만료·실패·활성 상태. 화면 원문을 저장하지 않음 |

`SnapshotToken`은 화면 버전·창·패키지·팝업 인스턴스·구조/본문 지문·캡처 세대로 분석을 묶습니다. A의 지문은 세션별 salt를 사용하며 D의 영구 규칙 지문으로 재사용하지 않습니다. D의 `PopupFingerprintProvider`는 기기 내부 키 기반 지문을 별도로 설계합니다. `RuleKey`에는 패키지·앱 버전·구조·정확한 본문·대상 지문이 모두 포함됩니다. 날짜·숫자가 바뀌면 같은 공지로 취급하지 않습니다.

## 공통 인터페이스

[PopupContracts.kt](../app/src/main/java/kr/closeforyou/core/contract/PopupContracts.kt)의 계약을 구현합니다.

| 담당 | 포트 | 연동 방식 |
|---|---|---|
| A → 전원 | `SnapshotSource` | `events: Flow<PopupEvent>` 수집, `currentToken()`으로 현재 유효성 확인 |
| A | `EvidenceExtractor` | 2차 `AccessibilityRuntime.extract`: 완료한 tree/OCR 근거만 제공. 노드·비트맵을 경계 밖으로 전달하지 않음 |
| A | `CaptureCapability` | 2차 Runtime에서 최신 토큰·별도 허락·서비스 능력·예산 확인. 가용성이 캡처나 자동 실행의 허가는 아님 |
| B | `PopupClassifier` | 분석 결과에서 표시 분류만 반환. 클릭·제스처·포커스 변경 금지 |
| B | `ActionPolicy` | 현재 토큰·사용자 선택·일치 규칙·사용자 조작·안내 가용성을 확인해 결정 |
| A 게이트 / B 요청 | `ActionExecutor` | 현재 화면을 다시 읽어 1회 실행할 미래 경계. 현재 Mock은 아무 행동도 하지 않음 |
| C | `PopupAnnouncer` | 안내·다시 듣기·중지. 재생 상태와 사용자의 청취·이해를 구분 |
| C | `VoiceCommandInput` | 말하기 버튼에서만 시작, `Flow<VoiceEvent>` 처리, 취소·지원 불가 대안 |
| D | `PopupRuleRepository` | 정확 일치·만료 조회, 저장, 철회, 전체 삭제, 규칙 관측 |
| D | `PopupFingerprintProvider` | 현재 화면·대상·앱 버전에서 기기 내부 지문 생성 |
| D | `SafeDiagnostics` | 고정 코드·사유만 기록. 패키지·본문·노드·지문을 인자로 받지 않음 |

`PopupEvent.Candidate`는 보호 검사를 통과한 **팝업 후보**입니다. 광고 판정이나 자동 클릭 허가가 아닙니다. `Blocked`는 보호 차단 코드만, `Invalidated`는 새 세대만 전달합니다. 이벤트 스트림은 화면 원문을 재생하지 않는 일시적 스트림입니다. 구독 전에 발생한 후보를 복구하기 위해 별도 영구 캐시를 만들지 않습니다.

실제 A 포트는 `accessibility/AccessibilityRuntime`입니다. `SnapshotSource`로 주입해 `events`를 구독합니다. `status`는 연결 여부·세대·후보 수·차단 코드만 제공하고 화면 원문은 포함하지 않습니다. 서비스는 시작·설정 변경·앱/화면/스크롤/포커스/사용자 조작·해제 때 토큰을 무효화합니다. 현재 후보 TTL은 5초이고 스트림은 replay 0, 버퍼 1입니다. 느린 소비자는 중간 이벤트를 놓칠 수 있으므로 `currentToken()` 확인과 실제 실행 전 재검사가 필요합니다.

비동기 소비자는 `collectLatest`로 이전 작업을 취소하고, `Blocked`/`Invalidated`를 받으면 진행 중 분석·안내·미실행 요청을 취소하고 참조를 놓습니다. 구독 해제·예외·설정 철회·앱 전환에서도 같은 원칙을 지킵니다. `currentToken()` 일치는 추가 검사일 뿐입니다. **토큰 비교만으로 실제 화면 재검사·보호 검사·사용자 허락을 대체하지 않습니다.**

## A 2차 캡처/OCR 연동

공통 데이터 모델·인터페이스·enum의 시그니처는 유지했습니다. 실제 Runtime을 `SnapshotSource`, `EvidenceExtractor`, `CaptureCapability`로 주입합니다. `IntegrationExample.collectBDecisionsExample`의 `source`와 `extractor`에 모두 `AccessibilityRuntime`을 전달할 수 있습니다. Mock은 계속 시험 전용입니다.

캡처는 기존 동의와 별도인 캡처 설명 동의·앱별 허용, 신뢰 가능한 팝업 영역과 OCR 필요성이 있어야 합니다. F0-A를 다시 확인하고 F0-B를 마치기 전 외부 Candidate를 발행하지 않습니다. 민감 징후는 Blocked, 읽기 실패/빈 OCR/불명 영역은 최신 F0-A를 통과한 트리 근거만 제공합니다. `INCOMPLETE_EVIDENCE`를 자동 허가로 승격하지 않습니다.

완료 분석 DTO 하나를 후보 시작부터 5초 TTL 안에서만 임시 보유하고 `extract`는 이를 반환합니다. OCR 후 TTL을 새로 연장하지 않으므로 B가 사용할 남은 시간은 5초보다 짧을 수 있습니다. 무효화/차단/서비스 해제 시 참조를 즉시 놓습니다. 캡처/Task 취소와 메모리 수명·지원 제한은 [캡처/OCR 문서](CAPTURE_OCR.md)를 따릅니다. `status.lastEvidenceIssue`는 고정 오류 코드이며 원문이나 이미지를 로그로 요청하지 않습니다.

OCR 출처는 `OCR`, 점수는 현재 `null`입니다. 같은 출처·위치·내용 중복만 정리하며 tree/OCR이 일치해도 두 출처를 보존합니다. OCR 본문만으로 광고·안전한 버튼·자동 허가를 판단하지 않습니다. F0-A/F0-B 통과는 개인정보 부재의 증명이 아닙니다.

## B: 분류·안전 판단

1. `PopupAnalysis`의 근거와 한계를 입력으로 받아 `PopupClassifier`를 구현합니다.
2. 최초 화면·중요 선택·라벨 불명·사용자 조작·불완전 근거·오래된 토큰은 금지 또는 사용자 확인으로 처리합니다. 표시 분류가 광고여도 자동 허가하지 않습니다.
3. D의 정확 일치 규칙과 C의 명시 선택을 `DecisionContext`에 넣어 `ActionPolicy`를 호출합니다. `STOP_ASKING`은 자동 닫기 허락이 아닙니다.
4. 승인된 실행 요청만 A의 `ActionExecutor`에 넘깁니다. B 안에서 `performAction`, `dispatchGesture`, `performGlobalAction`을 호출하지 않습니다.

현재 `DenyAllActionPolicy`는 항상 금지하고 `NeverExecuteActionExecutor`는 아무것도 누르지 않습니다. 실제 클릭·직전 재탐색·한 번 실행·결과 관측은 후속 A/B 통합 작업입니다. 실패 후 좌표 탭·뒤로 가기·무조건 재시도를 추가하지 않습니다. F5 영상 신호와 광고 여부는 분리하고 F6 영상 줄거리 추론은 구현하지 않습니다.

## C: 안내·음성·선택

`PopupAnnouncer`와 `VoiceCommandInput`을 구현합니다. `AnnouncementState`의 `UI_UPDATED`, `FOCUS_OBSERVED`, `TTS_COMPLETED`는 사용자가 들었거나 이해했다는 증거가 아닙니다. `USER_ACKNOWLEDGED`도 별도 사용자 응답으로만 기록합니다. 팝업 안내·다시 듣기·그대로 두기·수집 중지가 실제 TalkBack에서 탐색 가능한지 확인합니다.

`startFromSpeakButton(token)`은 사용자가 말하기 버튼을 누른 경로에서만 호출합니다. API 31+ 온디바이스 인식 지원 검사·마이크 권한·취소 처리가 필요하며 지원되지 않으면 명시적인 버튼 대안을 제공합니다. 기본 원격 인식·상시 듣기를 사용하지 않습니다. 인식한 명령도 `ActionPolicy`와 A 게이트를 통과해야 하며 ‘닫아’가 결제·동의·구매를 승인하지 않습니다.

`VoiceCommand`는 닫기·건너뛰기·읽기·그대로·돌아가기·버튼 이동·그림 설명을 표현합니다. F6 영상 내용 설명 명령은 없습니다. `GO_BACK` 값은 명령 표현일 뿐 현재 뒤로 가기를 실행하지 않으며, 미래에도 사용자 요청과 안전 조건을 검사해야 합니다. 현재 음성 관련 Mock은 `NOT_IMPLEMENTED`를 반환하고 마이크·TTS를 사용하지 않습니다.

## D: 기억·QA

`PopupRuleRepository`를 Room으로 구현하고 `PopupFingerprintProvider`로 세션 간 비교 가능한 기기 내부 키 지문을 설계합니다. 원본 화면·전체 공지·노드 라벨을 기본 저장하지 않습니다. 조회는 정확한 본문·구조·대상·앱 버전 일치, 활성 여부, 만료를 확인하고 철회·전체 삭제를 제공해야 합니다. 규칙 일치 결과만으로 실행을 허가하지 않습니다.

`InMemoryMockPopupRuleRepository`는 프로세스 메모리만 사용하는 개발용 대체물입니다. Room, 암호화, 백업·삭제 보장·스키마 마이그레이션을 구현했다고 해석하지 않습니다. `FixtureOnlyFingerprintProvider`도 합성 fixture용이며 실제 화면 지문 생성기로 쓰지 않습니다. 단위 시험과 별도로 B01/B05/C01/C08/C11 및 [개발 상태의 시험 표](DEVELOPMENT_STATUS.md)를 진행하고 실험 양식에 기기/OS/TalkBack·예상·관측·미실시를 기록합니다.

## Mock과 실행 예시

`testing/mock/MockPopupFixtures.kt`는 실제 개인정보가 없는 합성 화면을 제공합니다.

| fixture | 검증 의도 |
|---|---|
| `advertisement()` | 라벨 있는 광고 후보여도 최초 자동 허가 금지 |
| `notice()` / `changedDateNotice()` | 날짜·본문 변경 시 정확 일치 규칙 재사용 금지 |
| `decision()` | 중요 선택은 음성·사용자 요청이어도 안전 정책 적용 |
| `unlabeledButton()` | 라벨 없는 버튼의 형태만으로 자동 실행 금지 |
| `videoWithoutAdEvidence()` | 영상 관측을 광고로 확정하지 않음, F5 유지 |
| `privacyBlocked()` | 민감 화면은 화면 DTO 대신 차단 코드 전달 |

[`testing/mock/IntegrationExample.kt`](../app/src/main/java/kr/closeforyou/testing/mock/IntegrationExample.kt)에 B·C·D 포트를 사용하는 suspend 예제와 `collectBDecisionsExample` 이벤트 수집 예제를 제공합니다. 예제는 서비스의 런타임 행동 경로에 연결되지 않으며 안전한 Mock만 사용합니다. 각 담당자는 fixture로 먼저 개발한 뒤 실제 포트를 앱의 조립 위치에서 교체합니다. Mock의 결정은 정답 분류 엔진이나 실제 음성 출력·자동화 성공 결과가 아닙니다.

아래는 suspend 함수 또는 테스트 coroutine 안에서 실행하는 합성 데이터 예시입니다. 이 선택은 예제용 허락 값이며 실제 UI에서 받은 동의로 바꿔야 합니다.

```kotlin
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.*

val fixture = MockPopupFixtures.notice()
val decision = IntegrationExample.runBExample(fixture)
check(decision.authorization == ActionAuthorization.DENY)

val receipt = IntegrationExample.runCExample()
check(receipt.state == AnnouncementState.NOT_IMPLEMENTED)

val repository = InMemoryMockPopupRuleRepository()
val choice = UserChoice(
    token = fixture.snapshot.token,
    kind = ChoiceKind.REMEMBER_EXACT_POPUP,
    candidateId = fixture.analysis.closeCandidates.single().id,
    buttonMeaningConfirmed = true,
    repeatAutomationExplicitlyAllowed = true,
)
val rule = IntegrationExample.saveExplicitChoiceExample(
    fixture, choice, repository, nowEpochMillis = 1_000L,
)
check(repository.findExact(rule.key, nowEpochMillis = 2_000L) != null)
val changed = MockPopupFixtures.changedDateNotice()
val changedKey = FixtureOnlyFingerprintProvider().createKey(
    changed.snapshot, changed.analysis.closeCandidates.single(), appVersionCode = 1L,
)
check(repository.findExact(changedKey, nowEpochMillis = 2_000L) == null)
repository.revoke(rule.id)
check(repository.findExact(rule.key, nowEpochMillis = 2_000L) == null)
```

예제 호출과 반환 상태는 코드의 함수명·타입을 기준으로 확인하세요. `PopupSnapshot`이나 `PopupAnalysis` 전체를 `println`, `Log`, 예외 메시지·분석 도구 인자로 넘기지 않습니다. 개발 편의를 위한 노드 캐시·전체 화면 로그를 추가하지 않습니다.

## PR 전 확인

담당 브랜치에서 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`를 실행하고 변경·검증·한계와 공통 계약 변경 여부를 PR에 기록합니다. 빌드 실패를 해결한 뒤 리뷰를 요청합니다. 실기기에서 확인하지 않은 기능은 미검증으로 적고 단위 시험 결과와 구분합니다. `main`에는 직접 병합하지 않습니다.
