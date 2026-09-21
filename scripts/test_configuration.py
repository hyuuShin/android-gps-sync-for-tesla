import contextlib
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('register_partner', ROOT / 'scripts/register_partner.py')
register = importlib.util.module_from_spec(spec)
spec.loader.exec_module(register)
from fleet_location import endpoint_config


class ConfigurationTest(unittest.TestCase):
    def test_defaults_and_eu_environment_override(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertIn('.na.', endpoint_config()[0])
        with patch.dict(os.environ, {'TESLA_DEFAULT_REGION': 'EU', 'TESLA_TOKEN_URL': 'https://auth.example.com/token'}, clear=True):
            self.assertEqual(endpoint_config(), ('https://fleet-api.prd.eu.vn.cloud.tesla.com', 'https://auth.example.com/token'))

    def test_unsafe_or_invalid_endpoints_are_rejected(self):
        for values in ({'TESLA_DEFAULT_REGION': 'CN'}, {'TESLA_TOKEN_URL': 'http://example.com'},
                       {'TESLA_TOKEN_URL': 'https://user:secret@example.com/token'},
                       {'TESLA_FLEET_NA_URL': 'https://example.com/api'}):
            with self.subTest(values=values), patch.dict(os.environ, values, clear=True):
                with self.assertRaises(ValueError):
                    endpoint_config()

    def test_partner_registration_is_explicit_and_keeps_credentials_out_of_output(self):
        class Response(io.BytesIO):
            status = 200
        requests = []
        def open_request(req, **kwargs):
            if isinstance(req, str):
                return Response(b'-----BEGIN PUBLIC KEY-----\nTEST\n-----END PUBLIC KEY-----')
            requests.append(req)
            return Response(b'{}')
        with patch.dict(os.environ, {}, clear=True), patch.object(register.OPENER, 'open', side_effect=open_request), \
             patch.object(register.getpass, 'getpass', return_value='hidden-secret') as secret, \
             patch.object(register, 'request_json', side_effect=[{'access_token':'hidden-partner-token'}, {}]) as request_json:
            output = io.StringIO()
            args = ['register_partner.py', '--origin', 'https://login.example.com', '--client-id', 'test-client']
            with patch('sys.argv', args), contextlib.redirect_stdout(output):
                register.main()
            secret.assert_not_called()
            request_json.assert_not_called()
            self.assertEqual(requests, [])
            with patch('sys.argv', args + ['--register']), contextlib.redirect_stdout(output):
                register.main()
            self.assertEqual(len(requests), 1)
            self.assertEqual(requests[0].data, b'{"domain": "login.example.com"}')
            self.assertEqual(requests[0].get_header('Authorization'), 'Bearer hidden-partner-token')
            self.assertEqual(request_json.call_args_list[0].kwargs['data']['client_secret'], 'hidden-secret')
            self.assertNotIn('hidden-secret', output.getvalue())
            self.assertNotIn('hidden-partner-token', output.getvalue())


if __name__ == '__main__':
    unittest.main()
