# 처음 사용자를 위한 화면별 설정 가이드

먼저 **본인의 인증 서버 호스팅과 Tesla Developer 앱**을 준비합니다. 이 프로젝트는 Cloudflare Workers에서 로그인 서버를 실행합니다. Android 설치만으로 Tesla 로그인을 사용할 수는 없습니다.

이 문서는 2026-09-21에 확인한 공개 공식 문서를 바탕으로 합니다. 로그인 후 계정별 화면을 직접 캡처한 문서는 아니며, 아래 화면 구성 예시는 설명용입니다. 메뉴 위치·번역·등록 자격은 실제 포털에서 확인하세요. Tesla 등록 화면의 항목은 입력 목적을 기준으로 설명합니다.

## 전체 순서

| 순서 | 여는 화면 | 여기서 준비할 것 |
|---|---|---|
| 1 | Cloudflare → Workers & Pages | 호스팅 계정과 본인 HTTPS 주소 |
| 2 | Tesla Developer → 앱 생성/앱 설정 | 앱 정보, Origin, Redirect URI, 권한, Client ID·Secret |
| 3 | Tesla Developer → Billing and Usage | 결제 수단과 사용 한도 |
| 4 | 터미널 → Cloudflare Worker → Settings | 공개키·서버 배포, Client Secret 등록 |
| 5 | 터미널 → Android 앱 | Partner 등록, 재빌드, 사용자 로그인 |

기존 Worker·Tesla 앱·키가 있다면 먼저 해당 설정을 확인하고 재사용하세요. 아래 예시 주소는 제공되는 서버가 아닙니다.

## 1. Cloudflare: 호스팅 계정과 주소 준비

