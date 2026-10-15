#!/usr/bin/env python3
"""Create a private, reusable local release identity without printing passwords."""
import os
from pathlib import Path
import secrets
import subprocess

config = Path.home() / ".config/calculate-overtime/release-signing.properties"
key = Path.home() / ".local/share/calculate-overtime/signing/release.jks"
if config.exists():
    print(f"Using existing signing configuration: {config}")
    raise SystemExit(0)
if key.exists():
    raise SystemExit(f"Key already exists at {key}; restore its configuration instead of replacing it.")
for directory in (config.parent, key.parent):
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
password = secrets.token_urlsafe(36)
environment = os.environ.copy()
environment["OVERTIME_KEY_PASSWORD"] = password
old_mask = os.umask(0o077)
try:
    subprocess.run([
        "keytool", "-genkeypair", "-noprompt", "-keystore", str(key),
        "-storetype", "PKCS12", "-alias", "overtime-release", "-keyalg", "RSA", "-keysize", "3072",
        "-validity", "10000", "-dname", "CN=Overtime App, OU=Personal Android Apps",
        "-storepass:env", "OVERTIME_KEY_PASSWORD", "-keypass:env", "OVERTIME_KEY_PASSWORD",
    ], env=environment, check=True, capture_output=True)
    with config.open("x") as output:
        output.write(f"storeFile={key}\nstorePassword={password}\nkeyAlias=overtime-release\nkeyPassword={password}\n")
    key.chmod(0o600)
    config.chmod(0o600)
finally:
    os.umask(old_mask)
print(f"Release key: {key}")
print(f"Signing configuration: {config}")
print("Keep private backups of both files; future releases must use the same key.")
