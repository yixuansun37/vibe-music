#!/usr/bin/env python3
"""Small real-service demo; Python standard library and Docker Compose only."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
STATE = Path(os.environ.get("DANCEFLOW_DEMO_STATE_DIR", HERE / ".local")).expanduser().resolve()
PROJECT = os.environ.get("DANCEFLOW_DEMO_PROJECT", "danceflow-portfolio-demo")
DATABASE = os.environ.get("DANCEFLOW_DEMO_DATABASE", "vibe_music")
MYSQL_PORT = os.environ.get("DANCEFLOW_DEMO_MYSQL_PORT", "3307")
REDIS_PORT = os.environ.get("DANCEFLOW_DEMO_REDIS_PORT", "6380")
BACKEND_PORT = os.environ.get("DANCEFLOW_DEMO_BACKEND_PORT", "8080")
BACKEND_URL = os.environ.get("DANCEFLOW_DEMO_BACKEND_URL", f"http://127.0.0.1:{BACKEND_PORT}").rstrip("/")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def private_write(path, text):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as handle:
        handle.write(text)
    path.chmod(0o600)


def credentials():
    path = STATE / "credentials.json"
    require(path.is_file(), "Run 'python3 dev/demo/demo.py init' first")
    return json.loads(path.read_text())


def initialize():
    require(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", DATABASE), "Invalid demo database name")
    for value in [MYSQL_PORT, REDIS_PORT, BACKEND_PORT]:
        require(value.isdigit() and 1 <= int(value) <= 65535, "Invalid port override")
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    STATE.chmod(0o700)
    path = STATE / "credentials.json"
    if not path.exists():
        suffix = secrets.token_hex(3)
        values = {
            "mysql_root_password": secrets.token_hex(24),
            "mysql_app_password": secrets.token_hex(24),
            "redis_password": secrets.token_hex(24),
            "test_username": f"danceflow_{suffix}",
            "test_email": f"danceflow-{suffix}@example.invalid",
            "test_password": "D7" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(14)),
            "unused_minio_access_key": secrets.token_hex(12),
            "unused_minio_secret_key": secrets.token_hex(24),
        }
        private_write(path, json.dumps(values, indent=2) + "\n")
    path.chmod(0o600)
    values = credentials()
    for name, key in [("mysql-root-password", "mysql_root_password"),
                      ("mysql-app-password", "mysql_app_password"),
                      ("redis-password", "redis_password")]:
        private_write(STATE / name, values[key])
    private_write(STATE / "mysql-client.cnf", "[client]\nuser=danceflow\npassword=" + values["mysql_app_password"] + "\n")
    private_write(STATE / "redis.conf", "bind 0.0.0.0\nprotected-mode yes\nappendonly no\nsave \"\"\nrequirepass " + values["redis_password"] + "\n")
    # This is outside src/main/resources: it is never packaged into the server JAR.
    properties = {
        "server.address": "127.0.0.1",
        "server.port": BACKEND_PORT,
        "spring.main.lazy-initialization": "true",
        "spring.ai.dashscope.enabled": "false",
        "vibe-music.ai.dashscope.api-key": "",
        "vibe-music.datasource.url": f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/{DATABASE}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "vibe-music.datasource.username": "danceflow",
        "vibe-music.datasource.password": values["mysql_app_password"],
        "vibe-music.redis.host": "127.0.0.1",
        "vibe-music.redis.port": REDIS_PORT,
        "vibe-music.redis.password": values["redis_password"],
        "vibe-music.minio.endpoint": "http://127.0.0.1:9000",
        "vibe-music.minio.accessKey": values["unused_minio_access_key"],
        "vibe-music.minio.secretKey": values["unused_minio_secret_key"],
        "vibe-music.minio.bucket": "vibe-music-data",
        "vibe-music.mail.host": "127.0.0.1",
        "vibe-music.mail.username": "",
        "vibe-music.mail.password": "",
        "spring.mail.test-connection": "false",
        "mybatis-plus.configuration.log-impl": "org.apache.ibatis.logging.nologging.NoLoggingImpl",
    }
    private_write(STATE / "application-dev.properties", "\n".join(f"{key}={value}" for key, value in properties.items()) + "\n")
    print("Initialized private demo configuration; existing credentials were retained. No secret values are printed.")


def compose(*arguments, input_text=None):
    environment = dict(os.environ)
    environment["DANCEFLOW_DEMO_STATE_DIR"] = str(STATE)
    environment["DANCEFLOW_DEMO_MYSQL_PORT"] = MYSQL_PORT
    environment["DANCEFLOW_DEMO_REDIS_PORT"] = REDIS_PORT
    environment["DANCEFLOW_DEMO_DATABASE"] = DATABASE
    environment["DANCEFLOW_DEMO_UID"] = str(getattr(os, "getuid", lambda: 1000)())
    environment["DANCEFLOW_DEMO_GID"] = str(getattr(os, "getgid", lambda: 1000)())
    command = ["docker", "compose", "--project-name", PROJECT, "--file", str(HERE / "compose.yaml"), *arguments]
    result = subprocess.run(command, input=input_text, text=True, capture_output=True, env=environment)
    require(result.returncode == 0, "Docker Compose operation failed; raw container output is intentionally not printed. Check Docker access, images, ports and local secret files.")
    return result.stdout.strip()


def sql(statement):
    return compose("exec", "-T", "mysql", "mysql", "--defaults-extra-file=/run/secrets/mysql-client-config",
                   "--default-character-set=utf8mb4", "--batch", "--raw", "-N", DATABASE, input_text=statement)


def sql_string(value):
    # Hex strings keep generated account data out of shell arguments and avoid SQL quoting bugs.
    return "CONVERT(0x" + value.encode().hex() + " USING utf8mb4)"


def up():
    initialize()
    compose("up", "-d", "--wait", "--wait-timeout", "180")
    print("MySQL 8 and Redis 7 containers are healthy; host ports are bound to loopback only.")


def bootstrap():
    values = credentials()
    tables = set(sql("SHOW TABLES;").splitlines())
    if not tables:
        sql((REPO / "sql" / "vibe_music.sql").read_text())
        print("Imported the repository SQL into the empty demo database.")
    else:
        require({"tb_song", "tb_user", "tb_playlist", "tb_playlist_binding"}.issubset(tables),
                "Existing database has incomplete/unrecognized tables; refusing to replace any data")
        print("Existing tables retained; SQL import skipped.")
    username = sql_string(values["test_username"])
    email = sql_string(values["test_email"])
    digest = sql_string(hashlib.md5(values["test_password"].encode()).hexdigest())
    count = int(sql(f"SELECT COUNT(*) FROM tb_user WHERE username={username} OR email={email};"))
    if count:
        require(sql(f"SELECT COUNT(*) FROM tb_user WHERE username={username} AND email={email} AND password={digest} AND status=0;") == "1",
                "A conflicting demo account exists; refusing to overwrite its credentials")
        print("Existing local test account retained.")
    else:
        sql("INSERT INTO tb_user (username,password,email,create_time,update_time,status) "
            f"VALUES ({username},{digest},{email},NOW(),NOW(),0);")
        print("Created one local test account; credentials remain in private local state.")
    print(json.dumps({"song_count": int(sql("SELECT COUNT(*) FROM tb_song;")),
                      "playlist_count": int(sql("SELECT COUNT(*) FROM tb_playlist;"))}))


def start_backend():
    require((STATE / "application-dev.properties").is_file(), "Run init/up first")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
    require(java, "Java is unavailable; use a full JDK 17+ to build and run the backend")
    jar = Path(os.environ.get("DANCEFLOW_DEMO_JAR", REPO / "vibe-music-backend/vibe-music-server/target/vibe-music-server-0.0.1-SNAPSHOT.jar")).expanduser().resolve()
    require(jar.is_file(), "Build the backend first; the configured server JAR does not exist")
    command = [java, "-jar", str(jar), "--spring.profiles.active=dev",
               "--spring.config.additional-location=" + (STATE / "application-dev.properties").as_uri()]
    private_write(STATE / "backend.log", "")
    print(f"Starting the backend in this terminal on port {BACKEND_PORT}; AI, SMTP and media are not exercised.")
    print(f"Private backend log: {STATE / 'backend.log'}", flush=True)
    with (STATE / "backend.log").open("a") as log:
        process = subprocess.Popen(command, cwd=REPO / "vibe-music-backend", stdout=log, stderr=subprocess.STDOUT)
        try:
            returncode = process.wait()
        except KeyboardInterrupt:
            process.terminate()
            process.wait()
            return
    require(returncode == 0, "Backend exited unsuccessfully; inspect the private local backend.log without sharing secret values")


def verify():
    values = credentials()
    parsed = urllib.parse.urlsplit(BACKEND_URL)
    require(parsed.scheme in {"http", "https"} and parsed.hostname, "Invalid backend URL")
    require(parsed.hostname in {"localhost", "127.0.0.1", "::1"},
            "This local demo only verifies loopback backend URLs")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request_object = urllib.request.Request(BACKEND_URL + path, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            response = opener.open(request_object, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        try:
            result = json.loads(response.read().decode())
        except (ValueError, UnicodeError):
            result = {}
        return response.status, result

    status, _ = request("/playlist/dance/save", {"title": "unauthenticated check", "songIds": [1]})
    require(status == 401, "Unauthenticated save was not rejected")
    status, login = request("/user/login", {"email": values["test_email"], "password": values["test_password"]})
    require(status == 200 and login.get("code") == 0 and isinstance(login.get("data"), str), "Real login failed")
    token = login["data"]
    ttl = int(compose("exec", "-T", "redis", "sh", "-c",
        'export REDISCLI_AUTH="$(cat /run/secrets/redis-password)"; exec redis-cli -n 1 -x TTL', input_text=token))
    require(ttl > 0, "Login did not create an expiring Redis DB 1 session")
    status, info = request("/user/getUserInfo", token=token)
    require(status == 200 and info.get("code") == 0, "Authenticated user API failed")
    status, listing = request("/song/getAllSongs", {"pageNum": 1, "pageSize": 5}, token)
    require(status == 200 and listing.get("code") == 0, "Real song query failed")
    items = listing.get("data", {}).get("items", [])
    require(len(items) >= 3, "At least three real database songs are required")
    ids = [items[2]["songId"], items[0]["songId"], items[1]["songId"]]
    for song_id in ids:
        status, detail = request(f"/song/getSongDetail/{song_id}", token=token)
        require(status == 200 and detail.get("code") == 0 and detail.get("data", {}).get("songId") == song_id,
                "Real song detail failed")
    title = "DanceFlow real demo " + str(time.time_ns())
    status, saved = request("/playlist/dance/save", {"title": title, "description": "Real database demo; no AI or playback", "songIds": ids}, token)
    require(status == 200 and saved.get("code") == 0 and type(saved.get("data")) is int, "Real playlist save failed")
    playlist_id = saved["data"]
    require(sql(f"SELECT COUNT(*) FROM tb_playlist WHERE id={playlist_id};") == "1", "Playlist is missing from MySQL")
    stored_ids = [int(value) for value in sql(f"SELECT song_id FROM tb_playlist_binding WHERE playlist_id={playlist_id};").splitlines()]
    require(sorted(stored_ids) == sorted(ids), "Persisted MySQL song membership differs")
    status, playlist = request(f"/playlist/getPlaylistDetail/{playlist_id}", token=token)
    require(status == 200 and playlist.get("code") == 0, "Saved playlist detail failed")
    returned_ids = [song["songId"] for song in playlist["data"]["songs"]]
    require(sorted(returned_ids) == sorted(ids), "Saved playlist detail has different songs")
    missing_id = int(sql("SELECT COALESCE(MAX(id),0)+1 FROM tb_song;"))
    for suffix, bad_ids in [("missing", [ids[0], missing_id]), ("duplicate", [ids[0], ids[0]])]:
        bad_title = title + " " + suffix
        status, result = request("/playlist/dance/save", {"title": bad_title, "songIds": bad_ids}, token)
        require(status == 400 and result.get("code") == 1, f"{suffix} song validation failed")
        require(sql(f"SELECT COUNT(*) FROM tb_playlist WHERE title={sql_string(bad_title)};") == "0",
                f"{suffix} request wrote a partial playlist")
    report = {"mode": "real MySQL + Redis + Spring Boot; no mocks", "song_count": int(sql("SELECT COUNT(*) FROM tb_song;")),
        "playlist_id": playlist_id, "redis_session_ttl_seconds": ttl, "requested_song_ids": ids,
        "mysql_song_ids": stored_ids, "detail_song_ids": returned_ids, "observed_order_matches": ids == returned_ids,
        "ordering_guaranteed": False, "unauthenticated_rejected": True,
        "missing_and_duplicate_rejected_without_writes": True, "ai_tested": False, "playback_tested": False}
    private_write(STATE / "verification.json", json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "up", "bootstrap", "start-backend", "verify", "down"])
    command = parser.parse_args().command
    actions = {"init": initialize, "up": up, "bootstrap": bootstrap,
               "start-backend": start_backend, "verify": verify,
               "down": lambda: (compose("down"), print("Demo containers stopped; MySQL volume retained."))}
    actions[command]()


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        # Never print SQL, request/response bodies, token values or subprocess output.
        print(f"Demo setup failed: {error if isinstance(error, RuntimeError) else type(error).__name__}", file=sys.stderr)
        sys.exit(1)
