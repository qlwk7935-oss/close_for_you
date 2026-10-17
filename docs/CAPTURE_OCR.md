# A 2차: 화면 캡처와 한국어 OCR

2026-10-17 검토 수정. PR #1을 병합한 `main`의 `f52be56`에서 `feature/core-capture-ocr`를 생성했습니다. 실제 캡처·한국어 인식의 기기별 성공과 정확도는 **미검증**입니다. JVM 시험이나 계측 APK 빌드 성공을 기기 시험 성공으로 해석하지 않습니다.

## 실행 경로

`CloseForYouAccessibilityService` → `AndroidSnapshotCollector` F0-A → 후보 감지 → 토큰 시작/5초 TTL → `CaptureOcrPipeline` → 최신 트리로 F0-A 재검사 → 캡처 동의·앱 허용·영역·필요성 검사 → `AndroidScreenshotManager` → 영역 제한·빈 이미지 검사 → 최신 F0-A/토큰 재검사 → `KoreanMlKitOcr` → 최신 F0-A/토큰 재검사 → `OcrPrivacyReview` F0-B → `PopupAnalysis` → `AccessibilityRuntime.publish`.

실제 민감 징후 검사를 통과한 트리에 중요한 결정 문구가 있으면 추가 캡처 없이 고정 요약만 반환합니다(아래 책임 분리 참고). 나머지 캡처 대상이 아니면 안전한 트리 근거로 분석을 반환합니다. 캡처 대상에서는 F0-B가 끝나기 전에 `Candidate`를 외부에 발행하지 않습니다. 민감 징후가 있으면 `Blocked`만 발행합니다. 캡처/OCR 실패·인식 불확실성에서는 OCR을 포함하지 않는 트리 분석만 반환하되, 최신 화면·F0-A를 재확인할 수 없으면 전체를 차단합니다. 화면 변경·사용자 터치·스크롤·포커스·설정 철회·서비스 중단/해제·회전은 작업 취소와 토큰 무효화를 일으킵니다.

## 별도 허락과 캡처 영역

운영체제에서 수신되는 화면/접근성/터치 이벤트와 최신 트리 비교로 무효화를 수행합니다. 이벤트도 트리 변화도 없는 이미지 픽셀 변화, 모든 원시 터치의 관측을 보장할 수는 없습니다. TalkBack/제조사별 이벤트 전달을 반드시 실측하며, 이 제한을 해소했다고 가정해 자동 실행을 허가하면 안 됩니다.

기존 노드 수집 동의와 앱 허용은 캡처 허락이 아닙니다. `CorePreferences`에 다음 설정을 추가했습니다. 기본값은 모두 꺼짐/빈 목록이며 이전 설치의 동의를 캡처 동의로 승격하지 않습니다.

- `captureConsentGranted`: 전체 화면 캡처 위험을 포함한 설명에 별도 동의
- `captureAllowedPackages`: 기존 분석 허용 앱 중 사용자가 이미지 글자 읽기를 켠 앱

일시정지는 두 경로 모두 중지합니다. 전체 동의 철회는 캡처 동의/앱 목록도 지우고, 앱 허용 취소는 해당 앱의 캡처 허락도 지웁니다. 개인정보가 포함될 수 있는 이미지를 파일·DB·로그에 쓰거나 업로드하는 경로는 없습니다.

영역은 현재 활성 application 창이 화면보다 확실히 작은 경우, 또는 단일 명시적 Dialog 클래스 노드의 범위가 창 안에 있는 경우에만 인정합니다. 화면적 92% 미만, 최소 32×24px, 화면/창 안에 완전히 포함, 위에서 가리는 창 없음이 필요합니다. 일반 컨테이너 변화·전체 화면 전환·여러 모호한 Dialog는 캡처 허가가 아닙니다. 원본 노드에 읽을 본문이 있고 라벨 없는 이미지가 없으면 캡처를 생략합니다. 풀스크린 이미지·캔버스, 다중 디스플레이, 확대 상태 등 좌표를 신뢰할 수 없는 경우는 현재 지원 제한으로 표시합니다. 향후 명시적 영역 선택·시각 분석이 필요합니다.

