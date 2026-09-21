#!/usr/bin/env python3
"""Heuristic public-file review; reports only path/line/category, never matched values.

Scans working files and staged blobs, not Git history. Not a secret-scanner guarantee.
"""
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = {
    'private key material': re.compile(r'-----BEGIN (?:EC |RSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=]{20,}'),
    'embedded public key (owner-specific)': re.compile(r'-----BEGIN PUBLIC KEY-----(?:\s|\\n)+[A-Za-z0-9+/=]{40,}'),
    'JWT-like token': re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),
    'personal absolute path': re.compile(r'/(?:Users|home)/[A-Za-z0-9_.-]+/'),
    'owner-specific workers.dev host': re.compile(r'https://(?!YOUR-)[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev', re.I),
    'hardcoded UUID/app identifier': re.compile(r'\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b', re.I),
    'VIN-like identifier': re.compile(r'\b(?!A{17}\b)[A-HJ-NPR-Z0-9]{17}\b'),
}
PRIVATE_NAMES = {'tesla.properties', 'wrangler.toml', '.env', '.dev.vars', 'local.properties', 'key.properties'}
PRIVATE_SUFFIXES = {'.pem', '.key', '.keystore', '.jks', '.p12', '.pfx', '.apk', '.aab', '.log'}
PRIVATE_DIRS = {'artifacts', 'build', '.gradle', '.kotlin', '.wrangler', 'node_modules', 'captures'}


def git(args, prefix=None):
    return subprocess.check_output(['git', *(prefix or []), *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def scan_text(name, data):
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        return 0
    count = 0
    for category, pattern in RULES.items():
        for match in pattern.finditer(text):
            line = text.count('\n', 0, match.start()) + 1
            print(f'{name}:{line}: REVIEW {category}')
            count += 1
    return count


def main():
    try:
        has_git = Path(git(['rev-parse', '--show-toplevel']).decode().strip()) == ROOT
    except (subprocess.CalledProcessError, FileNotFoundError):
        has_git = False
    with tempfile.TemporaryDirectory(prefix='tesla-public-review-') as temp:
        prefix = []
        if not has_git:
            subprocess.run(['git', 'init', '--quiet', temp], check=True)
            prefix = ['--git-dir=' + temp + '/.git', '--work-tree=' + str(ROOT)]
            print('No repository history: scanning publishable working files using .gitignore.')
        names = sorted(set(git(['ls-files', '--cached', '--others', '--exclude-standard', '-z'], prefix).decode().split('\0')) - {''})
        findings = 0
        for name in names:
            path = Path(name)
            if path.name in PRIVATE_NAMES or path.suffix in PRIVATE_SUFFIXES or PRIVATE_DIRS.intersection(path.parts):
                print(f'{name}: REVIEW private/local file included in public candidates')
                findings += 1
            file = ROOT / path
            if file.is_symlink():
                print(f'{name}: REVIEW symlink (target not scanned)')
                findings += 1
            elif file.is_file():
                findings += scan_text(name, file.read_bytes())
            if has_git:
                try:
                    staged = git(['show', ':' + name])
                except subprocess.CalledProcessError:
                    continue
                if not file.is_file() or staged != file.read_bytes():
                    findings += scan_text(name + ' [staged]', staged)
        print(f'Reviewed {len(names)} public candidate files; {findings} findings. Git history and ignored local files were not scanned.')
        return 1 if findings else 0


if __name__ == '__main__':
    raise SystemExit(main())
