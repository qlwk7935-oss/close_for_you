# 10/20 오후 무인 작업 보고서 (C 재호)

> 브랜치 `feature/ui-voice`, **커밋 안 함**(push·PR·팀 채팅도 안 함). Mock 기준, 실제 연동 미검증.
> 진행 기록: `docs/c-notes/afternoon-log-2026-10-20.md`. 중간에 멈추지 않고 한 번에 끝냄.

## 쉬운 요약 (5줄)

1. 선택 창이 뜨고 0.7초 안에 [말하기]를 눌러 듣기 시작하면, 앱의 첫 안내 소리를 **건너뜁니다**(마이크에 우리 소리가 안 들어가게). 듣기가 끝나도 다시 말하지 않습니다.
2. LG 폰에서 선택 창을 기본 글꼴·글꼴 2.0배·다크 모드로 띄워 봤고, **잘리거나 안 읽히는 화면은 없었습니다.** [말하기]는 LG가 기기 안 인식을 지원하지 않아 숨겨졌습니다(정상).
3. PR 설명 초안(`pr-draft-ui-voice.md`)을 오늘 기준으로 새로 썼습니다. GitHub 웹에 제목·본문을 그대로 붙이면 됩니다.
4. 중간 발표용 C 부분 자료(`midterm-c-part.md`)를 새로 썼습니다: 화면 흐름, 안전 장치, 확인한 것/못 한 것, 시연 순서와 adb 명령, 예상 질문 5개.
5. 빌드·시험·lint 통과(시험 292 → **296개, 실패 0**, lint 경고 7개는 예전 그대로). LG 폰 설정은 원래대로 돌려 놨습니다.

## 단계별 결과

| 단계 | 결과 | 비고 |
|---|---|---|
| 0. 시작 확인 | 완료 | `feature/ui-voice`, 54da79e, 미추적은 지시서 하나 |
| 1. 듣는 중이면 첫 안내 건너뛰기 | 완료 | 시험 4개 추가 |
| 2. LG 설치·자동 점검 | 완료 (일부 생략) | font_scale 1.3은 생략(1.0·2.0 모두 정상). TalkBack은 켜지 않음 |
| 3. PR 초안 갱신 | 완료 | |
| 4. 중간 발표 자료 | 완료 | |
| 5. README 정리 | 완료 | 현재 기능·LG 결과 반영 |
| 6. 정리·보고서 | 완료 | |

### 1단계 — 어떻게 고쳤나
- `PromptControlsController.speakPressed`: 이 선택 창에서 실제로 듣기가 **시작된** 적이 있으면 true. (권한이 없어 듣기를 못 했으면 false → 첫 안내는 그대로 나감)
- `OverlayListener.onShown(prompt, speakPressed)`: 두 번째 인자를 추가(기본값 `{ false }`라 다른 곳은 그대로 동작). `AccessibilityChoiceOverlay`가 컨트롤러의 값을 넘깁니다.
- `PromptAnnouncementListener`: 0.7초 기다린 뒤 `speakPressed()`가 true면 말하지 않습니다. 시험할 수 있게 문장 만드는 함수를 받는 `internal` 생성자를 추가했고, 기존 생성자(Context 받는 것)는 그대로입니다.
- 시험(`PromptControlsControllerTest`): ① 안 누르면 0.7초 뒤 한 번 말함 ② 0.3초에 누르면 안 말함 ③ 누르고 다시 눌러 듣기를 끝내도 안 말함 ④ 권한이 없어 듣기를 못 하면 그대로 말함.
- 실기기 미확인(LG는 [말하기]가 숨겨져 시험 불가 → S26 필요).

## LG 점검 표 (LG 벨벳, Android 12, TalkBack 끔, 화면만 확인)

| 항목 | 결과 |
|---|---|
| 기본 글꼴, 시나리오 0·3·6 | 창이 화면 아래에 뜸, 버튼 글자 안 잘림, [다시 듣기]·[그만 말하기] 한 줄 정상 |
| font_scale 2.0, 시나리오 0·3·6 | 글자·버튼 커짐, 화면 안에 다 들어감(스크롤 생기지 않음), 잘림 없음 |
| font_scale 1.3 | 안 봄 |
| 다크 모드, 시나리오 0 | 창 배경이 어둡게 바뀌고 글자 읽힘 |
| [말하기] | 모든 시나리오에서 숨겨짐(정상) |
| 설정 화면(다크) | 음성 구역 "이 기기는 기기 안 음성 인식을 지원하지 않아서 음성 명령을 쓸 수 없어요." 보임. 허용 앱은 "허용한 앱이 없어요"(이 폰엔 허용 앱 없음 → **앱 이름 표시는 확인 못 함**). 기억 목록은 가짜 앱이 설치 안 돼 패키지 이름으로 대체 표시 |
| 시험 도구 함정 | 미리보기 화면이 이미 떠 있으면 `--ez ui_debug_open_settings true`가 안 먹음 → `am start -S`로 새로 시작하면 됨 (발표 자료에 반영) |