F0-A는 캡처 경로에서 다른 앱의 application 창이 함께 보이는 경우도 차단합니다. 같은 앱의 배경 창도 입력칸·비밀번호·민감 징후를 검사합니다. 이는 텍스트 없는 개인정보를 완벽히 찾는 검사가 아닙니다.

API 34+의 창 캡처는 공식 API 명세에 따라 접근성 오버레이의 픽셀을 제외합니다. 따라서 활성/포커스가 없는 `ACCESSIBILITY_OVERLAY`만 창 캡처 경로에서 가림 제외로 인정합니다. 활성/포커스가 있는 오버레이와 시스템·다른 앱 창은 계속 차단합니다. API 30~33의 전체 화면 캡처에서는 오버레이 픽셀이 섞일 수 있어 이 예외를 적용하지 않습니다. 실제 TalkBack 오버레이·제조사별 호환성은 미검증입니다.

## Android API와 자원 소유권

| 항목 | 구현 |
|---|---|
| API 30~33 | `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)` |
| API 34+ | `takeScreenshotOfWindow(windowId, mainExecutor, callback)` 우선, 실패 시 전체 화면으로 자동 대체하지 않음 |
| 메타데이터 | `res/xml`·`xml-v31` 모두 `canTakeScreenshot=true`; `canPerformGestures=false` 유지 |
| 실제 권한 검사 | 서비스 연결, `CAPABILITY_CAN_TAKE_SCREENSHOT`, 최신 토큰, 동의·앱 허락 확인. 메타데이터 선언은 사용자 동의의 대체가 아님 |
| 좌표 | API 30~33은 전체 디스플레이 좌표, API 34+는 창 좌상단 기준으로 ROI를 잘라 화면 좌표로 복원. 반환 픽셀 크기가 예상 화면/창 크기와 다르면 추측하여 보정하지 않고 거부 |
| 보안·오류 | secure window/접근 권한 없음/잘못된 창·디스플레이/빈 이미지/일반 실패/시간 초과를 고정 코드로 구분, 자동 재시도 없음 |
| 시간·횟수 | 캡처 콜백 1초, OCR 3초, 전체 후보 TTL 5초. 프로세스당 동시 1개, 최소 간격 2초, 1분당 6회, 최근 10분당 최대 30회(이동 시간창). 시스템 빈도 오류 후 6초 보류 |
| 픽셀 예산 | 원본 최대 800만 픽셀, ROI 최대 200만 픽셀. 소스 요청 전 검사와 실제 버퍼 수신 후 재검사. 단일 layer RGBA/RGBX 8888만 허용. 이론적 이미지 버퍼 약 48MB 이하; 정렬·SDK 내부 추론 메모리의 실제 피크는 기기 측정 필요 |

위 수치는 실측 전 임시 제한입니다. 한도에 도달하면 새 캡처를 보류하고 트리 근거만 제공합니다. **프로세스 누적 30회 제한은 제거**했습니다. `CaptureBudget`은 최대 30개의 요청 시작 시각만 메모리에 보유하며 `SystemClock.elapsedRealtime`(단조 증가, 절전 시간 포함) 기준 최근 10분의 기록을 검사합니다. 각 기록은 정확히 10분이 지나면 만료되어 서비스 재시작 없이 해당 슬롯이 회복됩니다. 예: 0~290초에 10초 간격으로 30회를 시작했다면 300초에는 `BUDGET_EXHAUSTED`, 600초에는 첫 슬롯이 회복됩니다. 실패한 캡처 요청도 예산을 소비하며 자동 재시도하지 않습니다.

