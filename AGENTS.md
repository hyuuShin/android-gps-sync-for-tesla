# Repository setup assistance

이 저장소에서 “테슬라 위치정보 설정”, “프로젝트 설정을 도와줘”, Tesla/Fleet/OAuth 설정 요청이 들어오면 이 절차를 따른다. 사용자가 쓰는 언어로 안내한다.

## 먼저 읽고 확인할 것

1. `README.md`의 전체 변수 표와 `server/README.md`를 읽는다. 기능 설명은 `GPS_TEST.md`, `docs/TESLA_FLEET_API_GUIDE.md`를 참고한다.
2. 현재 파일과 작업 내용을 확인한다. `android/tesla.properties`, `server/wrangler.toml`, 키가 이미 있으면 덮어쓰지 않는다. 비밀 파일의 전체 내용이나 환경변수 전체를 출력하지 않는다. 값 자체보다 설정 유무·URL 간 일치만 보고한다.
3. JDK, SDK 36, Node 22+, Python 3.10+를 검사한다. SDK 경로는 개인 `android/local.properties` 또는 ANDROID_HOME에만 둔다. 기존 설정이 없다면 예제에서 개인 설정 파일을 생성하고 아직 필요한 항목을 알려준다.
4. 사용 지역(NA/EU), 기존 Tesla 개발자 앱 유무, 본인 공개 HTTPS origin, 기존 공개키/Partner 등록 유무를 먼저 파악한다. 이미 세션에 주어진 정보는 다시 묻지 않는다. 비밀값이 없는 템플릿·검증·빌드는 답변을 기다리는 동안 진행할 수 있다.

## 단계별 안내

1. 본인 Tesla Developer 앱 생성/확인, MFA, 현재 등록·결제 요구사항 안내. 정책·scope·지역은 변경될 수 있으므로 필요하면 Tesla 공식 문서를 확인한다.
2. 본인 Worker origin을 정하고 Allowed Origin과 `ORIGIN/auth/tesla/callback` 등록을 안내한다. 공유 서버가 제공되는 것처럼 말하지 않는다.
3. P-256 키가 없을 때만 무시되는 개인 경로에 생성한다. 기존 키를 덮어쓰지 않는다. 공개키만 PUBLIC_KEY에 게시하고 개인키는 게시하지 않는다.
4. `server/README.md`의 변수를 채운다. CLIENT_SECRET은 사용자가 Cloudflare Secret 입력 화면/숨김 프롬프트에 직접 넣게 한다. 채팅으로 Secret·토큰·MFA 코드·VIN을 요청하지 않는다.
5. 사용자의 요청 범위에 배포/Partner 등록이 포함되는지 확인하고 해당 범위에서 진행한다. 로컬 준비는 자율적으로 완료하되 사용자가 승인하지 않은 외부 배포·등록을 완료했다고 주장하지 않는다. 필요한 경우 계정 로그인·동의는 사용자에게 맡긴다.
6. 공개키 200, 상태 endpoint, OAuth 시작 302/잘못된 callback 400을 확인한다. callback 전체 URL·state·code·쿠키·성공 HTML은 로그로 출력하지 않는다.
7. Partner Token 발급/등록은 `scripts/register_partner.py`를 사용한다. 기본 실행은 공개키 확인이며, `--register`는 실제 POST이다. API 지역을 명시하고 Partner Token을 사용자 토큰과 구분한다.
8. Android `TESLA_LOGIN_URL = ORIGIN/auth/tesla/start`, Client ID, 초기 지역과 서버 AUDIENCE를 맞춘다. 변경 후 빌드/설치가 필요하며 기존 토큰의 Client ID는 새 빌드로 변경되지 않음을 설명한다.
9. 사용자가 Tesla 로그인·동의 → JSON 복사 → 기기에서 VIN 입력/Save를 진행하도록 안내한다. Refresh Token은 여러 클라이언트에서 동시에 공유하지 않는다.
10. 정확한 위치·알림 권한 및 모의 위치 앱 선택 후 사용자가 위치 조회를 실행하도록 돕는다. 차량 API는 비용이 발생할 수 있으므로 불필요한 반복 호출 없이 기기 수신 좌표·지도 내 위치를 확인하고 중지한다.

## 완료 기준과 보고

- 설정만 요청하면 일관된 변수·다음에 필요한 사용자 단계가 명확해야 한다. 실제 인증 없이 전체 연결 성공이라고 보고하지 않는다.
- 코드 변경 시 `node --test server/worker.test.mjs`, Android 관련 변경 시 `./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`를 실행한다.
- 공개용 정리 요청 시 `python3 scripts/check_public_files.py` 및 Git 추가 파일을 검토한다. 현재 파일 검사와 Git 이력 검사를 구분한다. `.git`이 없으면 과거 이력을 검사했다고 말하지 않는다.
- 최종 답변에는 설정 완료/누락 항목, 검증 결과, 실제 로그인·차량·기기 확인 여부를 짧게 구분한다. 비밀값·VIN·실제 좌표·개인 호스트를 그대로 인용하지 않는다.
- 관련 설정을 추가/변경하면 기본값·예제·README 변수 표·테스트도 함께 맞춘다. Client Secret을 Android BuildConfig에 추가하지 않는다.
