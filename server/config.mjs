// Deployment variables are public except CLIENT_SECRET. Never derive origin from a request.
export const defaults = Object.freeze({
  AUDIENCE: 'https://fleet-api.prd.na.vn.cloud.tesla.com',
  AUTHORIZE_URL: 'https://auth.tesla.com/oauth2/v3/authorize',
  TOKEN_URL: 'https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token',
  SCOPES: 'openid offline_access vehicle_device_data vehicle_location',
});
function httpsUrl(value, originOnly = false) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      (originOnly && url.pathname !== '/')) throw new Error('Invalid HTTPS configuration');
  return originOnly ? url.origin : url.href;
}
export function readConfig(env) {
  const origin = httpsUrl(env.ORIGIN, true);
  const publicKey = (env.PUBLIC_KEY || '').replace(/\\n/g, '\n').trim();
  // Reject private-key material and malformed PEM before it can be served publicly.
  if (publicKey && !/^-----BEGIN PUBLIC KEY-----\r?\n[A-Za-z0-9+/=\r\n]+\r?\n-----END PUBLIC KEY-----$/.test(publicKey)) {
    throw new Error('Invalid public key');
  }
  const scopes = env.SCOPES ?? defaults.SCOPES;
  if (!defaults.SCOPES.split(' ').every(scope => scopes.split(/\s+/).includes(scope))) {
    throw new Error('Location and refresh scopes are required');
  }
  return {
    origin, callback: origin + '/auth/tesla/callback', publicKey,
    audience: httpsUrl(env.AUDIENCE ?? defaults.AUDIENCE, true),
    authorizeUrl: httpsUrl(env.AUTHORIZE_URL ?? defaults.AUTHORIZE_URL),
    tokenUrl: httpsUrl(env.TOKEN_URL ?? defaults.TOKEN_URL), scopes,
  };
}
