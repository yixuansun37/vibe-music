#!/usr/bin/env python3
"""Real catalog generation/save checks; no API mocks or artificial AI replies."""
from decimal import Decimal, ROUND_CEILING
import json
import time
import urllib.error
import urllib.parse
import urllib.request

import demo


def main():
    demo.require(urllib.parse.urlsplit(demo.BACKEND_URL).hostname in {"localhost", "127.0.0.1", "::1"},
                 "Only send local demo credentials to loopback")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(demo.BACKEND_URL + path, headers=headers,
                data=json.dumps(body).encode() if body is not None else None)
        try:
            response = opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        try:
            result = json.loads(response.read())
        except (ValueError, UnicodeError):
            result = {}
        return response.status, result

    status, _ = request("/playlist/dance/generate", {"danceStyle": "house", "targetMinutes": 20})
    demo.require(status == 401, "Generation must require authentication")
    credentials = demo.credentials()
    status, login = request("/user/login", {"email": credentials["test_email"], "password": credentials["test_password"]})
    demo.require(status == 200 and login.get("code") == 0, "Real login failed")
    token = login["data"]
    summaries, first_plan = [], None
    for style, minutes in [("hiphop", 20), ("house", 20), ("jazz", 20), ("hiphop", 90)]:
        payload = {"danceStyle": style, "targetMinutes": minutes}
        status, result = request("/playlist/dance/generate", payload, token)
        demo.require(status == 200 and result.get("code") == 0, "Real generation failed")
        plan = result["data"]
        demo.require([p["key"] for p in plan["sections"]] == ["warmup", "main", "freestyle"], "Missing sections")
        songs = [song for part in plan["sections"] for song in part["songs"]]
        ids = [song["songId"] for song in songs]
        demo.require(len(ids) == len(set(ids)) and 0 < len(ids) <= 50, "Invalid selection")
        demo.require(sum(s["durationMs"] for s in songs) == plan["actualDurationMs"], "Incorrect durations")
        demo.require(sum(p["actualDurationMs"] for p in plan["sections"]) == plan["actualDurationMs"], "Incorrect section totals")
        demo.require(0 <= plan["actualDurationMs"] <= minutes * 60_000 == plan["targetDurationMs"], "Target exceeded")
        demo.require(plan["shortfallMs"] == plan["targetDurationMs"] - plan["actualDurationMs"], "Incorrect shortfall")
        demo.require(not plan["shortfallMs"] or plan["warnings"], "Shortfall must be visible")
        for song in songs:
            status, result = request(f"/song/getSongDetail/{song['songId']}", token=token)
            actual = result.get("data")
            demo.require(status == 200 and result.get("code") == 0 and actual, "Song absent from real catalog")
            for field in ["songId", "songName", "artistName", "album", "duration", "audioUrl", "coverUrl"]:
                demo.require(actual.get(field) == song.get(field), "Invented or changed song metadata")
            milliseconds = int((Decimal(actual["duration"]) * 1000).to_integral_value(rounding=ROUND_CEILING))
            demo.require(milliseconds == song["durationMs"], "Song duration differs from database")
        if plan["generationMode"] == "DETERMINISTIC":
            _, repeated = request("/playlist/dance/generate", payload, token)
            demo.require(repeated["data"] == plan, "Non-AI generation is not repeatable")
        summaries.append({"danceStyle": style, "targetMinutes": minutes, "generationMode": plan["generationMode"],
            "songCount": len(ids), "actualDurationMs": plan["actualDurationMs"], "shortfallMs": plan["shortfallMs"]})
        first_plan = first_plan or plan

    for bad in [{"danceStyle": "unknown", "targetMinutes": 20}, {"danceStyle": "hiphop", "targetMinutes": 20.5}]:
        status, _ = request("/playlist/dance/generate", bad, token)
        demo.require(status == 400, "Invalid generation request was accepted")
    ids = [s["songId"] for p in first_plan["sections"] for s in p["songs"]]
    status, saved = request("/playlist/dance/save", {"title": "DanceFlow generated demo " + str(time.time_ns()),
        "description": "Generated preview; ordering and sections are not persisted", "songIds": ids}, token)
    demo.require(status == 200 and saved.get("code") == 0 and type(saved.get("data")) is int, "Saving failed")
    playlist_id = saved["data"]
    stored = [int(v) for v in demo.sql(f"SELECT song_id FROM tb_playlist_binding WHERE playlist_id={playlist_id};").splitlines()]
    demo.require(sorted(stored) == sorted(ids), "Generated songs were not persisted in MySQL")
    status, detail = request(f"/playlist/getPlaylistDetail/{playlist_id}", token=token)
    demo.require(status == 200 and sorted(s["songId"] for s in detail["data"]["songs"]) == sorted(ids), "Saved detail differs")
    report = {"mode": "real MySQL/Redis/backend; no mocks", "plans": summaries, "savedPlaylistId": playlist_id,
        "mysqlPersistenceConfirmed": True, "orderingGuaranteed": False,
        "liveAiProviderIndependentlyTested": False, "playbackTested": False}
    demo.private_write(demo.STATE / "practice-verification.json", json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        # Assertion messages and demo.compose errors are static, not response bodies.
        raise SystemExit(f"Practice verification failed: {error}")
    except Exception:
        # Never echo HTTP bodies, SQL parameters, passwords, tokens or provider errors.
        raise SystemExit("Practice verification failed; inspect local services without sharing secrets")
