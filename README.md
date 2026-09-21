# Android GPS Sync for Tesla

Tesla Fleet API로 본인 차량의 위치를 한 번 조회하고 Android 모의 위치로 적용하는 Kotlin/Compose 테스트 프로젝트입니다. 각 사용자가 **자신의 Tesla 개발자 앱과 OAuth 서버**를 구성합니다. 공유 로그인 서버나 기존 운영자의 인증값은 제공하지 않습니다.

정상 경로에서 버튼당 차량 조회 1회, 인증 오류 복구 시 추가 1회입니다. 조회한 좌표를 매초 주입하며 최대 5분 후 중지합니다. 실시간 차량 추적·차량 목록 선택·차량 명령은 구현하지 않았습니다. VIN은 앱에서 직접 입력합니다.

## 처음 clone했다면

저장소를 연 코딩 에이전트에 **“테슬라 위치정보 설정”**, **“프로젝트 설정을 도와줘”**라고 입력하세요. [AGENTS.md](AGENTS.md)가 안내 순서와 확인 기준을 정의합니다. 해당 파일을 자동으로 읽지 않는 도구에는 “AGENTS.md와 README.md를 읽고 설정을 도와줘”라고 요청하세요. Secret·토큰·VIN을 채팅에 붙여 넣을 필요는 없습니다.

직접 설정할 때는 다음 순서를 따릅니다.

1. JDK 17 이상(Gradle 실행은 설치된 호환 JDK), Android SDK Platform 36·Build Tools, Node.js 22 이상, Python 3.10 이상을 준비합니다. Android Studio의 내장 JDK도 사용할 수 있습니다. SDK는 `ANDROID_HOME` 또는 `android/local.properties`의 `sdk.dir`로 지정합니다.
2. 아래 예제를 복사합니다. 파일이 이미 있으면 덮어쓰지 말고 필요한 항목만 수정합니다.

   ```sh
   cp -n android/tesla.properties.example android/tesla.properties
   cp -n server/wrangler.toml.example server/wrangler.toml
   ```

3. [서버 설정 가이드](server/README.md)에 따라 Tesla 앱 생성 → 공개 HTTPS 주소 결정 → P-256 공개키 게시 → 지역별 Partner 등록 → OAuth 준비를 마칩니다.
4. `android/tesla.properties`에 본인 서버의 `TESLA_LOGIN_URL`, 본인 앱의 `TESLA_CLIENT_ID`, 사용할 `TESLA_DEFAULT_REGION`을 입력합니다. 서버 `AUDIENCE`와 앱의 Fleet 지역을 맞춥니다.
5. 빌드·검증 후 기기에 설치합니다.

   ```sh
   ./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   node --test server/worker.test.mjs
   python3 -m unittest discover -s scripts -p 'test_*.py'
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

6. 개발자 옵션 → 모의 위치 앱 → **Tesla GPS Sync**를 선택하고 앱에서 정확한 위치·알림 권한을 허용합니다.
7. **TeslaAuth · 인증 정보 설정 → Tesla 로그인 · 토큰 묶음 받기**를 누릅니다. Tesla 로그인·동의를 완료하고 결과 JSON 전체를 앱에 붙여 넣습니다. 본인 VIN을 입력하고 Save합니다.
8. **차량 위치 조회 → 모의 GPS 시작**을 누릅니다. 기기 수신 좌표와 지도 내 위치를 비교하고 테스트 후 중지합니다. [상세 사용법](GPS_TEST.md)을 참고하세요.

설정이 없어도 빌드·단위 테스트는 가능합니다. 미설정 로그인 버튼은 설정 안내를 표시합니다. 서버 환경변수가 없으면 OAuth를 시작하지 않습니다. 앱 설정 변경은 **재빌드·재설치**해야 적용됩니다. 기존 토큰 묶음에는 발급 앱의 Client ID가 들어 있으므로 개발자 앱을 바꾸면 Clear 후 다시 로그인하세요.

## 설정 변수 전체 목록

### Android 및 Python

공개 기본값은 [config/tesla.defaults.properties](config/tesla.defaults.properties)에 모았습니다. Android 우선순위는 **동일 이름 환경변수 → `android/tesla.properties` → 기본값**입니다. `-P` Gradle 옵션이나 `local.properties`로 Tesla 설정을 읽지는 않습니다. Python 예제와 Partner 등록 스크립트는 **환경변수 → 기본값**을 사용하며 Android 개인 설정 파일은 읽지 않습니다.

| 변수 | 기본값 | 설정 용도 |
|---|---|---|
| `TESLA_LOGIN_URL` | 빈 값 | Android가 여는 `https://본인도메인/auth/tesla/start` |
| `TESLA_CLIENT_ID` | 빈 값 | 본인 Tesla 앱 ID. 수동 토큰 입력 시 갱신에 사용. JSON의 `client_id`가 우선 |
| `TESLA_DEFAULT_REGION` | `NA` | 최초 화면 지역. `NA` 또는 `EU`; Python의 호출 지역 |
| `TESLA_TOKEN_URL` | `https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token` | Android/Python 토큰 갱신, Python Partner Token 발급 |
| `TESLA_FLEET_NA_URL` | `https://fleet-api.prd.na.vn.cloud.tesla.com` | 북미·아시아태평양용 Fleet origin |
| `TESLA_FLEET_EU_URL` | `https://fleet-api.prd.eu.vn.cloud.tesla.com` | 유럽용 Fleet origin |

