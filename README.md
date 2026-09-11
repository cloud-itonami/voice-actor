# gftd-voice-actor

A **free TTS voice-line generation** loop actor for
[`network-isekai`](https://github.com/gftdcojp/network-isekai), gftdcojp's
seventh and last of seven per-modality asset actors (ADR-2607123000).
Persona: **コエ (Koe)**, 声優 (narrator/voice) — "台詞の呼吸を大事にする
ナレーター。棒読みを何より嫌う — 短い一言でも息づかいを残す" (see
`resources/persona.edn`). Sibling actors: `gftd-illust-actor` (image),
`gftd-sculpt-actor` (3D), `gftd-rig-actor` (auto-rig), `gftd-motion-actor`
(motion clips), `gftd-avatar-actor` (VRM compositing), `gftd-audio-actor`
(music+SFX).

Built on the same "sealed intelligence ⊣ independent governor ⊣ append-only
ledger" containment pattern as this workspace's other actors
(`gftd-talent-actor`, `wami-actor`, `cloud-itonami`, and its `gftd-illust-actor`
reference sibling) — here it is **co-scientist tournament ⊣ AssetGovernor**,
run by a **durable outer loop** (not a StateGraph — murakumo generation jobs
are async, minutes-scale, and this workspace's CLAUDE.md is explicit that
long-running work belongs in a lease/tick/budget loop, not a StateGraph
interrupt).

## The core contract

```
voice.generate             murakumo fleet (async gen.job)      voice.judge
 (closed gene pool,   ──▶  submit via cloud-murakumo.gen +  ──▶ (persona-fit
  persona-flavored)        queue-kotoba, poll for :done)         script score)
                                    │
                                    ▼
                        voice.cosci/run-round
              (Reflection=HARD gate, Ranking=Elo on judge score,
                    Proximity, Evolution, Meta-review)
                                    │
                              round winner
                                    ▼
                          voice.governor/violations
                    (license-free? format-ok? safe? titled?
                          write-kind is :asset only)
                          │                    │
                        ok?                  hard
                          ▼                    ▼
          voice.datalad + voice.aozora      voice.ledger
          (save to assets/, datalad push,   (:held — no binary
           publish to net.voice.asset)       is ever saved)
```

**The actor never commits/publishes an asset the AssetGovernor would
reject**, and it never writes anything but `:kind :asset` — it does not
touch network-isekai's game logic or canon, it only produces free material
for games to consume.

Engine is `:tts` (murakumo modality `:voice`, models `cosyvoice2`
default/ja, `kokoro` fast — `cloud-murakumo`'s `resources/murakumo.edn`).
`voice.generate/round-candidates`'s `:prompt` is literally **the text to be
spoken** — a short, single-sentence NPC greeting/hint/flavor-remark/mutter/
warning/farewell composed from a closed line-bank, not a comma-joined
descriptor list (that's idiomatic for `gftd-illust-actor`'s image-diffusion
prompts, not for a line a narrator would actually say).

**HONEST LIMITS** (state these, do not pretend otherwise):
- `voice.judge` scores the candidate's **script** — how the line reads on
  the page, persona-fit and naturalness as written — not the rendered audio
  the job actually produced (prosody, pacing, whether the synthesized voice
  actually breathes the way the persona wants). A real perceptual judge (an
  audio-quality model, a speech-to-text-then-critique pass) is follow-up.
- Whether a submitted job ever leaves `:queued` depends on a murakumo fleet
  worker (Mac-mini / `gad`) being up and consuming the `gftd-murakumo` kotoba
  queue — this actor only submits/polls, it never runs GPU inference itself.
- `voice.murakumo/artifact-url`'s CID→URL resolution is a best-effort guess
  (`KOTOBASE_ARTIFACT_BASE_URL` overrides it), not a confirmed contract.

## This repo IS its own DataLad dataset

Unlike a typical actor repo, `assets/` here is **git-annex + Backblaze B2**
(`-c text2git`: code/EDN stay plain git, binaries get annexed) — accepted
assets are saved straight into this repo and pushed to B2, so "actor's own
git repo" and "asset storage" are the same thing (ADR-2607123000 §5).
`assets/<id>.edn` is written in the `network-isekai` `isekai.asset` manifest
shape (`:asset/gen {:stage :tts ...}`, matching network-isekai's own gen
stage naming) so a later Asset Hub import needs no conversion.

```sh
datalad get assets/            # fetch real bytes from B2 (skeleton clones without them)
datalad push --to b2           # push new bytes after a local save
```

## Running

```sh
kbb -M:run tick     # one durable-loop step (cron/launchd)
kbb -M:run run      # stay resident, tick on an interval
kbb -M:run status   # print ledger tail + loop state
kbb -M:test         # offline, fully faked (no network) — see test/voice/loop_test.kotoba
kbb -M:lint         # clj-kondo, errors fail
```

Env: `ASSET_ACTOR_DAILY_BUDGET` (default 8 gen jobs/day),
`MURAKUMO_KOTOBA_URL`/`MURAKUMO_KOTOBA_GRAPH`/`MURAKUMO_KOTOBA_TOKEN`
(queue-kotoba auth), `MURAKUMO_GATEWAY_URL` (judge's chat-completions
gateway).

CACAO identity is self-minted to `.voice/identity.edn` on first run
(gitignored — never commit a private key). aozora collection:
`net.voice.asset.publish`.

## Design

ADR-2607123000 (`network-isekai 向け murakumo 生成アセット持続ループ actor
群`) is the SSoT for this actor and its six siblings. Direct code ancestry:
`cloud-itonami`'s `src/cloud_itonami/media/{murakumo,aozora,cacao,publisher,
publish}.clj(c)` (murakumo→governor→aozora pipeline), `cloud-murakumo`'s
`src/cloud_murakumo/cosci.cljc` (co-scientist tournament shape), and its
reference sibling `gftd-illust-actor` (actor #1 of 7 — this actor is a
faithful port, substituting only the TTS/voice-line domain specifics).
