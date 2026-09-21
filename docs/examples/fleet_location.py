#!/usr/bin/env python3
"""Personal test example; macOS/Linux; token file is plaintext, owner-only."""
import configparser
import fcntl
import json
import math
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]

def endpoint_config():
    config = configparser.ConfigParser(interpolation=None)
    config.optionxform = str
    config.read_string("[tesla]\n" + (ROOT / "config/tesla.defaults.properties").read_text())
    def value(key):
        return os.environ.get(key, config["tesla"].get(key, ""))
    region = value("TESLA_DEFAULT_REGION")
    if region not in ("NA", "EU"):
        raise ValueError("TESLA_DEFAULT_REGION must be NA or EU")
    base, auth = value("TESLA_FLEET_" + region + "_URL"), value("TESLA_TOKEN_URL")
    for url in (base, auth):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Invalid HTTPS endpoint")
    if urllib.parse.urlsplit(base).path:
        raise ValueError("Fleet endpoint must be an origin without trailing slash")
    return base, auth

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

OPENER = urllib.request.build_opener(NoRedirect())

def request_json(url, data=None, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = urllib.parse.urlencode(data).encode()
    with OPENER.open(urllib.request.Request(url, data=data, headers=headers), timeout=20) as response:
        payload = response.read(1_048_577)
        if len(payload) > 1_048_576:
            raise ValueError("Response too large")
        return json.loads(payload)

def save_atomic(path, state):
    fd, temp = tempfile.mkstemp(prefix=".token-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(state, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)

def refresh(path, state):
    if not state.get("refresh_token"):
        raise ValueError("Refresh Token missing: log in again")
    reply = request_json(endpoint_config()[1], data={
        "grant_type": "refresh_token",
        "client_id": state["client_id"],
        "refresh_token": state["refresh_token"],
    })
    access, rotation = reply.get("access_token"), reply.get("refresh_token")
    seconds = reply.get("expires_in")
    if not all(isinstance(v, str) and v and not any(c.isspace() for c in v) for v in (access, rotation)):
        raise ValueError("Invalid token response")
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not 0 < seconds <= 31_536_000:
        raise ValueError("Invalid token lifetime")
    updated = dict(state, access_token=access, refresh_token=rotation,
                   expires_at=int(time.time() * 1000 + seconds * 1000))
    save_atomic(path, updated)
    state.update(updated)

def location(path, state):
    vin = state["vin"].strip().upper()
    if not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", vin):
        raise ValueError("VIN must have 17 valid characters")
    token = state.get("access_token")
    if not isinstance(token, str) or not token or any(c.isspace() for c in token):
        raise ValueError("Invalid access token")
    expiry = int(state.get("expires_at", 0))
    if expiry > 0 and expiry <= time.time() * 1000 + 60_000:
        refresh(path, state)
    url = endpoint_config()[0] + "/api/1/vehicles/" + vin + "/vehicle_data?endpoints=location_data"
    try:
        result = request_json(url, token=state["access_token"])
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
        refresh(path, state)
        result = request_json(url, token=state["access_token"])  # One retry only.
    if result.get("error"):
        raise ValueError("Tesla returned an API error")
    fix = result["response"]["drive_state"]
    lat, lon, stamp = (fix[k] for k in ("latitude", "longitude", "timestamp"))
    if not all(type(v) in (int, float) and math.isfinite(v) for v in (lat, lon, stamp)):
        raise ValueError("Missing or non-numeric location")
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise ValueError("Coordinates out of range")
    now = time.time() * 1000
    if int(stamp) != stamp or not now - 300_000 <= stamp <= now + 60_000:
        raise ValueError("Vehicle fix is stale or has an invalid timestamp")
    return {"latitude": lat, "longitude": lon, "vehicle_timestamp_ms": stamp}

def main():
    if len(sys.argv) != 2:
        raise ValueError("Usage: fleet_location.py PATH_TO_TOKEN_JSON")
    path = Path(sys.argv[1]).expanduser().resolve(strict=True)
    # This lock coordinates this script only, not an Android app or other clients.
    with open(str(path) + ".lock", "a", encoding="utf-8") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        with path.open(encoding="utf-8") as stream:
            info = os.fstat(stream.fileno())
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
                raise ValueError("Token file must be owner-only: chmod 600")
            state = json.load(stream)
        print(json.dumps(location(path, state)))

if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"HTTP {error.code}; check authorization, vehicle state and billing.", file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Do not print exception payloads that might contain secret JSON values.
        print("Failed: check token file permissions/fields, network and token validity.", file=sys.stderr)
        sys.exit(1)
