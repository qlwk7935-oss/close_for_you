# A 내부 V1 지문 codec — 운영 미연결

기준은 `feature/f1-popup-detection` / `07d2eda`의 AndroidSnapshotCollector입니다. 이번 구현은 `SnapshotFingerprintCodec.kt`, 순수 JVM 시험과 독립 합성 golden 자료뿐입니다. collector·service·F0/F1·두 pass·예산·진단·projection·공통 계약·BCD·PR #10·원래14개 시나리오는 변경하지 않으며 codec 호출을 운영 경로에 연결하지 않습니다.

## 정확한 V1 규격

모든 지문은 `SHA-256(salt || field0 || field1 || ...)`입니다. 기존 collector는 수명 동안 소유한 SecureRandom 32바이트 salt를 각 새 digest의 맨 앞에 **길이 prefix 없이 그대로** 넣습니다. codec은 그 salt를 인자로 받아 32바이트인지 확인한 후 방어적 복사하며 새 salt를 생성·내보내지 않습니다. 각 호출은 새 MessageDigest를 사용합니다. 지문 종류·버전 등의 새 domain tag를 넣지 않습니다. 따라서 빈 구조·빈 본문 지문은 둘 다 `SHA-256(salt)`인 기존 규격을 유지합니다.

각 nullable 문자열 필드의 인코딩:

1. null이면 signed int32 길이 `-1`의 big-endian 네 바이트 `ff ff ff ff`만 기록합니다.
2. null이 아니면 **UTF-8 바이트 수**의 int32 big-endian prefix와 그 바이트를 기록합니다. 빈 문자열은 길이0의 네 바이트만 가집니다.
3. UTF-16 문자 수를 길이로 쓰지 않습니다. trim/정규화/대소문자 변환/구분자 추가도 하지 않습니다. Kotlin/JVM UTF-8의 고립 surrogate 대체 인코딩도 시험합니다.

| 종류 | 기존 순서 그대로 기록하는 필드 |
|---|---|
| 구조 | 원래 preorder의 각 노드마다 `path, viewId, className, left, top, right, bottom, clickable, enabled, childCount, actions` |
| 본문 | 원래 preorder의 각 노드마다 `path, text, contentDescription` |
| outline | 해당 노드의 `path, viewId, className` |
| application | package 문자열 하나 |

좌표·childCount는 기존 정수의 10진수 문자열, boolean은 소문자 `true`/`false`입니다. 기존 collector는 CLICK을 먼저, DISMISS를 다음에 넣은 set을 문자열화합니다. codec은 action 존재 여부로 `""`, `"CLICK"`, `"DISMISS"`, `"CLICK,DISMISS"` 중 하나를 생성합니다. 전달 set의 순서가 반대여도 canonical collector 순서를 적용하며 입력 set은 변경하지 않습니다. 이 규칙은 **기존 collector가 만들 수 있는 V1 action 집합**의 직렬화입니다. 임의 순서의 set을 그대로 join한 결과와 같다고 주장하지 않습니다.

`SnapshotFingerprintNode.childCount`는 구조 지문용 실제 관측값을 전달하기 위한 A 내부 입력입니다. outline childCount나 추측한 값으로 대체해서는 안 됩니다. codec 자체는 이 값의 출처·정상성·순회 완전성을 검증하지 않습니다. 입력 순서를 정렬하거나 잘못된 bounds를 보정하지 않습니다. 기존 구조 지문에 없던 pane/가시성/scroll/화면 frame/새 geometry 상태/보호 receipt/세션을 추가하지 않습니다.

출력은32바이트이며 명시적인 `.hex()`는 기존과 같은 소문자64자리입니다. `.bytes()`는 방어적 복사본을 반환합니다. codec·입력 wrapper·digest wrapper의 toString은 redacted이고 로그/파일/DB/네트워크 출력이 없습니다. 잘못된 salt 길이의 예외 메시지는 고정 코드 `FINGERPRINT_SALT_SIZE`입니다. **명시적으로 꺼낸 bytes/hex도 민감 데이터이며 로그에 출력해서는 안 됩니다.** 참조 해제·수명 관리와 실제 수집 소유권은 후속 작업입니다.

## 독립 golden 생성 방법 — 순환 oracle 금지

