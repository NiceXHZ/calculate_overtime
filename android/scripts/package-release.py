#!/usr/bin/env python3
"""Verify signed standalone APKs, then prepare versioned release assets (no upload)."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
from zipfile import ZipFile


def run(*args):
    result = subprocess.run(args, check=True, capture_output=True, text=True)
    return result.stdout


def elf_alignments(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a 64-bit little-endian ELF library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    return [struct.unpack_from("<Q", data, offset + index * size + 48)[0]
            for index in range(count)
            if struct.unpack_from("<I", data, offset + index * size)[0] == 1]


def main():
    project = Path(__file__).resolve().parents[1]
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_path and (project / "local.properties").is_file():
        match = re.search(r"^sdk.dir=(.+)$", (project / "local.properties").read_text(), re.M)
        if match:
            sdk_path = match[1].replace(r"\:", ":").replace(r"\\", "\\")
    sdk = Path(sdk_path) if sdk_path else Path.home() / "Android/Sdk"
    candidates = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file() and (p / "zipalign").is_file()]
    tools = max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
    output = project / "app/build/outputs/apk/release"
    metadata = json.loads((output / "output-metadata.json").read_text())
    elements = metadata["elements"]
    version = elements[0]["versionName"]
    destination = project / "app/build/release-assets" / f"v{version}"
    apks, certificate = [], None
    for element in elements:
        source = (output / element["outputFile"]).resolve()
        if not source.is_relative_to(output.resolve()):
            raise ValueError("APK path escaped the build output")
        abi = next((f["value"] for f in element["filters"] if f["filterType"] == "ABI"), "universal")
        certs = run(str(tools / "apksigner"), "verify", "--print-certs", str(source))
        fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", certs)
        if not fingerprint:
            raise ValueError(f"Missing signing certificate: {source.name}")
        fingerprint = fingerprint[1]
        if certificate is not None and certificate != fingerprint:
            raise ValueError("APK signing identities differ")
        certificate = fingerprint
        run(str(tools / "zipalign"), "-c", "-P", "16", "4", str(source))
        with ZipFile(source) as archive:
            libraries = [p for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")]
            actual = {p.split("/")[1] for p in libraries}
            expected = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"} if abi == "universal" else {abi}
            if actual != expected:
                raise ValueError(f"Wrong native platforms in {source.name}: {actual}")
            for name in libraries:
                if name.split("/")[1] in {"arm64-v8a", "x86_64"}:
                    alignments = elf_alignments(archive.read(name))
                    if not alignments or any(value < 16384 for value in alignments):
                        raise ValueError(f"ELF library is not 16 KB aligned: {name}")
            if not any(name.endswith(".dex") for name in archive.namelist()):
                raise ValueError("APK has no executable DEX")
            native_bytes = sum(archive.getinfo(name).file_size for name in libraries)
        badging = run(str(tools / "aapt2"), "dump", "badging", str(source))
        if "application-debuggable" in badging:
            raise ValueError("Refusing to package a debuggable APK")
        for field, expected_value in (("name", metadata["applicationId"]), ("versionName", version), ("versionCode", str(element["versionCode"]))):
            if f"{field}='{expected_value}'" not in badging.splitlines()[0]:
                raise ValueError(f"Unexpected APK package metadata: {field}")
        apks.append({"file": f"overtime-checkin-{version}-{abi}.apk", "abi": abi,
                     "bytes": source.stat().st_size, "nativeBytes": native_bytes,
                     "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                     "versionCode": element["versionCode"], "libraries": libraries,
                     "source": source})
    destination.mkdir(parents=True, exist_ok=True)
    current_files = {apk["file"] for apk in apks}
    for old in destination.glob(f"overtime-checkin-{version}-*.apk"):
        if old.name not in current_files:
            old.unlink()
    for apk in apks:
        shutil.copyfile(apk.pop("source"), destination / apk["file"])
    (destination / "SHA256SUMS.txt").write_text("".join(f'{a["sha256"]}  {a["file"]}\n' for a in apks))
    report = {"version": version, "applicationId": metadata["applicationId"],
              "certificateSha256": certificate, "apks": apks}
    (destination / "PACKAGE_REPORT.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(destination)
    for apk in apks:
        print(f'{apk["file"]}: {apk["bytes"]:,} bytes; ABI={apk["abi"]}; signature + ZIP/ELF alignment verified')


if __name__ == "__main__":
    main()
