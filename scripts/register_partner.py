#!/usr/bin/env python3
"""Explicit, one-time partner registration. Never prints or persists credentials."""
import argparse
import getpass
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'docs/examples'))
from fleet_location import endpoint_config, request_json, OPENER


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', required=True, help='Your public HTTPS Worker origin')
    parser.add_argument('--client-id', required=True)
    parser.add_argument('--register', action='store_true', help='Send the partner registration POST')
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.origin)
    if (origin.scheme != 'https' or not origin.hostname or origin.username or origin.password
            or origin.port or origin.path not in ('', '/') or origin.query or origin.fragment):
        parser.error('--origin must be a public HTTPS origin without credentials, port or path')
    base, token_url = endpoint_config()
    key_url = args.origin.rstrip('/') + '/.well-known/appspecific/com.tesla.3p.public-key.pem'
    with OPENER.open(key_url, timeout=20) as response:
        key = response.read(8193)
        if len(key) > 8192 or not key.startswith(b'-----BEGIN PUBLIC KEY-----') or b'PRIVATE KEY' in key:
            raise ValueError('Public key endpoint is not ready')
    print('Public key endpoint ready. Region endpoint:', base)
    if not args.register:
        print('No registration sent. Add --register when your domain and application are ready.')
        return
    secret = getpass.getpass('Tesla Client Secret (hidden, not saved): ')
    if not secret:
        raise ValueError('Missing secret')
    partner = request_json(token_url, data={'grant_type': 'client_credentials',
        'client_id': args.client_id, 'client_secret': secret, 'audience': base})
    token = partner['access_token']
    request = urllib.request.Request(base + '/api/1/partner_accounts',
        data=json.dumps({'domain': origin.hostname}).encode(),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
    with OPENER.open(request, timeout=20) as response:
        print('Partner registration HTTP', response.status)
    # Verify that the registration can be queried without printing the response.
    request_json(base + '/api/1/partner_accounts/public_key?' +
                          urllib.parse.urlencode({'domain': origin.hostname}), token=token)
    print('Partner public-key lookup succeeded; inspect Tesla portal if registration remains unavailable.')


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f'HTTP {error.code}: check region, application, domain and credentials.', file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Registration failed: check public key, configuration and network. No credentials printed.', file=sys.stderr)
        sys.exit(1)