회복은 다음 요청/가용성 검사에서 계산하며 예약 타이머·자동 캡처를 만들지 않습니다. 회복해도 동시 실행 중이면 `BUSY`, 최소 2초·최근 1분 6회·시스템 오류 후 6초 보류 중이면 `RATE_LIMITED`입니다. 자원 슬롯은 시간 경과로 강제로 풀지 않습니다. 동의·앱별 허용·서비스 능력·현재 토큰·메모리 검사는 매번 별도로 유지합니다. 시간창에는 원문·앱 식별자가 없습니다.

`HardwareBuffer`는 콜백 안에서만 소유합니다. `Bitmap.wrapHardwareBuffer` 후 ROI를 먼저 제한하고 CPU에는 ROI만 복사합니다. 원본/중간 Bitmap과 버퍼는 OCR 코루틴을 재개하기 **전** `finally`에서 해제합니다. API 30~33은 캡처 순간 팝업 밖 개인정보가 GPU 메모리에 들어올 수 있습니다. 밖의 픽셀을 OCR·CPU 픽셀 검사·외부 계약으로 전달하지 않지만, 그 순간의 캡처 자체를 없앨 수 있다는 보장은 하지 않습니다.

ROI는 `BitmapFrame`의 참조 수명으로 관리하며 마지막 사용 뒤 픽셀을 지우고 recycle합니다. Android 캡처 호출은 취소 API가 없으므로 취소/시간 초과 뒤 늦게 오는 콜백도 버퍼를 닫고 캡처 슬롯을 반환합니다. 늦은 콜백이 오기 전까지 슬롯을 유지하여 누적 캡처를 막습니다.

ML Kit의 native Task도 강제 취소 완료를 보장하지 않습니다. 취소/시간 초과 시 공개 결과·분석 참조는 즉시 폐기하고 recognizer 종료를 요청하지만, native Task가 읽는 ROI를 미리 recycle하면 안 됩니다. 따라서 Task의 내부 lease는 완료 콜백까지 유지하고 마지막에 지웁니다. 그동안 추가 캡처는 금지합니다. SDK가 완료하지 않으면 슬롯이 잠긴 채로 안전하게 중단되며 프로세스 종료가 필요할 수 있습니다. 즉시 native 메모리 해제를 보장한다고 주장하지 않습니다.

## OCR·F0-B·데이터 계약

`com.google.mlkit:text-recognition-korean:16.0.1`과 `KoreanTextRecognizerOptions`를 실제 사용합니다. Korean `Kore_ctc` 모델과 native OCR 라이브러리가 APK에 포함되는 것을 확인했습니다. Play Services 모델 다운로드나 원격 인식 대체 경로를 사용하지 않습니다. 병합 매니페스트에서는 전이 의존성이 인터넷 권한을 넣어도 제거하도록 설정했습니다.

OCR line의 영역을 화면 좌표로 변환하고 위→아래, 같은 높이는 왼쪽→오른쪽으로 정렬합니다. `source=OCR`, `confidence=null`이며 점수나 정확도를 만들어 내지 않습니다. 복잡한 다단 편집 순서와 인식 정확도는 미검증입니다. 트리 텍스트와 description도 `ACCESSIBILITY_TREE` 근거로 제공합니다. 같은 출처·내용·위치 중복은 정리하고, 서로 다른 출처의 동일 텍스트는 두 항목을 유지하여 출처를 잃지 않습니다. C는 낭독 시 표시 그룹을 만들 수 있지만 원본 근거를 합쳐 출처를 없애면 안 됩니다.

