#!/usr/bin/env python3
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
failures: list[str] = []
checks: list[str] = []

def require(condition: bool, message: str) -> None:
    (checks if condition else failures).append(message)

def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

# XML/plist syntax.
try:
    ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    checks.append("AndroidManifest.xml parses")
except Exception as e:
    failures.append(f"AndroidManifest.xml parse: {e}")

try:
    with (ROOT / "ios/TailTuneRemote/Info.plist").open("rb") as fh:
        plistlib.load(fh)
    checks.append("iOS Info.plist parses")
except Exception as e:
    failures.append(f"Info.plist parse: {e}")

manifest = text("app/src/main/AndroidManifest.xml")
for permission in [
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
]:
    require(permission in manifest, f"manifest permission {permission}")
require('android:name=".RemoteServerService"' in manifest, "RemoteServerService declared")
require('android:foregroundServiceType="connectedDevice"' in manifest, "remote FGS uses connectedDevice")
require('android:name=".PlaybackService"' in manifest, "PlaybackService declared")
require('android:foregroundServiceType="mediaPlayback"' in manifest, "playback FGS uses mediaPlayback")
# Media3 documents an exported MediaSessionService intent-filter so system and
# external media controllers can discover the session.
playback_manifest = manifest.split('android:name=".PlaybackService"', 1)[1].split('</service>', 1)[0]
require('android:exported="true"' in playback_manifest, "MediaSessionService is exported for controller discovery")

remote = text("app/src/main/java/dev/tailtune/remote/RemoteServerService.kt")
playback = text("app/src/main/java/dev/tailtune/remote/PlaybackService.kt")
server = text("app/src/main/java/dev/tailtune/remote/TailTuneServer.kt")
offline = text("app/src/main/java/dev/tailtune/remote/OfflineStore.kt")
prefs = text("app/src/main/java/dev/tailtune/remote/RemoteServicePreferences.kt")
web = text("app/src/main/assets/web/app.js")
socket = text("ios/TailTuneRemote/Sources/TailTuneSocket.swift")
project = text("ios/project.yml")

require("override fun onBind(intent: Intent?): IBinder? = null" in remote, "RemoteServerService implements Service.onBind")
require("TailTuneServer(" in remote, "remote service owns TailTuneServer construction")
require("TailTuneServer(" not in playback, "playback service does not construct HTTP server")
require("startWebServerSafely" not in playback, "old embedded web-server startup removed")
require("START_STICKY" in remote, "remote service requests sticky restart")
require("serverWatchdogRunnable" in remote and ".isAlive" in remote, "remote listener watchdog present")
require("serverGeneration" in remote, "stale server-start generation guard present")
require("bindRequested" in remote and "releasePlaybackBinding" in remote, "binding lifecycle is explicit")
require("PLAYBACK_READY_POLL_MS" in remote, "playback initialization is polled without exponential delay")
require("override fun onTaskRemoved(rootIntent: Intent?)" in playback and "RemoteServicePreferences.isEnabled(this)" in playback, "custom-looper playback has explicit task-removal policy")
require(".commit()" not in prefs, "remote enabled flag does not synchronously commit on UI thread")
require("setWriteAheadLoggingEnabled(true)" in offline, "SQLite WAL enabled")
require("ArrayBlockingQueue" in server, "WebSocket request queue is bounded")
require("MAX_PENDING_SOCKET_REQUESTS" in server, "WebSocket request queue has explicit cap")
require("x-tailtune-token" in server and "isAuthorized" in server and "MessageDigest.isEqual" in server, "HTTP/WebSocket token authentication present")
require("MAX_REQUEST_BODY_BYTES" in server, "HTTP body limit present")
require("MAX_WEBSOCKET_REQUEST_BYTES" in server, "WebSocket message limit present")
require('operation: "ping"' in web, "browser heartbeat uses protocol JSON ping")
require("requestTimeoutSeconds" in socket, "native iOS requests have timeout")
require("scheduleReconnectLocked" in socket and "func resume()" in socket, "native iOS reconnect/resume present")
require("authorizationFailed" in socket, "native iOS prevents bad-token reconnect loop")
require("NSBonjourServices" in project and "_tailtune._tcp" in project, "iOS Bonjour entitlement description configured")

# Basic secret scan: permit placeholders/documentation, reject obvious literals in source.
secret_patterns = [
    re.compile(r'password\s*=\s*"(?!")[^"\n]{4,}"', re.I),
    re.compile(r'X-TailTune-Token["\']?\s*[:=]\s*["\'][A-Za-z0-9_-]{32}["\']'),
]
for path in list((ROOT / "app/src").rglob("*")) + list((ROOT / "ios").rglob("*.swift")):
    if not path.is_file():
        continue
    body = path.read_text(encoding="utf-8", errors="ignore")
    for pattern in secret_patterns:
        if pattern.search(body):
            failures.append(f"possible embedded secret: {path.relative_to(ROOT)}")

# JavaScript syntax when Node is available. CI/build environments without Node
# still get the structural checks above.
if shutil.which("node"):
    node = subprocess.run(
        ["node", "--check", str(ROOT / "app/src/main/assets/web/app.js")],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    require(node.returncode == 0, "web JavaScript parses with node --check")
    if node.returncode:
        failures.append(node.stderr.strip())
else:
    checks.append("web JavaScript parse skipped (node unavailable)")

# Swift parser check works on Linux when a Swift toolchain is installed. The
# dedicated macOS CI workflow performs the authoritative iOS build/test.
if shutil.which("swiftc"):
    swift_files = [str(p) for p in (ROOT / "ios/TailTuneRemote/Sources").glob("*.swift")]
    swift = subprocess.run(
        ["swiftc", "-parse", *swift_files], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    require(swift.returncode == 0, "all native iOS Swift sources parse")
    if swift.returncode:
        failures.append(swift.stderr.strip())
else:
    checks.append("native iOS Swift parse skipped (swiftc unavailable)")

print(f"STATIC_AUDIT checks={len(checks)} failures={len(failures)}")
for item in checks:
    print(f"  PASS {item}")
for item in failures:
    print(f"  FAIL {item}")
sys.exit(1 if failures else 0)
