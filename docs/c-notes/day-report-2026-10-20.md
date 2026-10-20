# 10/20 낮 무인 작업 보고서 (C 재호)

> 브랜치 `feature/ui-voice`, **커밋 안 함**(push·PR·팀 채팅도 안 함). Mock 기준, 실제 연동·실기기 미검증.
> 자세한 진행 기록: `docs/c-notes/day-log-2026-10-20.md`

## 쉬운 요약 (5줄)

1. "닫을까요?" 선택 창에 **[말하기]** 버튼이 생겼습니다. 누를 때만 듣고, 다시 누르면 멈춥니다. 알아들은 말은 손으로 누를 때와 **같은 길**로 처리됩니다(B 정책·A 실행 관문을 그대로 거침).
2. 선택 창에 **[다시 듣기]·[그만 말하기]** 버튼이 생겼습니다.
3. 음성 정책 표를 코드로 만들었습니다: 닫기·건너뛰기·읽기·그대로 두기는 바로, "항상 닫아"·"다시 묻지 마"는 "화면에서 직접 골라 주세요", 결제·구매·구독·삭제·권한·동의·로그인은 "그 버튼은 대신 누를 수 없어요".
4. 마이크 권한이 없거나 이 빌드에 권한 선언이 없으면(release) [말하기]는 숨겨집니다. 설정 화면에 "음성 명령" 구역(켜기/끄기 + 마이크 상태 + [마이크 허용])이 생겼습니다.
5. 빌드·시험·lint 모두 통과(시험 264 → **292개, 실패 0**, lint 경고 7개는 예전 그대로). INTERNET 권한 없음, 권한 변화 없음.

## 단계별 결과

| 단계 | 결과 | 비고 |
|---|---|---|
| 0. 시작 확인 | 완료 | `feature/ui-voice`, 미추적은 지시서 1개뿐 |
| 1. [말하기] + 음성 → 선택 | 완료 (Mock) | 플래그 `VoiceButtonFeature.enabled = true` |
| 2. 마이크 권한 | 완료 | main 매니페스트는 손대지 않음 → release는 숨김 |
| 3. [다시 듣기]·[그만 말하기] | 완료 | 기존 `repeatLast()`/`stop()` 연결 |
| 4. 음성 정책 | 완료 | 순수 함수 + 시험 12개 |
| 5. 설정 켜기/끄기 | 완료 | 기본 켜짐 |
| 6. 정리·보고서 | 완료 | 실기기 확인은 못 함 |

### 1단계 — [말하기]
- 위치: 선택지 버튼들 아래. 높이 48dp 이상, 보이는 글자가 곧 TalkBack 이름("말하기"). 듣는 중에는 글자가 "듣기 멈추기"로 바뀌고 상태 설명 "듣는 중"이 붙습니다.
- 상태 줄: "듣고 있어요." / "알아듣지 못했어요. 다시 말하거나 화면에서 골라 주세요." 등은 liveRegion(Polite)으로 TalkBack이 스스로 읽습니다. **포커스는 옮기지 않습니다.**
- 아무 말도 없으면(`ERROR_SPEECH_TIMEOUT`) 조용히 끝납니다. 그 밖의 인식 실패는 "알아듣지 못했어요".
- 누르면 먼저 우리 TTS를 멈춥니다(`announcer.stop()`).
- **중요하게 정한 점:** 마이크가 켜진 동안 TalkBack이 상태 줄을 읽습니다. 그 문장에 "닫아" 같은 명령어가 있으면 마이크가 그걸 명령으로 들을 수 있어서, 음성 관련 문장에는 명령어를 넣지 않았고 시험(`voiceSentencesHaveNoCommandWords`)으로 고정했습니다.
- 계약 `VoiceEvent`에는 "못 알아들음·거절"을 나누는 값이 없어서(전부 `Cancelled`), `core`는 고치지 않고 C 안에 `VoiceUtterance`를 두었습니다. 계약 `events`는 예전과 똑같이 나갑니다.

### 2단계 — 마이크 권한
- `MicrophoneAccess`(순수 인터페이스) → `SpeakButtonPolicy.state(...)` → `SHOWN / TURNED_OFF / NOT_IN_THIS_VERSION / PERMISSION_NEEDED / DEVICE_UNSUPPORTED`.
- 선택 창에서의 선택: **권한만 없을 때(선언은 있음)만** 마지막 줄에 "음성 명령을 쓰려면 설정에서 마이크를 허용해 주세요." 한 줄. 나머지(설정 꺼짐, release처럼 선언 없음, 기기 안 인식 미지원)는 안내 없이 숨김.
  - 이유: 사용자가 고칠 수 있을 때만 알려 주고, 고칠 수 없는 경우까지 말하면 팝업마다 TalkBack이 쓸모없는 줄을 한 번 더 읽게 됩니다.