스크린샷은 스크래치 폴더에만 두었고 저장소에는 넣지 않았습니다.

### 폰 설정 (원래 값 → 끝난 뒤)
| 설정 | 원래 | 끝난 뒤 |
|---|---|---|
| `font_scale` | null(설정 안 됨) | `delete` 했는데 읽으면 `1.0`으로 나옴 → 값은 같음(기본 1.0). 폰이 스스로 다시 쓴 것으로 보임 |
| 다크 모드 | no | no |
| `enabled_accessibility_services` | null | null |
| `accessibility_enabled` | 0 | 0 |

## 바뀐 파일

| 파일 | 내용 |
|---|---|
| `app/src/main/java/kr/closeforyou/ui/voice/overlay/PromptAnnouncementListener.kt` | 듣는 중이면 첫 안내 건너뛰기, 시험용 internal 생성자 |
| `app/src/main/java/kr/closeforyou/ui/voice/overlay/AccessibilityChoiceOverlay.kt` | `OverlayListener.onShown`에 `speakPressed` 인자 |
| `app/src/main/java/kr/closeforyou/ui/voice/overlay/PromptControls.kt` | `speakPressed` |
| `app/src/test/java/kr/closeforyou/ui/voice/overlay/PromptControlsControllerTest.kt` | 시험 4개 |
| `app/src/main/java/kr/closeforyou/ui/voice/README.md` | 현재 기능·미검증 표 갱신 |
| `docs/c-notes/pr-draft-ui-voice.md` | 전체 새로 씀 |
| `docs/c-notes/midterm-c-part.md` (새 파일) | 중간 발표 C 부분 |
| `docs/c-notes/afternoon-plan-2026-10-20.md`, `afternoon-log-…`, `afternoon-report-…` (새 파일) | 지시서·기록·보고서 |

A·B·D·core·main 매니페스트·MainActivity는 안 고침. 플래그 3개 그대로 꺼짐. 새 라이브러리·권한 없음.

## 추천 커밋 묶음

1. ```
   fix(ui-voice): 말하기로 듣는 중이면 선택 창 첫 안내 건너뛰기 (Mock 기준, 실기기 미검증)
   ```
   - 코드 3개 + 시험 1개 + README
2. ```
   docs(ui-voice): PR 초안 갱신, 중간 발표 C 자료, 10/20 오후 지시서·기록·보고서
   ```
   - `docs/c-notes/` 아래 5개

## 사용자가 결정할 것
- 커밋·push, PR을 언제 만들지 (초안은 준비됨, 리뷰어 D).
- 발표 자료를 우진에게 언제 어떻게 넘길지.
- release에서 [말하기]를 쓰려면 main 매니페스트 `RECORD_AUDIO`가 필요 → 팀에 요청할지.

## 팀 메시지 초안 (필요할 때)
> 우진님, 중간 발표 C 부분 자료를 `docs/c-notes/midterm-c-part.md`에 정리했어요(PR 올리면 같이 들어가요). 시연은 LG + 가짜 팝업으로 3~5분이고, 실제 감지 연동 전이라 "Mock 기준"이라고 말해 주시면 돼요.

## 사람이 확인할 것
- TalkBack 켜고 선택 창 읽기 순서: 요약 → 선택지 → [다시 듣기] → [그만 말하기] (LG, 사람이 켜서)
- 허용 앱이 있을 때 설정 화면에 앱 이름이 보이는지 (LG에는 허용 앱 없음)
- S26: [말하기] 보이는지, 첫 안내 전에 누르면 안내가 안 나오는지, 실제 인식
- 창이 화면 높이를 넘을 만큼 길 때(글꼴 2.0배 + 선택지 많음) 스크롤·상태 표시줄 여백

## 사용량
- 시작: 5시간 5%, 주간 95%. 끝(6단계 시작 때): 5시간 10%, 주간 95%. 추가 사용량 꺼짐 유지. 중간에 멈추지 않음.