한국 테스트는 `NA`부터 설정하고 계정·차량의 실제 지역을 확인하세요. 이 앱의 지역 선택지는 NA/EU이며 다른 지역은 별도 구현·검증이 필요합니다. URL은 HTTPS여야 하며 사용자 정보·쿼리·fragment를 넣지 않습니다. Fleet origin에는 경로나 끝의 `/`를 넣지 않습니다. 엔드포인트를 변경하면 토큰 전송 대상도 바뀌므로 본인이 관리하고 신뢰하는 값만 사용하세요.

Android 설정은 `BuildConfig`에 들어가 **APK에서 읽을 수 있습니다**. Client Secret, 개인키, 사용자/Partner 토큰, VIN은 빌드 설정에 넣지 않습니다.

### OAuth 서버

Worker 런타임 환경변수입니다. 공개값은 개인 `server/wrangler.toml`의 `[vars]`, `CLIENT_SECRET`은 Cloudflare Secret으로 등록합니다. 기본값 정의는 [server/config.mjs](server/config.mjs)에 있습니다.

| 변수 | 기본값 / 필수 여부 | 설정 용도 |
|---|---|---|
| `ORIGIN` | 필수, 기본값 없음 | 본인의 공개 HTTPS origin. 허용 호스트 및 callback 생성 기준 |
| `CLIENT_ID` | OAuth 필수 | Android `TESLA_CLIENT_ID`와 같은 개발자 앱 ID |
| `CLIENT_SECRET` | OAuth 필수, **Secret** | 서버에서만 코드 교환 및 state 서명에 사용 |
| `PUBLIC_KEY` | 공개키 게시·Partner 등록 필수 | 본인이 생성한 P-256 **공개키** PEM. 실제 줄바꿈 또는 `\n` 지원 |
| `AUDIENCE` | NA Fleet origin | 토큰 발급 지역. EU는 `TESLA_FLEET_EU_URL` 기본값과 맞춤 |
| `AUTHORIZE_URL` | `https://auth.tesla.com/oauth2/v3/authorize` | 사용자 로그인·동의 엔드포인트 |
| `TOKEN_URL` | Android `TESLA_TOKEN_URL`과 같은 공식 기본 URL | 서버의 코드 교환 엔드포인트 |
| `SCOPES` | `openid offline_access vehicle_device_data vehicle_location` | 로그인 요청 권한. 현재 기능에 필요한 네 범위는 제거 불가 |