- 설정 화면: 상태 한 줄 + 권한이 필요할 때만 [마이크 허용] 버튼(Activity 안 `ActivityResultContracts.RequestPermission`). 거절되면 "휴대폰 설정의 앱 권한에서 바꿀 수 있어요". 시스템 설정에서 바꾸고 돌아오면 다시 확인합니다. 선언이 없으면 "아직 이 앱 버전에서는 음성 명령을 쓸 수 없어요".
- 선택 창(서비스 오버레이)에서는 권한을 요청하지 않습니다.

### 3단계 — [다시 듣기]·[그만 말하기]
- 두 버튼을 한 줄(Row)에 나란히 두어 창 높이를 덜 늘렸습니다. 누르면 듣기 중일 때 먼저 듣기를 취소합니다.
- 다시 들을 게 없으면 기존 `TtsPopupAnnouncer`가 `ui_announce_nothing_to_repeat`("다시 들을 안내가 없어요.")를 말합니다.
- 접근성 점검(코드 기준, 실기기 아님): TalkBack 순서 = 요약 → 선택지 → 말하기 → 다시 듣기 → 그만 말하기 → 상태 줄 → 권한 안내. 모든 버튼 48dp 이상, 보이는 글자 = 읽는 이름. 글자가 크면 창이 스크롤됩니다(기존 `verticalScroll`). 버튼이 2~3개 늘어서 창이 길어진 점은 실기기에서 확인 필요.

### 4단계 — 음성 정책 표 (`command/VoicePolicy.kt`)
| 들은 말 | 결과 | 문장 |
|---|---|---|
| 닫아 / 건너뛰어 / 읽어 줘 / 그대로 둬 | 그 선택지가 **보일 때만** 선택 (탭과 같은 길) | — |
| 닫아 (검증된 닫기 버튼 없음·중요 결정 팝업) | 실행 안 함 | 이 팝업은 대신 닫을 수 없어요. |
| 항상 닫아 / 앞으로 / 매번 / 다시 묻지 마 / 그만 물어 | 실행 안 함 | 그 선택은 화면에서 직접 골라 주세요. |
| 결제·구매·구입·주문·구독·삭제·지워·권한·허용·동의·로그인·가입 | 실행 안 함 (다른 명령어가 섞여도 거절이 먼저) | 그 버튼은 대신 누를 수 없어요. |
| 뒤로 가 / 그림 설명 / 버튼으로 이동(플래그 꺼짐) / 안 보이는 선택지 | 실행 안 함 | 그 말은 이 창에서 쓸 수 없어요. 화면에서 골라 주세요. |
| 무관한 말·길게 섞인 말 | 실행 안 함 | 알아듣지 못했어요. … |
| 아무 말 없음 | 조용히 끝 | — |
- 들은 문장 원문은 안내문에 넣지 않고, 로그에도 남기지 않습니다.
- 덤으로 고친 것: `VoiceChoiceMapper`가 읽기/그대로 두기 선택지를 `first { }`로 찾아서 선택지가 없으면 앱이 멈출 수 있었음 → "여기서 못 함"으로 바꿈.

### 5단계 — 설정
- `UiVoiceSettings.voiceCommandsEnabled`(기본 켜짐, 저장 키 `voice_commands_enabled`). 끄면 다음 선택 창부터 [말하기]가 숨겨집니다. 마이크 줄과 같은 "음성 명령" 구역.

### 6단계 — 확인
- `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` → BUILD SUCCESSFUL.
- 시험 292개, 실패 0 (새 시험: `VoicePolicyTest` 12, `PromptControlsControllerTest` 10, `SpeakButtonPolicyTest` 5, 설정 1).
- lint 경고 7개(GradleDependency 6, OldTargetApi 1) — 오늘 전과 같음.
- 병합 매니페스트 권한: debug = `RECORD_AUDIO`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` / release = `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **INTERNET 없음, 오늘 바뀐 권한 없음.** 새 라이브러리 없음.
- 꺼진 플래그(`RememberChoiceFeature`, `CannotPressReasonFeature`, `MoveToButtonFeature`)는 그대로입니다.

## 바뀐 파일

