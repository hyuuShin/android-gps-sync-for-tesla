import {test} from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {generateKeyPairSync} from 'node:crypto';
const origin='https://login.example.com';
const publicKey = generateKeyPairSync('ec', {namedCurve:'prime256v1'}).publicKey.export({type:'spki',format:'pem'});
const env={ORIGIN:origin,PUBLIC_KEY:publicKey,CLIENT_ID:'test-client',CLIENT_SECRET:'test-secret-not-production'};
test('public key and unknown paths',async()=>{
 const key=await worker.fetch(new Request(origin+'/.well-known/appspecific/com.tesla.3p.public-key.pem'),env);
 assert.equal(key.status,200); assert.match(await key.text(),/BEGIN PUBLIC KEY/);
 assert.equal((await worker.fetch(new Request(origin+'/missing'),env)).status,404);
});
test('OAuth requires matching, unexpired signed cookie; token exchange escapes output',async()=>{
 const start=await worker.fetch(new Request(origin+'/auth/tesla/start'),env);
 assert.equal(start.status,302);
 assert.ok(new URL(start.headers.get('Location')).searchParams.get('scope').split(' ').includes('offline_access'));
 const state=new URL(start.headers.get('Location')).searchParams.get('state');
 const cookie=start.headers.get('Set-Cookie').split(';')[0];
 assert.match(start.headers.get('Set-Cookie'),/Secure; HttpOnly; SameSite=Lax/);
 const callback=origin+'/auth/tesla/callback?'+new URLSearchParams({state,code:'test-code'});
 assert.equal((await worker.fetch(new Request(callback),env)).status,400);
 assert.equal((await worker.fetch(new Request(callback,{headers:{Cookie:cookie+'bad'}}),env)).status,400);
 const oldFetch=globalThis.fetch;
 globalThis.fetch=async(url,options)=>{
   assert.equal(url,'https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token');
   assert.equal(options.body.get('code'),'test-code');
   return Response.json({access_token:'token</textarea><script>bad</script>',refresh_token:'test-refresh',expires_in:300});
 };
 try {
   const result=await worker.fetch(new Request(callback,{headers:{Cookie:cookie}}),env);
   assert.equal(result.status,200); assert.equal(result.headers.get('Cache-Control'),'no-store');
   assert.match(result.headers.get('Set-Cookie'),/Max-Age=0/);
   const body=await result.text(); assert.ok(!body.includes('<script>')); assert.ok(body.includes('&lt;/textarea&gt;'));
   assert.ok(body.includes('test-refresh')); assert.ok(body.includes('expires_at')); assert.ok(body.includes('test-client'));
 } finally {globalThis.fetch=oldFetch;}
});
test('missing configuration and invalid host do not redirect',async()=>{
 assert.equal((await worker.fetch(new Request(origin+'/auth/tesla/start'))).status,503);
 assert.equal((await worker.fetch(new Request('https://evil.invalid/auth/tesla/start'),env)).status,400);
});
test('token requests use supported manual redirects and reject redirects without exposing upstream content',async()=>{
 const start=await worker.fetch(new Request(origin+'/auth/tesla/start'),env);
 const state=new URL(start.headers.get('Location')).searchParams.get('state');
 const cookie=start.headers.get('Set-Cookie').split(';')[0];
 const callback=new Request(origin+'/auth/tesla/callback?'+new URLSearchParams({state,code:'test-code'}),{headers:{Cookie:cookie}});
 const oldFetch=globalThis.fetch;
 let calls=0;
 globalThis.fetch=async(url,options)=>{
   calls++;
   assert.equal(options.redirect,'manual');
   return new Response('sensitive-upstream-body',{status:302,headers:{Location:'https://untrusted.invalid/'}});
 };
 try {
   const result=await worker.fetch(callback,env);
   assert.equal(calls,1); assert.equal(result.status,502);
   const body=await result.text(); assert.ok(body.includes('TOKEN_HTTP_302'));
   assert.ok(!body.includes('sensitive-upstream-body')); assert.ok(!body.includes('untrusted.invalid'));
 } finally {globalThis.fetch=oldFetch;}
});
test('network exceptions expose only a fixed diagnostic category',async()=>{
 const start=await worker.fetch(new Request(origin+'/auth/tesla/start'),env);
 const state=new URL(start.headers.get('Location')).searchParams.get('state');
 const cookie=start.headers.get('Set-Cookie').split(';')[0];
 const oldFetch=globalThis.fetch;
 globalThis.fetch=async()=>{throw new TypeError('sensitive-secret-value');};
 try {
   const result=await worker.fetch(new Request(origin+'/auth/tesla/callback?'+new URLSearchParams({state,code:'test-code'}),{headers:{Cookie:cookie}}),env);
   const body=await result.text(); assert.ok(body.includes('TOKEN_REQUEST_TYPE_ERROR'));
   assert.ok(!body.includes('sensitive-secret-value')); assert.equal(result.headers.get('Cache-Control'),'no-store');
 } finally {globalThis.fetch=oldFetch;}
});