F0-B는 F0-A와 독립된 **텍스트 기반 보호 휴리스틱**입니다. 비밀번호·인증·계좌·카드·금융·로그인·개인 식별 문맥, 연락처/이메일 징후, 공백·줄바꿈으로 나뉜 긴 숫자열을 차단합니다. 문맥 없는 6~8자리 인증번호 가능 숫자, 미지원 문자, 빈 OCR, 낮은 점수, 범위 오류, 과도한 내용은 판단 불가입니다. 날짜·시간·일반 가격 자체를 광고나 결제로 분류하지 않습니다. 원문 개인정보가 섞인 노드 없는 이미지, OCR 누락·오인식, 얼굴/QR 등 비문자 민감 정보는 완벽히 검출하지 못합니다. F0-A/F0-B 통과는 개인정보 부재의 증명이 아닙니다.

### 개인정보 보호와 중요한 결정의 책임 분리 (PR #2 검토 수정)

| 경계 | 책임과 반환 |
|---|---|
| A / F0-A | 수집 동의·분석 허용 앱·보호 앱·비밀번호·모든 입력란·민감/불명 트리를 검사. 실제 보호 징후가 있으면 전체 차단하고 캡처하지 않음. `결제/구매/구독/동의/권한/삭제` 단어만으로 전체 분석을 차단하지 않음 |
| A / F0-B | 캡처가 필요했던 이미지의 OCR 전체를 좌표·품질·개인정보 기준으로 **다시** 검사. F0-A 통과 여부로 생략하지 않음. 민감하면 전체 차단, 불명확하면 OCR/이미지는 전혀 전달하지 않고 최신 F0-A를 통과한 트리만 유지 |
| A / 최소 결정 근거 | 보호 검사 후 `DecisionEvidence`가 `결제 선택 필요`, `구매 선택 필요`, `구독 선택 필요`, `동의 선택 필요`, `권한 허용 선택 필요`, `삭제 선택 필요`라는 고정 어휘만 전달. 원문/이름/금액/버튼 라벨/ID는 전달하지 않음 |
| B / 분류와 안전 판단 | 이 근거를 사용해 `USER_DECISION`으로 분류할 실제 엔진은 B의 TODO. 분류와 실행 허가는 별개이며 위험 결정 자동 실행은 계속 금지. 명령·사용자 동의·기억 규칙으로 금지를 우회하지 않음 |

예를 들어 `결제하시겠습니까? 10,000원`은 고정 요약 `결제 선택 필요`만 제공합니다. `결제 비밀번호`, 카드번호·계좌·인증번호·입력란이 섞인 경우에는 요약도 주지 않습니다. 일반 `개인정보 수집 및 이용에 동의` 라벨은 허용하되 이름·연락처 등 개인 필드/노출 징후가 함께 있으면 차단합니다. 비밀번호·로그인·카드·은행 등의 문맥만으로도 보호 불확실성을 고려해 보수적으로 차단하며, 모든 일반 안내를 허용한다고 보장하지 않습니다.

트리에서 결정 문구를 이미 알면 캡처/OCR을 추가 실행하지 않습니다. 이미지에서 처음 발견한 결정 문구는 F0-B 전체 검사를 마친 뒤 요약합니다. 이 경로의 `PopupAnalysis`는 `UNKNOWN`/`INCOMPLETE_EVIDENCE`, 닫기 후보 없음이며 `status.lastEvidenceIssue=DECISION_SUMMARY_ONLY`입니다. `ExtractedText`의 출처와 좌표는 근거를 보존하지만 **text는 원문 전사가 아닌 생성된 고정 요약**, confidence는 null입니다. 동시에 공개 `Candidate.snapshot.nodes`를 빈 목록으로 바꾸므로 기존 트리를 통한 원문·실행 대상 우회 노출도 없습니다. 원래 토큰·창 좌표는 무효화/검증 용도로만 유지합니다. 원본 트리는 A의 일시적 최신 화면 검사에만 사용합니다. 화면 변경·취소·5초 TTL은 요약 경로에도 그대로 적용합니다.

