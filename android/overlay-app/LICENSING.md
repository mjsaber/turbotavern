# Turbo Tavern — Licensing

Turbo Tavern ships as two build flavors with different licenses, from one codebase.

## clean (Google Play) — proprietary
- `applicationId com.turbotavern`. No VPN, no 拔线, **no GPL code**.
- Licensed under the proprietary EULA (`app/src/clean/assets/licenses/EULA.txt`).
- Contains only permissively-licensed third-party code (ML Kit, ONNX Runtime, PaddleOCR).
- Verified GPL-free: the clean APK has no `libgojni.so` / gomobile / bobcore.

## full (sideload) — GPL-3.0
- `applicationId com.bobassist.phase0`. Adds 拔线 (VPN socket-kill) via the bundled mihomo core.
- Because it links mihomo (GPL-3.0), the **entire full APK is a GPL-3.0 derivative work** and MUST
  be distributed with its Corresponding Source under GPL-3.0.
- License + attribution are bundled in `app/src/full/assets/licenses/` (`GPL-3.0.txt`, `NOTICE.txt`)
  and shown in-app via `AboutActivity`.

## ⚠️ What the GPL full build exposes
The full build's Corresponding Source includes **`app/src/main`** — the entire overlay / OCR /
hero-tier / trinket engine (the bulk of the app). Shipping the full GPL build therefore
**open-sources that engine**. The clean build's protection is then **brand + Play distribution +
no-ban-risk + server-gated premium data (Stage 7)** — NOT code secrecy. If you are unwilling to
open-source the engine, the alternative is to drop the full build entirely and ship only clean on
Play (which also means dropping 拔线).

## Publishing the full Corresponding Source (GPL obligation)
For every released full APK you must publish the matching source. Recommended:

1. Create a **public** repo (e.g. `github.com/<you>/turbo-tavern`) under GPL-3.0 containing the full
   build's source: everything **EXCEPT `app/src/clean/`** (which holds the proprietary EULA + the
   clean no-op). Add a root `LICENSE` = GPL-3.0 (copy of `app/src/full/assets/licenses/GPL-3.0.txt`).
2. Tag each release to match the APK `versionName`/`versionCode`.
3. Put that repo's URL in `app/src/full/assets/licenses/NOTICE.txt` (replace `REPLACE-ME`).
   A Gradle guard blocks `assembleFullRelease`/`bundleFullRelease` until you do.

mihomo (the GPL core) is itself published at <https://github.com/MetaCubeX/mihomo> (v1.19.25); the
full build links it via `app/libs/bobcore.aar` and attributes it in `NOTICE.txt` (including the
upstream "do not reuse the mihomo name" clause).

## Keeping clean proprietary while full is GPL
`app/src/clean/` is the only flavor-private code (just `NoopKillFeature` + the EULA). Keep it OUT of
the public GPL repo. You own all non-mihomo code, so you may dual-license it: GPL-3.0 as part of the
full build, proprietary as part of the clean build.
