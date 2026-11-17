# AdShield checker (Android / Java)

Dev-only conformance suite for the Android port of the shared AdShield core
("隱藏重複廣告", SPEC v2: algoVersion 2, formatVersion 2, tables `f40fc4cc`).
Nothing in `lumina-dev/` is part of any Gradle source set, so the app build never compiles it.

```sh
sh lumina-dev/adshield-check/run.sh      # JDK >= 9 (compiles with javac --release 8)
```

What it runs, all on the engine's own 16 MB-stack thread (`LuminaAdShieldThread`):

| Section | What |
|---|---|
| siphash-paper / siphash-77 | SipHash-2-4 paper values; `vectors/siphash_vectors.json` (64 raw + 13 tagged) |
| tsv-131 | `vectors/*.tsv` like `adshield/check/CheckVectors.java`, LF and CRLF |
| tables | fold idempotent / never outputs DROP over all scalars, Hangul composition, invisibles |
| normalize-55 | `vectors/normalize_vectors.json`: fold (string + scalars), text, cjk, wlen, cjkCount, otherLetterCount, paragraphs |
| fingerprint-57 | `vectors/fingerprint_vectors.json`: every SPEC §10.2 field (links in order with token/type/kind/source/feature/hash, keys in order with kind/tag/payload/hash, flags, subsetLinks, pending, resolved*, senderKey, store, endorsing, ...) |
| scenarios-97 | `corpus/scenarios.json`, runner identical to `ref/test_adshield.py` `run_scenario` (fold sets and states, 3 sensitivities) |
| load-order-shuffle / reverse-order | `test_observe_order_independent` (5 shuffles per eligible scenario; FP-20a/b, RG-01/02, EV-25 covered) and FP-20a vs FP-20b |
| storage | round trip (same bytes, same decisions), wrong key/chat, truncation, corrupt body, forged stores, no plaintext in the blob, delete anonymizes, row cap, eviction, observe range checks, endorsement upsert, not-ad determinism, handle order |
| spec-8.3-rejections | every SPEC §8.3 rejection rule on blobs written by an independent writer, plus boundary values that must still be accepted |
| account | ForgetAll (files gone, every write refused, delayed flush cannot recreate files), per-userId dirs, reload, purgeOrphans after a key change, logout vs panic wipe, total cap LRU by mtime, no files while off/ineligible, 8-chat memory cap, FileOps seam |
| robustness | deep-recursion input on a 64 KB stack never escapes `observe`; same input processed on the engine thread |
| perf | `observe` x3000 and `decide` timings on a 3000-row chat |

`vectors/` and `corpus/` are verbatim copies of `adshield/vectors` and `adshield/corpus`
(re-copy them whenever the shared core is regenerated; `.gitattributes` keeps them byte-exact).
`TMessagesProj/src/main/java/org/telegram/messenger/lumina/AdShieldTables.java` is the unmodified
generator output `adshield/out/AdShieldTables.java`.
