"""
Build the chess-guru relay APK with plain Android SDK tools (no Gradle, no downloads):
aapt2 (resources + manifest) -> javac -> d8 (dex) -> zip -> zipalign -> apksigner.

Usage:  python -I relay/android/build.py [--sdk D:/Android/sdk]
Output: relay/android/out/chess-guru-relay.apk
A signing key is created once in relay/android/.keystore/ (gitignored); keep it to install updates over old versions.
"""
import argparse
import glob
import os
import secrets
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
BUILD = os.path.join(OUT, "build")
KS_DIR = os.path.join(HERE, ".keystore")
KS = os.path.join(KS_DIR, "relay.jks")
KS_PASS_FILE = os.path.join(KS_DIR, "password.txt")
MIN_SDK, TARGET_SDK = "26", "36"
VERSION_CODE, VERSION_NAME = "1", "1.0.0"


def run(args):
    print(">", " ".join(os.path.basename(a) if i == 0 else a for i, a in enumerate(args)))
    r = subprocess.run(args, shell=False)
    if r.returncode != 0:
        sys.exit(f"step failed: {os.path.basename(args[0])}")


def tool(sdk, bt, name):
    for cand in (name + ".exe", name + ".bat", name):
        p = os.path.join(sdk, "build-tools", bt, cand)
        if os.path.exists(p):
            return p
    sys.exit(f"{name} not found in build-tools {bt}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "D:/Android/sdk")
    a = ap.parse_args()
    sdk = a.sdk
    bts = sorted(os.listdir(os.path.join(sdk, "build-tools")), key=lambda v: [int(x) if x.isdigit() else 0 for x in v.split(".")])
    bt = bts[-1]
    platforms = sorted(os.listdir(os.path.join(sdk, "platforms")))
    android_jar = os.path.join(sdk, "platforms", platforms[-1], "android.jar")
    aapt2, d8, zipalign, apksigner = (tool(sdk, bt, n) for n in ("aapt2", "d8", "zipalign", "apksigner"))
    print(f"build-tools {bt}, platform {platforms[-1]}")

    shutil.rmtree(BUILD, ignore_errors=True)
    for d in ("res-flat", "gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)

    # 1. resources + manifest
    res_files = [p for p in glob.glob(os.path.join(HERE, "res", "**", "*"), recursive=True) if os.path.isfile(p)]
    for f in res_files:
        run([aapt2, "compile", f, "-o", os.path.join(BUILD, "res-flat")])
    flats = glob.glob(os.path.join(BUILD, "res-flat", "*.flat"))
    base_apk = os.path.join(BUILD, "base.apk")
    run([aapt2, "link", "-o", base_apk, "-I", android_jar, "--manifest", os.path.join(HERE, "AndroidManifest.xml"),
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
         "--java", os.path.join(BUILD, "gen"), *flats])

    # 2. compile java
    sources = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True) + glob.glob(os.path.join(BUILD, "gen", "**", "*.java"), recursive=True)
    run(["javac", "-encoding", "UTF-8", "--release", "11", "-classpath", android_jar,
         "-d", os.path.join(BUILD, "classes"), *sources])

    # 3. dex
    classes = glob.glob(os.path.join(BUILD, "classes", "**", "*.class"), recursive=True)
    run([d8, "--release", "--min-api", MIN_SDK, "--lib", android_jar, "--output", os.path.join(BUILD, "dex"), *classes])

    # 4. add classes.dex
    unsigned = os.path.join(BUILD, "unsigned.apk")
    shutil.copy(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex")

    # 5. align
    aligned = os.path.join(BUILD, "aligned.apk")
    run([zipalign, "-p", "-f", "4", unsigned, aligned])

    # 6. sign (create a local key once)
    if not os.path.exists(KS):
        os.makedirs(KS_DIR, exist_ok=True)
        pw = secrets.token_urlsafe(24)
        with open(KS_PASS_FILE, "w") as f:
            f.write(pw)
        run(["keytool", "-genkeypair", "-keystore", KS, "-storepass", pw, "-keypass", pw, "-alias", "relay",
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=chess-guru relay"])
    pw = open(KS_PASS_FILE).read().strip()
    final = os.path.join(OUT, "chess-guru-relay.apk")
    run([apksigner, "sign", "--ks", KS, "--ks-pass", "file:" + KS_PASS_FILE, "--out", final, aligned])
    run([apksigner, "verify", "--print-certs", final])
    print("\nAPK:", final, f"({os.path.getsize(final) // 1024} KB)")
    del pw


if __name__ == "__main__":
    main()
