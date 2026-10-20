# 10/20 오후 무인 작업 기록 (C 재호)

> 지시서: `afternoon-plan-2026-10-20.md`. 멈췄다 다시 불려 오면 이 기록부터 읽는다.

## 시작
- 시작 시각 사용량: 5시간 5%, 주간 95%. 추가 사용량 꺼짐.
- 브랜치 feature/ui-voice, 54da79e, 미추적은 지시서 하나(정상).

## LG 폰 설정 변경 (원래 값)
- (아직 바꾼 것 없음)

## 진행
- 0단계: 확인 끝.
- 1단계 끝: 첫 안내(0.7초 뒤) 전에 [말하기]로 듣기가 시작되면 첫 안내를 건너뜀, 듣기가 끝나도 다시 안 냄.
  - 바뀐 파일: overlay/PromptAnnouncementListener.kt(시험용 내부 생성자 추가), overlay/AccessibilityChoiceOverlay.kt(OverlayListener.onShown에 speakPressed 인자, 기본값 false), overlay/PromptControls.kt(speakPressed), test PromptControlsControllerTest.kt(+4개)
  - 빌드·시험·lint 통과.
  - 다음: 2단계 LG 설치·점검.
- LG 원래 값(2단계 시작 전): font_scale = null(설정 안 됨=1.0), 다크 모드 = no, enabled_accessibility_services = null, accessibility_enabled = 0.
  - 되돌리기: `settings delete system font_scale`, `cmd uimode night no`, `settings delete secure enabled_accessibility_services`, `settings put secure accessibility_enabled 0`
- 2단계: 설치 끝. 시험 서비스 켬(enabled_accessibility_services=ChoiceOverlayTestService, accessibility_enabled=1) → 끝나면 되돌리기
- font_scale 2.0으로 바꿈(원래 null) → 되돌리기 필요
- font_scale 되돌림(delete → null) 확인. 다크 모드 yes로 바꿈 → 되돌리기 필요
- 다크 모드 되돌림(no)
- 시험 서비스 끔(enabled_accessibility_services delete, accessibility_enabled 0).
- 2단계 끝. 점검 결과:

| 항목 | 결과 |
|---|---|
| 기본 글꼴, 시나리오 0·3·6 | 창이 화면 아래에 뜸, 버튼 글자 안 잘림, [다시 듣기]·[그만 말하기] 한 줄 정상 |
| font_scale 2.0, 시나리오 0·3·6 | 글자·버튼 커짐, 화면 안에 다 들어가서 스크롤 필요 없음, 잘림 없음 |
| font_scale 1.3 | 안 봄(1.0·2.0 모두 정상이라 생략) |
| 다크 모드, 시나리오 0 | 창 배경 어둡게 바뀜, 글자 읽힘 |
| [말하기] | 모든 시나리오에서 숨겨짐(LG 기기 안 인식 미지원, 정상) |
| 설정 화면(다크) | 음성 구역 "이 기기는 기기 안 음성 인식을 지원하지 않아서…" 보임. 허용 앱은 "없어요"(이 폰에 허용 앱 없음). 기억 목록은 앱 이름 대신 패키지 이름(가짜 앱이 설치 안 돼 대체 표시, 정상) |
| 참고 | `--ez ui_debug_open_settings true`는 미리보기 화면이 이미 떠 있으면 안 먹음 → `am start -S`로 새로 띄우면 됨 |

- 다음: 3단계 PR 초안.
- 3단계 끝: docs/c-notes/pr-draft-ui-voice.md 전체 갱신(제목+본문, 시험 296, LG 본 것/못 본 것, A·B·D 필요 사항). 다음: 4단계 midterm-c-part.md
- 4단계 끝: docs/c-notes/midterm-c-part.md 새로 씀. 다음: 5단계 README 점검
- 5단계 끝: ui/voice/README.md 현재 기능·LG 결과에 맞춤. 다음: 6단계 정리·보고서
- 6단계 끝: 빌드·시험(296, 실패 0)·lint(경고 7) 통과, 폰 설정 원래대로 확인, 보고서 작성. **전 단계 끝.**
