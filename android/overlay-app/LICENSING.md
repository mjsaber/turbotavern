# Turbo Tavern — Licensing

**The entire repository is open-source under GPL-3.0** (decided 2026-06-13). Both build flavors are
GPL; there is no proprietary flavor. The repo itself is the Corresponding Source.

Turbo Tavern still ships as **two build flavors from one codebase** — but the split is now driven by
**Google Play policy + Blizzard ban risk**, NOT by licensing.

## clean (Google Play)
- `applicationId com.turbotavern`. No VPN, no 拔线, no mihomo core.
- Excludes the mihomo/VPN path for two reasons — now **policy + size**, not copyright:
  - Google Play rejects 拔线 (deliberate disconnect) as game-cheating / network-abuse.
  - No reason to ship the ~46MB `libgojni.so` native blob the clean build never calls.
- GPL-3.0 like the rest of the repo; license shown in-app via `AboutActivity`.

## full (sideload)
- `applicationId com.turbotavern.full` (distinct id so it can coexist with the Play clean build).
- Adds 拔线 (VPN socket-kill) via the bundled mihomo core (`app/libs/bobcore.aar`).
- GPL-3.0; bundles `app/src/full/assets/licenses/` (`GPL-3.0.txt`, `NOTICE.txt`), shown in-app.

## Single source of truth (no error-prone duplication)
Shared overlap (`app/src/main`) is the single home for all cross-flavor logic. Variant differences are
expressed as **interfaces declared in `src/main`** with thin per-flavor selectors (`KillFeatureHolder`
etc.). See the dedup strategy: interface seams + compiler-verified bindings keep the two packages in
sync from one place. Build two APKs (`assembleCleanRelease` / `assembleFullRelease`), publish separately.

## GPL Corresponding Source
Because the whole repo is GPL-3.0 and public on GitHub, the repo URL satisfies the Corresponding-Source
obligation for **both** flavors. To finish:

1. Repo is public at `github.com/mjsaber/turbotavern` (renamed 2026-06-13 from `bob_assistant`).
   Root `LICENSE` = GPL-3.0.
2. Tag each release to match the APK `versionName`/`versionCode`.
3. Put the repo URL in `app/src/full/assets/licenses/NOTICE.txt` (replace `REPLACE-ME`).
   A Gradle guard blocks `assembleFullRelease`/`bundleFullRelease` until you do.

mihomo (the GPL core) is published upstream at <https://github.com/MetaCubeX/mihomo> (v1.19.25); the
full build links it via `app/libs/bobcore.aar` and attributes it in `NOTICE.txt` (including the
upstream "do not reuse the mihomo name" clause).

## Monetization is unaffected
A fully-open client does not block paid premium: mihomo is GPL **not** AGPL → no network copyleft, and
the premium tier-data server + dataset are not in this repo. Premium stays server-gated by subscription
token; the open client can't unlock it without a paid token.
