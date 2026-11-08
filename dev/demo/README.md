# Minimal real-service DanceFlow demo

DanceFlow extends the existing VibeMusic work by Alex-LiSun and zl1nq. The original
LICENSE, NOTICE and attribution remain unchanged. This setup adds no product
features and downloads no audio files.

Requirements: Python 3 standard library, Docker with Docker Compose v2 supporting
`up --wait`, a full JDK 17+, Node.js and pnpm 9.15.2. Run commands from the repository
root. Docker's existing `DOCKER_HOST` / `DOCKER_CONTEXT` settings are respected.

```bash
python3 dev/demo/demo.py up
python3 dev/demo/demo.py bootstrap
cd vibe-music-backend
bash ./mvnw -B clean verify
# If Maven requires an existing proxy/settings file, use:
# bash ./mvnw -s "$MAVEN_SETTINGS" -B clean verify
cd ..
python3 dev/demo/demo.py start-backend
```

Keep the backend terminal running. In another terminal:

```bash
python3 dev/demo/demo.py verify
cd vibe-music-client
pnpm install --frozen-lockfile
pnpm dev
```

MySQL 8 and password-protected Redis 7 bind only to loopback on ports 3307 and 6380.
The backend binds to loopback on port 8080, matching the existing frontend HTTP
client. The frontend normally uses port 8090. Optional **non-secret** overrides are
listed in `.env.example`; export them before running the script (it does not
implicitly source a `.env` file). Use a distinct `DANCEFLOW_DEMO_PROJECT` and ports
for a second independent demo. If changing backend port, account for the existing
frontend HTTP client's fixed backend URL.
The verifier accepts loopback backend URLs only. The script runs Redis as the
local file owner so bind-mounted secret files can stay private (`0600`).

All credentials are generated locally into ignored `dev/demo/.local/` with private
permissions. `init` and `up` retain existing credentials. The external Spring
configuration stays there, outside `src/main/resources`, and is not packaged into
the JAR. Do not copy, commit or share this directory, its logs, or login tokens.
MinIO's generated unused values only satisfy client construction; no MinIO or SMTP
server runs. DashScope is disabled and controllers are initialized lazily. AI
requests are not supported by this demo configuration; no API key is invented.

`bootstrap` imports `sql/vibe_music.sql` **only when the database has no tables**.
It preserves an existing recognized schema and creates/reuses one generated local
test account without overwriting other users. If a conflicting account or partial
schema exists, it refuses to overwrite it. Keep the private local state together
with its Docker project/volume; deleting only credentials does not reset MySQL.

`verify` performs real login, checks the Redis DB 1 session TTL, queries database
songs and their details, saves a fresh playlist, checks MySQL directly, reads the
playlist detail, and rejects missing/duplicate song IDs without partial writes.
Each run leaves one clearly named demo playlist. Results are in
`.local/verification.json`; the expected original SQL catalog contains 499 songs,
but the script reports the actual count. This is a real save-workflow test, **not
an AI or music-playback end-to-end test**.

For a manual UI demonstration, use the generated local account from the private
credentials file in the existing login dialog, then browse the real library and
open `/playlist/<playlist_id>` using the ID from verification. Saving through an AI
response button still requires a separately configured real DashScope service;
do not inject artificial AI cards and call that an AI demonstration.

The `/dance` page can generate real catalog-based practice plans without an AI
key and labels that deterministic mode as non-AI. Run
`python3 dev/demo/verify_practice.py` after starting the backend to verify generation
and saving against real services. See [generator rules](../../vibe-music-backend/docs/dance-practice.md).

The schema has no owner/privacy fields and no playlist-song position column:
playlists are public/shared, and song order is **not guaranteed to survive reads**.
The verifier reports the observed order honestly. Missing media files are expected;
no playback verification is claimed.

Additional checks:

```bash
cd vibe-music-client
pnpm build
pnpm type-check
```

The original four frontend type errors were present before this extension; record
actual output rather than treating a production build as a successful type check.
Stop only this demo's containers with `python3 dev/demo/demo.py down` from the
repository root. Its MySQL volume is retained; no automatic destructive reset is
provided. Stop the foreground backend with Ctrl+C.