이는 개인정보 경계에서의 데이터 최소화이며 A가 B의 분류·실행 엔진을 구현한 것이 아닙니다. C는 부분 안내임을 표현하고 D는 요약이나 기존 트리 지문을 원문 동일성/반복 자동화 근거로 저장·승격하면 안 됩니다. 자세한 약관/가격/대상 선택 안내는 C/B의 별도 안전 연동 TODO입니다.

`PopupAnalysis`는 `UNKNOWN` 분류와 `INCOMPLETE_EVIDENCE`, `IMAGE_MODEL_NOT_IMPLEMENTED` 제한을 유지합니다. OCR은 닫기 후보 생성·광고 판정·자동 클릭 허가로 연결하지 않습니다. B/C/D 공통 모델/메서드/enum의 시그니처 변경은 없습니다. `PopupContracts.kt`는 설명 주석만 갱신했습니다.

`AccessibilityRuntime`이 기존 `SnapshotSource`, `EvidenceExtractor`, `CaptureCapability`를 구현합니다. 외부 `Candidate`를 받은 다음 `extract(snapshot)`을 호출하면 완료된 분석만 반환하며 중복 캡처를 시작하지 않습니다. 분석 DTO 1개를 기존 5초 TTL 안에서만 보유하고, 무효화/차단/해제에 즉시 지웁니다. 새 API 인자를 요구하지 않으므로 기존 B 수집 예제에 런타임을 포트로 주입할 수 있습니다. `status.lastEvidenceIssue`는 원문 없는 상세 코드, `ocrTextCount`는 현재 개수, `lastCompletedOcrTextCount`는 최근 완료 개수뿐입니다. 서비스와 공통 계약 밖으로 `Bitmap`·`HardwareBuffer`·노드를 내보내지 않습니다.

## 자동 검증과 미검증 항목

기존 `SnapshotToken.contentDigest`는 트리의 세션 지문입니다. 이미지 픽셀이나 OCR 본문을 포함하지 않으므로 그 값만으로 이미지 공지 내용이 같다고 간주하면 안 됩니다. D의 실제 지문 구현은 `EvidenceExtractor`의 현재 완료 분석까지 받아 OCR 포함 중요 본문을 별도로 결박하고 B는 실행 직전에 재확인해야 합니다. 해당 확인이 없으면 반복 자동화는 금지합니다. 이번 PR은 저장·실행 엔진을 제공하지 않습니다.

최종 Gradle 실행 결과는 [개발 상태](DEVELOPMENT_STATUS.md)에 기록합니다. 다음 범위를 분리합니다.

| 시나리오 | JVM 제어 흐름 시험 | 실제 API/인식 시험 |
|---|---|---|
| 한국어 점검·날짜·시간·가격 | 합성 OCR line의 source/좌표/정렬/비분류 확인 | `BundledKoreanOcrTest`, 미실시 |
| 글자 없는 이미지 | OCR 빈 결과를 판단 불가로 처리 | 합성 원·색상 이미지의 실제 모델 시험, 미실시 |
| 트리에 이미 본문 있음 | 불필요 캡처 생략, 교차 출처 보존·중복 처리 | Dialog/이미지와 트리 혼합 기기 시험, 미실시 |
| 비활성 서비스·능력 없음·캡처 실패 | Port 오류와 미호출, 안전한 트리 보존 | 실제 비활성·권한·오류 코드, 미실시 |
| 비밀번호·보안 창·민감 OCR | F0-A 선차단, secure 오류, F0-B 결과 미전달 | `FLAG_SECURE`·이미지 민감 화면, 미실시 |
| OCR 중 화면 변경·터치·취소 | 늦은 결과 폐기, frame close 검증 | 실제 화면 전환/TalkBack 조작, 미실시 |
| OCR 오류·시간 초과 | 가상 시간 timeout·최신 보호 재검사 | native Task 종료/메모리 수명, 미실시 |
| 한도·메모리 | 단일 슬롯·빈도·10분 이동창 한도/자동 회복 | Bitmap lease 계측, 버퍼 치수/포맷·회전·실제 피크, 미실시 |

