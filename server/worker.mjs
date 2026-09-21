import {readConfig} from './config.mjs';

const COOKIE = '__Host-tesla-oauth';
const clearCookie = `${COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0`;
const headers = {
  'Cache-Control': 'no-store', 'Pragma': 'no-cache',
  'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
};
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const html = (body, status = 200, extra = {}) => new Response(`<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Tesla 위치 테스트 로그인</title><body>${body}</body></html>`, {status, headers:{...headers,'Content-Type':'text/html; charset=utf-8',...extra}});
async function sign(value, secret) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), {name:'HMAC',hash:'SHA-256'}, false, ['sign']);
  return [...new Uint8Array(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(value)))].map(v=>v.toString(16).padStart(2,'0')).join('');
}
export default {
  async fetch(request, env = {}) {
    const url = new URL(request.url);
    if (!['GET','HEAD'].includes(request.method)) return new Response('Method not allowed',{status:405,headers:{...headers,Allow:'GET, HEAD'}});
    let config;
    try { config = readConfig(env); }
    catch { return html('<h1>서버 설정이 필요합니다</h1><p>server/README.md의 환경변수를 확인하세요.</p>',503); }
    const {origin, callback, audience, publicKey, authorizeUrl, tokenUrl, scopes} = config;
    if (url.origin !== origin) return new Response('Unknown host',{status:400,headers});
    if (url.pathname === '/.well-known/appspecific/com.tesla.3p.public-key.pem' && !publicKey) return new Response('Public key not configured',{status:503,headers});
    if (url.pathname === '/.well-known/appspecific/com.tesla.3p.public-key.pem') return new Response(request.method === 'HEAD' ? null : publicKey,{headers:{'Content-Type':'application/x-pem-file','Cache-Control':'public, max-age=300','X-Content-Type-Options':'nosniff'}});
    if (url.pathname === '/') return Response.json({service:'Tesla Fleet API',publicKeyConfigured:Boolean(publicKey),oauthConfigured:Boolean(env.CLIENT_ID && env.CLIENT_SECRET)},{headers});
    if (!['/auth/tesla/start','/auth/tesla/callback'].includes(url.pathname)) return new Response('Not found',{status:404,headers});
    if (request.method !== 'GET') return new Response(null,{status:405,headers});
    if (!env.CLIENT_ID || !env.CLIENT_SECRET) return html('<h1>로그인 준비 중</h1><p>서버의 Tesla 앱 자격 증명이 설정되지 않았습니다.</p>',503);
    if (url.pathname.endsWith('/start')) {
      const value = Date.now() + '.' + crypto.randomUUID();
      const state = value + '.' + await sign(value, env.CLIENT_SECRET);
      const auth = new URL(authorizeUrl);
      auth.search = new URLSearchParams({client_id:env.CLIENT_ID,response_type:'code',redirect_uri:callback,scope:scopes,state,prompt_missing_scopes:'true'}).toString();
      return new Response(null,{status:302,headers:{...headers,Location:auth.toString(),'Set-Cookie':`${COOKIE}=${state}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=600`}});
    }
    const cookie = request.headers.get('Cookie')?.split(';').map(v=>v.trim()).find(v=>v.startsWith(COOKIE+'='))?.slice(COOKIE.length+1);
    const state = url.searchParams.get('state') || '';
    const parts = state.split('.');
    const createdAt = Number(parts[0]);
    const valid = state.length < 256 && state === cookie && parts.length === 3 && Number.isFinite(createdAt) && Date.now() - createdAt >= 0 && Date.now() - createdAt < 600_000 && parts[2] === await sign(parts.slice(0,2).join('.'),env.CLIENT_SECRET);
    if (!valid) return html('<h1>로그인을 다시 시작하세요</h1><p>로그인 세션이 만료되었거나 일치하지 않습니다.</p>',400,{'Set-Cookie':clearCookie});
    if (url.searchParams.has('error') || !url.searchParams.get('code')) return html('<h1>로그인이 완료되지 않았습니다.</h1><p>앱으로 돌아가 다시 로그인하세요.</p>',400,{'Set-Cookie':clearCookie});
    let stage = 'TOKEN_REQUEST';
    try {
      const response = await fetch(tokenUrl,{
        method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},redirect:'manual',signal:AbortSignal.timeout(20_000),
        body:new URLSearchParams({grant_type:'authorization_code',client_id:env.CLIENT_ID,client_secret:env.CLIENT_SECRET,code:url.searchParams.get('code'),audience:audience,redirect_uri:callback}),
      });
      if (!response.ok) return html(`<h1>토큰 발급 실패</h1><p>진단 코드: TOKEN_HTTP_${response.status}</p><p>앱으로 돌아가 로그인을 다시 시작하세요.</p>`,502,{'Set-Cookie':clearCookie});
      stage = 'TOKEN_RESPONSE';
      const data=await response.json();
      if (typeof data.access_token !== 'string' || !data.access_token) throw new Error('No token');
      const bundle = JSON.stringify({access_token:data.access_token,refresh_token:data.refresh_token || '',expires_at:Date.now() + Number(data.expires_in || 0)*1000,client_id:env.CLIENT_ID});
      return html(`<h1>Tesla 로그인 완료</h1><h2>자동 갱신용 토큰 묶음(JSON)</h2><p>이 JSON 전체를 Android TeslaAuth의 Access Token 칸에 붙여 넣고 VIN과 함께 Save하세요.</p><textarea readonly rows="14" cols="36" aria-label="자동 갱신용 토큰 묶음">${escape(bundle)}</textarea>${data.refresh_token ? '' : '<p>Refresh Token이 발급되지 않았습니다. 자동 갱신 권한에 동의해 다시 로그인하세요.</p>'}<h2>Access Token만 복사 (자동 갱신 안 됨)</h2><p>아래 사용자 Access Token을 복사해 Android 앱의 토큰 입력란에 붙여 넣으세요. 이 화면을 닫으면 서버에서 토큰을 다시 볼 수 없습니다.</p><textarea readonly rows="12" cols="36" aria-label="사용자 Access Token">${escape(data.access_token)}</textarea><p>유효기간: ${escape(data.expires_in ?? 'Tesla 정책에 따름')}초. 토큰 묶음을 저장하면 앱이 만료 시 자동 갱신합니다.</p><p>차량 VIN은 Tesla 앱의 차량 정보에서 확인할 수 있습니다.</p>`,200,{'Set-Cookie':clearCookie});
    } catch (error) {
      // Only fixed categories leave the server; never expose exception messages or token responses.
      const message = String(error?.message || '');
      const reason = /redirect/i.test(message) ? 'REDIRECT_MODE' : /timeout|abort/i.test(message) ? 'TIMEOUT' : /json/i.test(message) ? 'INVALID_JSON' : /No token/.test(message) ? 'MISSING_TOKEN' : error?.name === 'TypeError' ? 'TYPE_ERROR' : 'NETWORK';
      return html(`<h1>로그인 서버 연결 실패</h1><p>진단 코드: ${stage}_${reason}</p><p>앱에서 로그인을 다시 시작하세요.</p>`,502,{'Set-Cookie':clearCookie});
    }
  },
};