`tools/generate_snapshot_fingerprint_goldens.py`는 Python 표준 라이브러리만 사용합니다. Kotlin codec을 실행·호출·import하거나 소스에서 상수를 추출하지 않습니다. 실제 사용자 데이터·salt를 입력받는 기능도 없습니다.

- 테스트 전용 salt는 고정 `bytes(range(32))`입니다. 운영 salt를 고정한다는 뜻이 아닙니다.
- 합성 노드3개의 필드를 별도 Python 목록으로 선언합니다. null/빈 값, 한글·emoji·줄바꿈, 음수 좌표, boolean, childCount, actions를 포함합니다.
- 인코더는 `struct.pack(">i", length)`와 UTF-8을 사용합니다. 고립 surrogate 시험 값은 Python의 `errors="replace"`를 통해 JVM과 같은 `?` 바이트를 사용합니다. 정상 surrogate pair 시험은 실제 Unicode emoji 리터럴을 사용합니다.
- 전체 바이트열을 별도로 연결하고 `hashlib.sha256(salt + stream)`으로 계산합니다.
- 결과는 `app/src/test/resources/snapshot_fingerprint_v1_golden.properties`에 정적으로 저장합니다. 각 vector의 `.stream`은 salt 뒤의 필드 바이트열이고 `.sha256`은 예상32바이트 해시의 hex입니다. 총22개 vector입니다.

생성/재검증:

```sh
# 고정 합성 리소스만 명시적으로 갱신. 출력은 고정 상태 코드뿐입니다.
python3 -B tools/generate_snapshot_fingerprint_goldens.py --write
# 기본 동작은 read-only 재계산 비교이며 파일을 쓰지 않습니다.
python3 -B tools/generate_snapshot_fingerprint_goldens.py
```

두 명령은 salt·필드·지문을 콘솔에 출력하지 않습니다. 의도적으로 새로운 vector를 추가하는 경우만 `--write`를 사용하고 규격과 두 구현의 diff를 검토합니다. 실패한 codec에 맞추려고 golden을 갱신해서는 안 됩니다.

JVM 시험은 커밋된 예상 해시를 읽어 codec 결과를 `assertArrayEquals`로 비교합니다. 추가로 Python이 만든 원시 stream을 JDK SHA-256으로 재해시해 리소스 자체를 검증하고, null/빈 값·한글/emoji·255바이트 초과 prefix에는 명시적 바이트 기준도 확인합니다. 이는 **codec으로 expected 값을 생성한 뒤 그 codec과 비교하는 시험이 아닙니다**. 테스트 리소스와 생성기는 운영 앱 입력/설정이 아닙니다.

## 검증과 한계

시험은 구조/본문/outline/application golden, null/빈 값·다중 바이트 UTF-8·고립 surrogate·정규화 부재, 필드/노드 순서, actions 네 조합·역순 set, 기존 필드 기여와 제외 필드, salt 재현성·변경·방어적 복사, digest 복사·재호출 독립성·hex·출력 비노출을 포함합니다.

```powershell
git switch feature/f1-popup-detection
git pull --ff-only origin feature/f1-popup-detection
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "kr.closeforyou.accessibility.SnapshotFingerprintCodecTest"
```

합성 golden 일치는 **V1 바이트 규격의 해당 fixture 검증**입니다. 실제 Android getter 호출 순서·두 pass 사이 값의 변경·지문의 실제 화면 결합·collector 전체 DTO/진단 동등성·성능/100ms·취소·TTL을 증명하지 않습니다. F0 완료/배경 coverage, 같은 시도의 토큰/구조·본문·outline 결합, 서비스 세션·무효화/TTL의 P1은 미해결입니다. 다음 승인 단계에서 A 소유 보호 증거/대상 노드 기록을 분리하고 기존 두 pass의 실제 관측값에 결합해야 합니다. 세 번째 Android 전체 순회, 부분 Ready 승격 또는 가짜 지문에 의한 통과는 허용하지 않습니다.

실제 API35/37 계측은 이번 단계에서 실행하지 않습니다. Scroll malformed, 별개 Window/frame 불일치와 원래14개 UNOBSERVED는 그대로입니다. 이 codec 구현을 수집 복구·F1 정확도·실제 캡처/OCR 성공으로 해석하지 않습니다.