새 파일
- `app/src/main/java/kr/closeforyou/ui/voice/command/VoicePolicy.kt` — `VoiceUtterance`, `VoiceOutcome`, `VoicePolicy`
- `app/src/main/java/kr/closeforyou/ui/voice/command/SpeakButtonAccess.kt` — `VoiceButtonFeature`, `MicrophoneAccess`, `SpeakButtonPolicy`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/PromptControls.kt` — `PromptControlsState`, `PromptControlsSupport`, `PromptControlsController`
- 시험: `command/VoicePolicyTest.kt`, `command/SpeakButtonPolicyTest.kt`, `overlay/PromptControlsControllerTest.kt`
- 기록: `docs/c-notes/day-log-2026-10-20.md`, 이 보고서 (지시서 `day-plan-2026-10-20.md`도 미추적)

고친 파일 (전부 C 영역)
- `choice/ChoicePromptContent.kt` — 버튼 3개·상태 줄·미리보기 2개 추가 (기본값이면 예전과 같은 화면)
- `overlay/AccessibilityChoiceOverlay.kt` — `controlsSupport` 인자(기본 `NONE`), 탭·음성 공통 `onOption`
- `command/KoreanVoiceCommandMatcher.kt` — `classify()` 추가 (`match()`는 그대로)
- `command/OnDeviceVoiceCommandInput.kt` — `SpeakButtonVoiceInput` 구현, `utterances` 흐름 추가
- `command/VoiceChoiceMapper.kt` — 안 보이는 선택지 처리
- `settings/UiVoiceSettings.kt`, `settings/UiVoiceSettingsScreen.kt` — 음성 명령 구역
- `res/values/ui_voice_strings.xml` — `ui_voice_*`, `ui_choice_repeat`, `ui_choice_stop_speaking`, `ui_settings_voice_*`, `ui_settings_mic_*`
- `debug/.../ChoiceOverlayTestService.kt` — 시험 오버레이에도 버튼 3개 켬
- `ui/voice/README.md`, `settings/UiVoiceSettingsTest.kt`

손대지 않은 것: A·B·D 코드, `core/**`, main `AndroidManifest.xml`, `MainActivity`, 기능 플래그 3개.

## 추천 커밋 묶음·메시지 (사용자가 직접)

1. 음성 정책·마이크 상태 (순수 로직 + 시험)
   - `command/VoicePolicy.kt`, `command/SpeakButtonAccess.kt`, `command/KoreanVoiceCommandMatcher.kt`, `command/VoiceChoiceMapper.kt`, `command/OnDeviceVoiceCommandInput.kt`, `VoicePolicyTest.kt`, `SpeakButtonPolicyTest.kt`
   - `feat(ui-voice): 음성 정책 표(바로·탭 확정·거절)와 마이크 상태 판단 (Mock 기준)`
2. 선택 창 버튼 (말하기·다시 듣기·그만 말하기)
   - `overlay/PromptControls.kt`, `overlay/AccessibilityChoiceOverlay.kt`, `choice/ChoicePromptContent.kt`, `debug/ChoiceOverlayTestService.kt`, `PromptControlsControllerTest.kt`, `ui_voice_strings.xml`의 `ui_voice_*`·`ui_choice_repeat/stop_speaking`
   - `feat(ui-voice): 선택 창에 말하기·다시 듣기·그만 말하기 연결 (실기기 미검증)`
3. 설정 화면 음성 구역
   - `settings/UiVoiceSettings.kt`, `settings/UiVoiceSettingsScreen.kt`, `UiVoiceSettingsTest.kt`, `ui_voice_strings.xml`의 `ui_settings_voice_*`·`ui_settings_mic_*`
   - `feat(ui-voice): 설정에 음성 명령 켜기/끄기와 마이크 허용 추가`
4. 문서: `README.md`, `day-plan/day-log/day-report-2026-10-20.md` — `docs(ui-voice): 10/20 낮 작업 기록·보고서`

> 주의: 문자열 파일(`ui_voice_strings.xml`) 하나를 세 묶음이 같이 씁니다. 1번이 `ui_voice_*`를, 2번이 `voiceCommandsEnabled`(3번)를 쓰므로 묶음별로 따로 빌드되게 하려면 **1번에 `ui_voice_*` 문자열을, 순서는 1 → 3 → 2**로 해야 합니다(묶음별 빌드는 확인 안 함). 번거로우면 1+2+3을 **한 커밋**으로 묶는 것을 추천합니다(전체 빌드는 확인함).

## 사용자가 결정할 것

1. **권한 없을 때 선택 창 안내 한 줄** — (가) 지금처럼 "권한만 없을 때" 한 줄 *(추천: 고칠 수 있을 때만 알려 줌)* / (나) 항상 숨기고 설정 화면에서만 안내 / (다) 숨길 때마다 이유를 한 줄
2. **음성 "닫아"가 버튼 이름을 말하지 않고 바로 누르는 것** — 지금은 CLAUDE.md 3-1대로 바로 요청(B·A 관문은 거침), `buttonMeaningConfirmed = true`는 탭과 같음. (가) 그대로 *(추천: 팀 규칙과 같음, 검증된 닫기 버튼이 하나일 때만)* / (나) 음성일 때는 버튼 이름을 읽어 주고 탭으로 확정
3. **거절 단어 목록에 "허용"·"가입"·"주문"·"지워" 포함** — (가) 그대로 *(추천: 안전 쪽)* / (나) 팀과 B의 위험 단어 목록에 맞춰 줄이기
4. **음성 결과 문장을 TalkBack(liveRegion)만으로 알릴지** — TalkBack을 안 쓰는 저시력 사용자는 화면 글자만 봅니다. (가) 그대로 *(추천: 실기기에서 겹침 확인 전까지)* / (나) TalkBack이 꺼져 있으면 우리 TTS로도 읽기

## 팀 메시지 초안 (보내지 않음)

> 우진님, C 선택 창에 [말하기]·[다시 듣기]·[그만 말하기]를 붙였어요(Mock 기준, 실기기 미검증).
> 1) **요청:** main `AndroidManifest.xml`에 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`를 넣어 주실 수 있을까요? 지금은 debug에만 있어서 release에서는 [말하기]가 자동으로 숨겨져요. 권한은 선택 창(서비스)에서는 요청하지 않고 설정 화면(Activity)에서만 요청해요. INTERNET은 그대로 없어요.
> 2) A 서비스에서 선택 창을 만들 때 `AccessibilityChoiceOverlay(service, listener, PromptControlsSupport.create(service, announcer))`처럼 세 번째 인자를 주면 버튼이 켜져요. 안 주면 예전 화면 그대로예요.
> 3) 음성으로 고른 선택은 탭과 똑같은 `UserChoice`로 나가서 B 정책·A 관문을 그대로 거쳐요. 결제·구독·동의·로그인 같은 말은 음성으로 누르지 않고, "항상 닫기"류는 화면 탭으로만 확정해요. 항상 닫기·다시 묻지 않기·버튼으로 이동 플래그는 말씀대로 꺼 둔 채예요.
> 4) 우리 오버레이에서 나는 `liveRegion` 안내가 A의 "사용자 조작/새 팝업" 판단에 섞이지 않는지 같이 봐 주시면 좋겠어요(`ChoiceOverlayIdentity` 참고).

## 실기기 확인할 것

S26 (기기 안 음성 인식 지원 예상)
- [ ] 설정 → 음성 명령 → [마이크 허용] 권한 창이 뜨고, 돌아오면 "마이크가 허용되어 있어요"로 바뀌는지
- [ ] 선택 창 [말하기] → "듣고 있어요" → "닫아"/"건너뛰어"/"읽어 줘"/"그대로 둬"가 맞게 처리되는지
- [ ] "결제해"·"항상 닫아"·"안녕하세요"·아무 말 없음 → 각 문장/조용히 끝
- [ ] **TalkBack이 "듣고 있어요"를 읽는 소리가 인식에 섞이는지**, 인식 시작 소리(비프)와 겹치는지
- [ ] TalkBack 켠 상태에서 [말하기]를 두 번 탭할 때 TalkBack 소리가 명령으로 오인되지 않는지
- [ ] `ERROR_SPEECH_TIMEOUT`이 실제로 "아무 말 없음"에 오는지 (기기마다 `ERROR_NO_MATCH`일 수 있음)
- [ ] 다시 듣기·그만 말하기가 우리 TTS에 바로 반응하는지

LG 시험폰 (기기 안 인식 미지원)
- [ ] [말하기]가 숨겨지고, 설정에 "이 기기는 기기 안 음성 인식을 지원하지 않아서…"가 보이는지
- [ ] TalkBack 순서: 요약 → 선택지 → 다시 듣기 → 그만 말하기 (→ 상태 줄)
- [ ] 큰 글자에서 창이 화면을 넘을 때 스크롤되는지, 두 버튼 한 줄이 잘리지 않는지

## 남은 문제

- `PromptAnnouncementListener`의 첫 안내는 창이 뜨고 0.7초 뒤에 나갑니다. 그 전에 [말하기]를 누르면 듣는 중에 우리 TTS가 시작될 수 있습니다(희귀). 다음에 "듣는 중이면 첫 안내 건너뛰기"를 넣을 수 있음.
- `PopupChoiceFlow`의 결과 안내(누르기 실패 등)는 듣기와 겹치지 않지만(선택 후 창이 닫힘), 읽기 선택 뒤 바로 [말하기]를 누르면 내용 낭독이 멈춥니다(의도: 마이크 보호).
- release에서는 RECORD_AUDIO가 없어 음성 명령이 동작하지 않습니다(A 요청 필요).
- Compose 미리보기·화면은 이번 세션에서 눈으로 보지 못했습니다(빌드·시험만).

## 사용량

- 5시간 한도: 시작 28% → 끝 35% (리셋 14:50 KST 무렵)
- 주간 한도: 91% → 92% (숫자만 기록). 유료 추가 사용량 꺼짐 그대로.