Worker 이름·진입점·호환 날짜·로그 비활성화는 [wrangler.toml.example](server/wrangler.toml.example)에 있습니다. `CLIENT_ID`/`CLIENT_SECRET`만 설정하던 이전 구성도 이제 `ORIGIN`과 본인 `PUBLIC_KEY`를 설정해야 합니다.

### 자동으로 결정되는 값과 기기 런타임 값

| 항목 | 기준 / 저장 위치 |
|---|---|
| OAuth 시작 | `ORIGIN + /auth/tesla/start` → Android `TESLA_LOGIN_URL`에 입력 |
| OAuth callback / Redirect URI | `ORIGIN + /auth/tesla/callback`; Tesla 포털 등록값과 정확히 일치 |
| Allowed Origin | Tesla 포털에 `ORIGIN` 등록 |
| 공개키 URL | `ORIGIN + /.well-known/appspecific/com.tesla.3p.public-key.pem` |
| Partner 등록 domain | `ORIGIN`의 호스트만 사용. `https://`나 경로 제외 |
| 위치 조회 | 선택된 Fleet origin + `/api/1/vehicles/{VIN}/vehicle_data?endpoints=location_data` |
| 사용자 `access_token`, `refresh_token`, `vin`, `expires_at`, `client_id` | 앱의 TeslaAuth에서 입력/가져오기, Keystore AES-GCM으로 기기에 저장 |
| Python 토큰 JSON 파일 경로 | 실행 인자로 지정, 저장소 밖 또는 무시되는 `artifacts/` 안에 보관, 권한 `600` |

프로토콜의 경로·grant type, 응답 필드, state 10분, 네트워크 제한, 위치 유효성 검사와 모의 위치 5분 제한은 코드의 동작 규칙입니다. 사용자별 등록 설정과 구분하며 변경할 때 관련 테스트를 갱신합니다.

## Python에서 위치만 조회

macOS/Linux용 표준 라이브러리 예제입니다. 별도 사용자 로그인으로 받은 JSON에 `vin`을 추가하여 개인 파일로 저장하고 `chmod 600`을 적용합니다. 파일 구조는 [상세 가이드](docs/TESLA_FLEET_API_GUIDE.md)에 있습니다.

```sh
TESLA_DEFAULT_REGION=NA python3 docs/examples/fleet_location.py artifacts/tesla-fleet/user-token.json
```

성공 시 **실제 좌표가 stdout에 출력**됩니다. 결과를 공개 로그에 저장하지 마세요. Android와 Python이 같은 Refresh Token을 동시에 갱신하지 않도록 별도 로그인을 사용합니다.

## 저장소 구조와 공개 전 확인

- `android/`: Kotlin 앱, 로컬 설정 예제, 단위·계측 테스트
- `server/`: OAuth Worker, 배포 설정 예제, 서버 테스트와 온보딩
- `config/`: 개인 식별자가 없는 공개 기본값
- `scripts/`: Partner 등록 도우미와 공개 파일 점검
- `docs/`: 구현 설명, Python 예제, 정적 도식
- [AGENTS.md](AGENTS.md): 에이전트가 설정 요청을 처리하는 절차

`.gitignore`는 개인 설정·키·토큰 파일·로그·기기 캡처·빌드 산출물을 제외합니다. **파일을 ZIP으로 통째로 공유하면 이 규칙이 적용되지 않습니다.** Git에 실제로 추가할 파일을 검토하세요. 이미 추적된 파일은 ignore만 추가해도 제거되지 않습니다.

```sh
python3 scripts/check_public_files.py
# git init / git add 후에도 실행하고 staging 파일을 직접 검토
# Git 이력이 있는 경우 별도로 전체 이력 secret scan 수행
```

실제 Tesla 로그인, 계정 등록 상태, 유료 API 동작과 기기의 모의 위치는 본인 환경에서 확인해야 합니다. 서버 단위 테스트는 Tesla 요청을 모킹합니다. 소스 검증 통과만으로 실제 차량 연결 성공을 의미하지 않습니다.