이 환경은 연결된 Android 기기와 실행 가능한 에뮬레이터/KVM이 없어 기기 시험을 실행하지 않았습니다. 계측 소스/APK가 존재하거나 컴파일된 것을 실제 OCR/capture 성공으로 표시하지 않습니다. 독립 합성 호스트의 Kotlin 런타임 패키징 수정과 Windows 설치·실행 명령은 [ANDROID_TEST_RUNTIME.md](ANDROID_TEST_RUNTIME.md)를 먼저 확인합니다.

## 합성 데이터 기기 검증 절차

실 계정·연락처·메신저·사진이 없는 초기화한 시험 기기/에뮬레이터에서만 합니다. API 30 또는 33과 API 34 또는 35를 구분해 기록합니다.

1. `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` 실행 후 본 앱, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, `capture-test-host/build/outputs/apk/debug/capture-test-host-debug.apk` 세 APK를 `adb install -r -t`로 각각 설치. assembleDebugAndroidTest는 독립 호스트도 빌드하지만 installDebugAndroidTest는 호스트를 설치하지 않습니다.
2. 캡처 서비스 시험 전, 번들 모델/좌표/Bitmap lease 계측만 실행:

   ```bash
   adb shell am instrument -w -e class kr.closeforyou.accessibility.capture.BundledKoreanOcrTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
   ```

3. 실제 서비스 연결·캡처 통합 시험은 이제 본 앱이 아닌 합성 호스트 대상 외부 계측에서 실행합니다. APK 4개 설치, 연결 전용 검사, 사용자 UI 동의/합성 호스트 단독 허용 조건과 새 명령은 [SERVICE_CAPTURE_TEST.md](SERVICE_CAPTURE_TEST.md)를 따릅니다. 기존 `kr.closeforyou.test` runner의 ServiceCaptureOptInTest 명령은 사용하지 않습니다. 연결 검사는 동의를 켜지 않으며, 캡처 시험도 사용자 설정을 변경하지 않습니다.

4. 수동 관찰은 앱에서 `kr.closeforyou.capturetest`를 분석 허용하고, 캡처 설명 별도 동의와 해당 앱의 이미지 글자 읽기 허용을 켜고 분석을 시작합니다. 다음 debug/testOnly 독립 호스트 Activity를 열면 합성 한국어 이미지 Dialog가 나타납니다.

   ```bash
   adb shell am start -W -n kr.closeforyou.capturetest/kr.closeforyou.capturetest.SyntheticPopupActivity
   ```

5. `Candidate` → `AccessibilityRuntime.extract`의 OCR source/화면 좌표를 디버거에서 확인합니다. 원문 로그/스크린샷 파일을 만들지 않습니다. 자동 캡처 횟수는 최근 완료 개수·코드로만 관찰합니다. APK 실행 권한·설정·제조사별 차이를 기록합니다.
6. TalkBack 켬/끔, OCR 도중 터치·스크롤·다른 Activity 이동·회전·설정 철회를 각각 실행합니다. 이전 토큰의 `extract`가 취소되고 OCR 결과가 발행되지 않는지 확인합니다.
7. 합성 비밀번호/인증·카드 이미지로 F0-B 차단, `FLAG_SECURE` 시험 창으로 실패/빈 화면 처리, 잘못된 창·서비스 해제·짧은 간격·30회 한도를 확인합니다. 이 음성/보안/오류 시나리오들은 별도 시험 fixture 확장이 필요하며 현재 실제 시험 성공으로 기록하지 않습니다.
8. 캡처해 버린 외부 영역이 CPU/OCR로 전달되지 않는지, 콜백이 늦게 와도 buffer가 닫히는지, 취소된 native 입력은 Task 완료 뒤 지워지는지 프로파일러/중단점으로 관찰합니다. 화면 원문을 profiler metadata에 남기지 않습니다.

