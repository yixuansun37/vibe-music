# DanceFlow practice generator

Log in and open `/dance`. Choose Hip-hop, House or Jazz dance and an integer target
of 5–90 minutes. Preview warm-up, main practice and freestyle, then use the existing
save dialog. `POST /playlist/dance/generate` uses existing user authentication:

```json
{"danceStyle":"hiphop","targetMinutes":20}
```

The existing `{code,message,data}` response contains three `sections` with real
song metadata and `durationMs`, section/total target and actual milliseconds,
`shortfallMs`, warnings, candidate count and `generationMode`.

## Rules and limits

Hip-hop maps to 嘻哈说唱, House to 电子, Jazz dance to 节奏布鲁斯. These are music
category heuristics, not verified dance suitability, BPM, tempo or energy data.
The existing music-tool/database and artist-name lookup is reused, with stable ID
ordering and at most 200 matching candidates. No unrelated genre is substituted.

Positive decimal seconds or strict `mm:ss` are parsed into integer milliseconds;
sub-millisecond fractions round upward. Invalid/overflowing durations are skipped
and song IDs deduplicated. The planner seeds up to three shortest fitting songs,
then greedily adds the longest remaining whole songs that fit, at most 50 songs.
It never exceeds the total target, but does not promise an optimal exact sum.

The selected list is ordered by validated AI preference or by ID. Cumulative
cut-points make approximately 20%/60%/20% sections, all nonempty when three songs
fit. Actual durations, empty sections, invalid data and shortages are reported.
Warm-up labels do not assert slower tempo or physiological suitability.

## AI boundary

The optional ranker uses the existing Spring AI `ChatModel` (DashScope). It sends
only real catalog IDs/names/artists and asks for their order. A reply must contain
every candidate exactly once, as integer IDs from that whitelist. Invalid replies,
provider errors, missing keys or disabled AI cause an explicitly non-AI fallback.
No write tools, shared chat memory or logging advisor are used. AI does not supply
song metadata, BPM, URLs, timing or section budgets.

The local demo disables DashScope, so `/dance` is usable without a key. Provider
adapter tests use a mocked model and do not establish live LLM success. Real AI
still requires secure credentials, allowed network access and separate validation.

## Saving and verification

Saving reuses `POST /playlist/dance/save`. The current schema does **not** persist
practice sections, song order, ownership or privacy; saved playlists are public.
No schema migration is included. No audio playback success is claimed.

Start the [local demo](../../dev/demo/README.md), then run:

```bash
python3 dev/demo/verify_practice.py
```

This checks real login, three styles, no-AI repeatability, durations and database
metadata, shortages, generated-song saving, direct MySQL persistence and detail
retrieval. Only an ignored local report is written; AI and playback remain distinct.
Backend: `bash ./mvnw -B clean verify` in `vibe-music-backend`.
Frontend: `pnpm build` and `pnpm type-check` in `vibe-music-client`; four original
type errors remain outside this feature's scope.