1. [Cloudflare 대시보드](https://dash.cloudflare.com/)에서 본인 계정으로 가입/로그인하고 사용할 계정을 선택합니다.
2. **Workers & Pages** 페이지를 엽니다. Workers는 이 저장소의 서버 코드를 실행하는 서비스입니다.
3. **Your subdomain**을 확인합니다. 공식 문서는 이 항목 옆의 **Change**에서 계정의 `workers.dev` 서브도메인을 설정할 수 있다고 안내합니다. 기존 값을 바꾸면 다른 Worker에도 영향을 줄 수 있으므로 기존 계정에서는 현재 값을 사용하세요.
4. 로컬 `server/wrangler.toml`의 `name`에 사용할 Worker 이름을 정합니다. 예제 이름은 `tesla-location-login`입니다. 파일이 없을 때만 [서버 가이드](../server/README.md#3-worker-배포와-secret-등록)에 따라 예제를 복사합니다.

```text
화면 구성 예시 — Cloudflare
Workers & Pages
  Your subdomain: <본인 계정 서브도메인>.workers.dev   [Change]
  [Create application]

Worker 주소 = https://<Worker 이름>.<본인 계정 서브도메인>.workers.dev
```

**Create application**은 대시보드에서 템플릿/Git로 새 앱을 만드는 진입점입니다. 이 저장소는 아래 4단계에서 Wrangler CLI로 배포하므로 여기서 별도 템플릿 앱을 만들 필요는 없습니다. 서브도메인 초기 설정 화면이 나타나면 본인 값을 설정하고, 배포 후 표시되는 실제 URL을 다시 확인합니다.

본인 주소를 `ORIGIN`으로 사용합니다. 경로와 마지막 `/`를 붙이지 않습니다. 예를 들어 본인 도메인을 쓴다면 `https://login.example.com` 형태입니다. `workers.dev`를 쓰면 별도 도메인 구매 없이 시작할 수 있지만, **Tesla 등록 화면에서 해당 주소가 수락되는지 확인해야 합니다**. 수락되지 않으면 본인 도메인을 연결한 뒤 등록값을 맞추세요.

완료 기준: 사용할 Cloudflare 계정, Worker 이름, HTTPS origin을 알고 있습니다. 아직 서버를 배포한 상태는 아닙니다.

근거: [Cloudflare 대시보드 시작하기](https://developers.cloudflare.com/workers/get-started/dashboard/), [workers.dev 주소 설정](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/).

## 2. Tesla Developer: 개발자 앱 등록

1. 본인 Tesla 계정의 이메일 인증과 MFA를 완료합니다.
2. [Tesla Fleet API 시작하기](https://developer.tesla.com/docs/fleet-api/getting-started/what-is-fleet-api)의 **Create Application and Access Dashboard**로 이동하거나 [개발자 대시보드](https://developer.tesla.com/dashboard)를 엽니다.
3. 앱 생성/접근 신청 화면에서 실제 정보를 입력합니다. 공식 안내는 법적 사업 정보, 앱 이름, 설명, 사용 목적을 요구합니다. 개인 테스트 사용자는 포털에서 본인에게 적용되는 자격·필수 항목을 확인하세요. 요구 항목을 임의의 사업 정보로 채우지 않습니다.
4. 앱의 URL 및 권한 설정을 아래 표와 맞춥니다. 기존 앱이라면 설정 화면에서 현재 값을 확인합니다.

| 화면에서 찾을 항목 | 입력 또는 선택할 내용 |
|---|---|
| 애플리케이션 이름 | 본인이 구분할 수 있는 이름. 공식 안내상 기존 이름과 중복되면 신청이 거절될 수 있음 |
| 설명·사용 목적 | 예: 본인 차량 위치를 조회해 Android 모의 위치 기능을 시험하는 개인용 앱 |
| 법적 정보·약관 | 실제 정보 입력, 자격과 이용 조건 확인 |
| Allowed Origin | 본인의 `ORIGIN` — 예: `https://login.example.com` |
| Allowed Redirect URI | 같은 origin에 `/auth/tesla/callback` 추가 |
| 차량 정보 권한 | `Vehicle Information` / `vehicle_device_data` |
| 차량 위치 권한 | `Vehicle Location` / `vehicle_location` |

```text
입력값 대응 예시 — 실제 Tesla 화면 캡처가 아님
Allowed Origin        https://login.example.com
Allowed Redirect URI  https://login.example.com/auth/tesla/callback

차량 데이터 권한       Vehicle Information  → vehicle_device_data
위치 권한              Vehicle Location     → vehicle_location
```

OAuth 요청에는 로그인용 `openid`와 갱신용 `offline_access`도 포함합니다. 이 저장소의 `SCOPES`는 `openid offline_access vehicle_device_data vehicle_location`입니다. 포털에서 로그인·갱신 항목을 따로 제공한다면 함께 확인하세요. 차량 명령·충전·에너지 제어 권한은 현재 기능에 필요하지 않습니다. [공식 권한 목록](https://developer.tesla.com/docs/fleet-api/authentication/overview#scopes)

`Allowed Redirect URI`에 Android가 여는 `/auth/tesla/start`를 넣지 않습니다. Tesla 로그인 후 **돌아오는 주소**인 `/auth/tesla/callback`을 등록해야 합니다. [공식 OAuth 흐름](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens)

등록 후 앱의 자격 증명에서 **Client ID**와 **Client Secret**을 확인합니다. Client ID는 서버 `CLIENT_ID`와 Android `TESLA_CLIENT_ID`에 같은 값을 넣습니다. Client Secret은 본인 비밀 저장소에 보관하고 4단계에서 Cloudflare Secret에 직접 입력합니다. 채팅·스크린샷·Android 설정에 넣지 않습니다.

완료 기준: 앱의 승인/활성 상태를 확인하고, URL과 필요한 권한을 등록했으며 Client ID·Secret을 확보했습니다. 신청 제출만으로 활성화가 완료되었다고 판단하지 않습니다.

## 3. Tesla Developer: 결제와 사용 한도

앱 관리 대시보드의 **Billing and Usage** 페이지를 찾아 다음을 확인합니다.

| 화면 항목 | 할 일 |
|---|---|
| Manage Payment | 본인의 결제 수단 설정 |
| Update Limit | 본인이 허용할 사용 한도 설정 |
| 사용량·앱 상태 | 사용 가능 여부와 한도 확인 |

Fleet API는 사용량에 따라 과금됩니다. 공식 안내상 결제 수단이 없거나 한도를 초과하면 앱이 비활성화될 수 있고, 기본 한도는 0입니다. 금액은 이 문서에 고정하지 않으므로 현재 포털의 요금과 조건을 읽고 직접 설정하세요. [Tesla 결제·한도 공식 안내](https://developer.tesla.com/docs/fleet-api/billing-and-limits)

완료 기준: 결제 수단과 본인이 정한 한도가 반영되어 있습니다. 개발자 등록과 Android에서 하는 사용자 로그인·동의는 별도 단계입니다.

## 4. 인증 서버 배포와 Cloudflare 설정 화면

[서버 가이드의 키 생성](../server/README.md#2-본인-키-쌍-생성)부터 진행합니다. 기존 키는 덮어쓰지 않고, 공개키만 `PUBLIC_KEY`에 넣습니다. 개인키는 서버에 게시하지 않습니다.

`server/wrangler.toml`에 본인의 `ORIGIN`, `CLIENT_ID`, `PUBLIC_KEY`, `AUDIENCE`를 채운 다음 [배포 명령](../server/README.md#3-worker-배포와-secret-등록)을 실행합니다. `wrangler login`이 여는 브라우저에서 본인 Cloudflare 계정과 허용 내용을 확인합니다. `deploy`는 해당 계정에 실제 서버를 게시합니다.

배포 후 **Workers & Pages → 본인 Worker → Settings → Domains & Routes**에서 실제 `workers.dev` 주소를 확인합니다. 예상한 origin과 다르면 서버 `ORIGIN`, Tesla 등록 URL, Android 로그인 URL을 같은 주소로 수정하고 다시 배포/빌드합니다. 공개키 URL은 Tesla가 로그인 없이 읽을 수 있어야 합니다.

Client Secret은 서버 가이드의 숨김 CLI 프롬프트 또는 다음 화면 중 한 방법으로 등록합니다.

```text
Cloudflare → Workers & Pages → 본인 Worker → Settings
  Variables and Secrets → Add
    Type          Secret
    Variable name CLIENT_SECRET
    Value         <본인 Tesla Client Secret을 직접 입력>
  Deploy
```

대시보드의 **Deploy**를 눌러 적용합니다. `CLIENT_SECRET`은 반드시 **Secret** 유형으로 저장합니다. 나머지 공개 변수는 로컬 TOML에서 관리하세요. 대시보드에서만 공개 변수를 바꾸면 다음 CLI 배포 때 TOML 값으로 적용될 수 있습니다. [Cloudflare Secret 화면 안내](https://developers.cloudflare.com/workers/configuration/secrets/)

| 브라우저에서 열 경로 | 기대 결과 |
|---|---|
| `ORIGIN/` | `publicKeyConfigured: true`, `oauthConfigured: true` |
| `ORIGIN/.well-known/appspecific/com.tesla.3p.public-key.pem` | 본인 공개키 표시, HTTP 200 |
| `ORIGIN/auth/tesla/start` | Tesla 로그인 화면으로 이동, HTTP 302 |
| `ORIGIN/auth/tesla/callback`를 쿼리 없이 직접 열기 | HTTP 400 — 정상 보안 동작 |

상태 값은 설정의 존재만 확인합니다. 실제 자격 증명 유효성은 로그인으로 확인해야 합니다. 로그인 중 callback 전체 URL, 쿠키, 결과 JSON 화면은 공유하거나 로그로 남기지 않습니다.

## 5. Partner 등록 후 Android 로그인

1. [지역별 Partner 등록](../server/README.md#4-지역별-partner-등록)을 실행합니다. 스크립트 기본 실행은 공개키 확인이며, `--register`를 붙이면 실제 Tesla 등록 POST를 보냅니다. 앱 생성만으로 이 단계가 완료되지는 않습니다.
2. 서버 `AUDIENCE`, Partner 등록 지역, Android의 초기 지역을 맞춥니다. 지원 설정은 NA/EU이며 한국 테스트는 NA부터 확인합니다.
3. [Android 연결](../server/README.md#5-android-연결)에 따라 `TESLA_LOGIN_URL = ORIGIN/auth/tesla/start`와 Client ID를 설정하고 재빌드·설치합니다.
4. 앱의 **TeslaAuth · 인증 정보 설정 → Tesla 로그인 · 토큰 묶음 받기**에서 Tesla 로그인과 권한 동의를 완료합니다.
5. 결과 JSON을 기기에 붙여 넣고 VIN은 기기에서 직접 입력해 **Save**합니다. Partner Token을 사용자 토큰으로 입력하지 않습니다.
6. [기기 테스트 순서](../GPS_TEST.md)에 따라 권한·모의 위치 앱을 설정한 뒤 위치 조회를 한 번 실행하고 확인 후 중지합니다.

최종 완료는 **서버 설정 확인 → 실제 사용자 로그인 성공 → 실제 차량 조회·기기 위치 확인**을 각각 구분합니다. 문서를 따라 설정했거나 단위 테스트가 통과한 것만으로 실제 차량 연결까지 검증된 것은 아닙니다.