기기·Android·TalkBack·앱 버전·시나리오·예상·관측·실패 코드·실시/미실시를 기존 검증 계획의 양식에 기록합니다. 탐지·기계적 API 성공·인식 정확도·개인정보 보호를 별도로 평가합니다.

## 후속 범위

F4 고급 설명, F5 영상 광고 자동 처리, B 분류/실행, C 음성 안내/명령, D Room은 TODO로 유지합니다. F6는 제외입니다. F10/F12/F13 필수 결정은 변경하지 않았습니다. 이번 PR은 클릭·좌표 탭·뒤로 가기를 실행하지 않습니다.

공식 근거: [AccessibilityService 캡처 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [ScreenshotResult](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult), [ML Kit 한국어 번들 모델](https://developers.google.com/ml-kit/vision/text-recognition/v2/android). 문서의 API 설명과 본 프로젝트의 기기별 실측 결과는 구분합니다.


PR #2 검토 후 추가 실기기 확인(모두 합성 데이터, 아직 미실시): 일반 결제·동의 안내는 트리에서 고정 요약만 발행하고 캡처하지 않는지, 이미지 안내는 F0-B 후 요약하는지, 비밀번호/카드/인증번호를 섞으면 전체 차단하는지 관찰합니다. 원본 노드/버튼이 공개 Candidate에 없고 위험 행동이 실행되지 않는지도 확인합니다. 30회 한도 후 가장 오래된 요청이 10분을 경과하면 재시작 없이 다음 명시적 요청이 가능해지는지, 절전 후에도 회복하는지, 아직 native 작업 중이면 계속 BUSY인지 확인합니다. 원문 로그나 화면 파일은 만들지 않습니다.


PR #4 이후 API 37 통합 시험의 혼합 진단을 수정했습니다. F0-A/F0-B·시스템 창/불확실 영역 보호는 유지하며, debug 세션에서 합성 root 시도별 단계·영역 거부 분기를 관찰합니다. 전체 캡처 성공은 기기 재시험 전 미검증입니다. [확인된 진단 결함·미확정 원인·Windows 명령](CAPTURE_SESSION_DIAGNOSTICS.md)을 따릅니다. B·C·D 공통 계약은 변경하지 않았습니다.


PR #5 이후 영역 결정 수정: 일반 서비스 metrics를 화면 전체로 가정하지 않고 명시적 기본 디스플레이 WindowContext 경계를 논리 크기와 교차 검증합니다. 창/노드 screen 좌표는 원본을 유지하며 화면 밖 창은 내부 노드로 우회하지 않습니다. [수정 전후·새 진단·API 35/37 계측 명령](DIALOG_CAPTURE_COORDINATES.md)을 확인합니다. 실제 캡처 API 반환/OCR은 기기 재시험 전 미검증입니다.


PR #6 추가: window screenshot buffer와 접근성 창 크기의 일반 동등 계약을 확인할 수 없어 mismatch 차단을 유지합니다. 실제 반환 버퍼 metadata로 메모리 한도를 먼저 검사하고 축별/디스플레이/교환 관계 코드와 해제 여부만 debug 세션에서 관측합니다. [AOSP·CTS 근거, 안전 거부, 버퍼 전용 계측과 Windows 명령](WINDOW_CAPTURE_BUFFER.md)을 확인합니다. 픽셀 대응·전체 OCR 성공은 기기 재시험 전 미검증입니다.

PR #6의 후속 [합성 패턴 계측](SYNTHETIC_BUFFER_MAPPING.md)은 실제 window callback을 debug 세션에서만 검사합니다. 패턴 검증 결과를 프로덕션 크롭/변환/분류 근거로 사용하지 않으며, 불일치 버퍼는 계속 REGION_UNCERTAIN입니다. 일반 한국어 OCR fixture와 B·C·D 계약은 유지합니다.
