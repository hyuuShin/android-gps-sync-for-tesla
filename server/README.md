# 본인의 Tesla OAuth 서버 구성

이 서버는 개인 테스트용 Cloudflare Worker입니다. 토큰 DB 없이 브라우저에 사용자 Access/Refresh Token JSON을 표시하고 사용자가 Android에 직접 옮깁니다. 실제 값은 [루트 README의 변수 목록](../README.md#설정-변수-전체-목록)을 기준으로 개인 환경에 입력합니다.

Cloudflare나 Tesla Developer를 처음 사용한다면 [화면별 시작 가이드](../docs/FIRST_TIME_SETUP.md)에서 계정·주소 준비와 등록 화면의 입력 항목을 먼저 확인하세요. 아래는 키 생성과 CLI 배포를 포함한 실행 절차입니다.

## 1. 앱과 공개 HTTPS 주소 결정

1. 이메일 인증·MFA가 완료된 본인의 Tesla 계정을 준비하고 [Tesla Developer](https://developer.tesla.com/)에서 애플리케이션을 생성합니다. 등록 자격·약관·결제·사용 한도는 포털에서 확인합니다.
2. Cloudflare에서 본인 Worker 이름과 공개 주소를 정합니다. 아래의 `https://login.example.com`은 실행 가능한 제공 서버가 아닌 설명용 예시입니다. 실제 Worker의 `workers.dev` 주소 또는 본인 도메인을 사용하세요. Tesla가 해당 도메인을 수락하는지 확인합니다.
3. Tesla 앱의 Allowed Origin에 본인 `ORIGIN`, Allowed Redirect URI에 `ORIGIN/auth/tesla/callback`을 등록합니다. 대소문자·경로·마지막 슬래시까지 실제 요청과 일치시킵니다.
4. 차량 데이터·위치 권한을 앱에 설정합니다. OAuth 요청은 `openid offline_access vehicle_device_data vehicle_location`입니다. 사용자도 이 범위에 동의해야 합니다.
5. Client ID를 개인 설정에 저장하고 Client Secret은 비밀 저장소에 보관합니다.

로그인은 [Authorization Code 흐름](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens)을 사용합니다. Secret은 서버에서만 사용하며 Android의 Refresh Token 교환에는 Client ID와 Refresh Token을 사용합니다. 인증 엔드포인트, 요청 범위·등록 요구사항은 이 공식 문서를 기준으로 점검하세요.

## 2. 본인 키 쌍 생성

저장소 루트에서 실행합니다. 이미 키가 있으면 재사용 여부를 확인하고 **덮어쓰지 않습니다**. 개인키는 Worker에 업로드하지 않습니다.

```sh
mkdir -p artifacts/tesla-fleet
# 두 파일이 모두 없는 경우에만 생성
if [ ! -e artifacts/tesla-fleet/private-key.pem ] && [ ! -e artifacts/tesla-fleet/public-key.pem ]; then
  (umask 077
   openssl ecparam -name prime256v1 -genkey -noout -out artifacts/tesla-fleet/private-key.pem
   openssl ec -in artifacts/tesla-fleet/private-key.pem -pubout -out artifacts/tesla-fleet/public-key.pem)
fi
openssl pkey -pubin -in artifacts/tesla-fleet/public-key.pem -text -noout
```

곡선은 P-256/secp256r1/prime256v1입니다. 공개키 PEM 전체를 `PUBLIC_KEY`에 등록합니다. `wrangler.toml`에서는 아래 형태의 TOML 여러 줄 문자열을 사용할 수 있습니다. `<...>` 표시는 실제 공개키의 Base64 본문으로 교체합니다.

```toml
PUBLIC_KEY = """
-----BEGIN PUBLIC KEY-----
<PUBLIC_KEY_BASE64>
-----END PUBLIC KEY-----
"""
```

이 문자열은 `.example` 파일에 실제로 넣지 않습니다. Worker는 개인키/잘못된 PEM 외피를 거절합니다. 암호학적 곡선 확인은 위 OpenSSL 명령과 Tesla 등록 결과로 확인합니다. 공개키는 다음 주소에 계속 제공해야 합니다. [Partner 등록 요구사항](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints)

```text
https://본인도메인/.well-known/appspecific/com.tesla.3p.public-key.pem
```

## 3. Worker 배포와 Secret 등록

루트에서 `cp -n server/wrangler.toml.example server/wrangler.toml`을 실행하고 `[vars]`의 `ORIGIN`, `CLIENT_ID`, `PUBLIC_KEY`, `AUDIENCE`를 본인 값으로 수정합니다. `ORIGIN`은 HTTPS origin만 입력합니다. callback은 코드가 여기서 파생하므로 별도 환경변수로 지정하지 않습니다.

아래 명령은 **본인 Cloudflare 계정에 배포**합니다. Worker 이름을 먼저 확인하세요. Wrangler 4 CLI가 없다면 `npx`가 설치 확인을 요청할 수 있습니다.

```sh
cd server
npx wrangler@4 login
npx wrangler@4 deploy
npx wrangler@4 secret put CLIENT_SECRET
```

Secret 입력 프롬프트에 본인 값을 입력합니다. 셸 명령 문자열·TOML·채팅에 Secret을 기입하지 않습니다. `PUBLIC_KEY`와 공개 설정을 대시보드에서 관리하는 경우에도 이후 CLI 배포 시 TOML의 `[vars]`가 적용되므로 두 관리 방식을 혼용하지 마세요. Secret은 `[vars]`와 별도로 관리됩니다.

로그·트레이스는 비활성화된 예제를 유지합니다. OAuth callback 쿼리에는 일회용 code가 있고, 성공 HTML에는 토큰이 있습니다. `wrangler tail`이나 요청/응답 수집으로 이를 공개 로그에 남기지 마세요.

본인 URL로 다음을 확인합니다.

- `GET /`: `publicKeyConfigured: true`, `oauthConfigured: true`. 설정 존재만 확인하며 자격 증명의 유효성까지 보장하지 않습니다.
- `GET /.well-known/appspecific/com.tesla.3p.public-key.pem`: 200, 본인 공개키와 일치.
- `GET /auth/tesla/start`: Tesla authorize로 302, `client_id`, `redirect_uri`, `scope` 확인. 생성된 state·쿠키를 외부에 공유하지 않습니다.
- state/쿠키 없는 `GET /auth/tesla/callback`: 400. 정상 보안 동작입니다.

## 4. 지역별 Partner 등록

사용할 지역의 Fleet origin을 서버 `AUDIENCE`, Android 지역, 등록 스크립트의 `TESLA_DEFAULT_REGION`에 맞춥니다. 현재 예제는 NA/EU를 제공합니다. 한국 테스트는 NA 설정부터 확인합니다. 다른 지역 토큰을 재사용하거나 앱 화면의 지역 선택만 바꾸면 정상 등록이 보장되지 않습니다.

루트에서 공개키 접근만 먼저 확인합니다. 아래 Client ID·origin은 본인 값으로 바꾸세요.

```sh
TESLA_DEFAULT_REGION=NA python3 scripts/register_partner.py \
  --origin https://login.example.com --client-id YOUR_CLIENT_ID
```

Partner 등록을 실행할 때는 `--register`를 추가합니다. Client Secret은 숨김 입력으로 받으며, Partner Token은 메모리에서만 사용합니다. 서버의 사용자 OAuth와 별개인 **일회성 계정 등록 POST**가 발생합니다.

```sh
TESLA_DEFAULT_REGION=NA python3 scripts/register_partner.py \
  --origin https://login.example.com --client-id YOUR_CLIENT_ID --register
```

스크립트는 `client_credentials`로 Partner Token 발급 → 해당 Fleet API의 `POST /api/1/partner_accounts`에 `{"domain":"본인호스트"}` 전송 → 공개키 등록 조회 순서로 실행합니다. 응답의 토큰/개인정보를 출력하거나 파일로 저장하지 않습니다. 등록 HTTP와 공개키 조회 성공을 확인하세요. EU도 사용한다면 EU에 맞춰 별도로 등록합니다. 기존 등록 실패 응답은 상태를 확인하고 처리하며 무조건 반복하지 않습니다.

[Partner Token 공식 문서](https://developer.tesla.com/docs/fleet-api/authentication/partner-tokens), [Partner API 공식 문서](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints)

## 5. Android 연결

`android/tesla.properties`의 `TESLA_LOGIN_URL`을 `ORIGIN/auth/tesla/start`로, `TESLA_CLIENT_ID`를 서버의 `CLIENT_ID`로 설정하고 재빌드합니다. TeslaAuth에서 로그인 결과 JSON을 복사해 Save합니다. Partner Token은 앱의 사용자 토큰 칸에 넣지 않습니다. VIN은 기기에서만 입력합니다.

Worker의 쿠키는 Secure/HttpOnly/SameSite=Lax이며 state 서명·10분 유효성을 확인합니다. 응답은 no-store/no-referrer/CSP를 적용하고 토큰 교환은 리디렉션을 따르지 않습니다. 브라우저 방문 기록이나 클립보드는 별도이므로 사용 후 직접 정리합니다. 공개 서비스에 제공할 자동 로그인·세션 관리·남용 방지는 별도 설계가 필요합니다.

## 문제 해결과 검증

| 증상 | 확인할 항목 |
|---|---|
| Worker 503 | ORIGIN, URL 형식, PUBLIC_KEY PEM, 필수 scope, Client ID/Secret |
| Unknown host 400 | 실제 접속 origin과 ORIGIN의 일치 |
| Tesla redirect 오류 | Tesla 등록 Redirect URI와 callback의 정확한 일치 |
| 콜백 400 | 새 로그인 시작, 같은 브라우저/쿠키, 10분 내 완료 |
| `TOKEN_HTTP_400/401` | Client ID/Secret, 코드 만료·재사용, callback, audience |
| Fleet 403/404 | 사용자 동의, Partner 등록, 앱 활성 상태, VIN·지역 |
| Fleet 408/429 | 차량 온라인 상태, 조회 한도. 자동 깨우기·반복 조회 없음 |

```sh
node --test server/worker.test.mjs
```

단위 테스트는 외부 인증 서버를 모킹합니다. 실제 로그인과 차량 조회는 사용자가 본인 환경에서 별도로 검증합니다. 참고 문서 확인일: 2026-09-21.