test('configuration is isolated per deployment and is used in authorization and token exchange', async () => {
 const custom = {...env, ORIGIN:'https://other.example.com', CLIENT_ID:'other-client',
   AUDIENCE:'https://fleet-api.prd.eu.vn.cloud.tesla.com', AUTHORIZE_URL:'https://auth.example.com/authorize',
   TOKEN_URL:'https://auth.example.com/token', SCOPES:env.SCOPES || 'openid offline_access vehicle_device_data vehicle_location user_data'};
 const start = await worker.fetch(new Request(custom.ORIGIN+'/auth/tesla/start'),custom);
 const auth = new URL(start.headers.get('Location'));
 assert.equal(auth.origin,'https://auth.example.com');
 assert.equal(auth.searchParams.get('redirect_uri'),custom.ORIGIN+'/auth/tesla/callback');
 assert.equal(auth.searchParams.get('client_id'),custom.CLIENT_ID);
 assert.ok(auth.searchParams.get('scope').includes('user_data'));
 const oldFetch = globalThis.fetch;
 globalThis.fetch = async (url, options) => {
   assert.equal(url,custom.TOKEN_URL);
   assert.equal(options.body.get('audience'),custom.AUDIENCE);
   assert.equal(options.body.get('redirect_uri'),custom.ORIGIN+'/auth/tesla/callback');
   return Response.json({access_token:'custom-access',refresh_token:'custom-refresh',expires_in:300});
 };
 try {
   const callback = new Request(custom.ORIGIN+'/auth/tesla/callback?'+new URLSearchParams({code:'test',state:auth.searchParams.get('state')}),
     {headers:{Cookie:start.headers.get('Set-Cookie').split(';')[0]}});
   assert.equal((await worker.fetch(callback,custom)).status,200);
 } finally { globalThis.fetch = oldFetch; }
});
test('missing key and unsafe configuration fail closed without exposing values', async () => {
 for (const overrides of [{ORIGIN:''},{ORIGIN:'http://example.com'},{TOKEN_URL:'https://user:secret@example.com/token'},
   {PUBLIC_KEY:'-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----'},{SCOPES:'openid'}]) {
   const response = await worker.fetch(new Request(origin+'/auth/tesla/start'),{...env,...overrides});
   assert.equal(response.status,503);
   assert.ok(!(await response.text()).includes('secret'));
 }
 assert.equal((await worker.fetch(new Request(origin+'/.well-known/appspecific/com.tesla.3p.public-key.pem'),{...env,PUBLIC_KEY:''})).status,503);
 const status = await worker.fetch(new Request(origin+'/'),{...env,PUBLIC_KEY:'',CLIENT_SECRET:''});
 assert.deepEqual(await status.json(),{service:'Tesla Fleet API',publicKeyConfigured:false,oauthConfigured:false});
 assert.equal((await worker.fetch(new Request('https://evil.invalid/.well-known/appspecific/com.tesla.3p.public-key.pem'),env)).status,400);
});
