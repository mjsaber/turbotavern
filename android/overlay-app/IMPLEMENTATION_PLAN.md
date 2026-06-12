# Bob Assistant — Go-to-Market Implementation Plan

**Strategy (locked 2026-06-12): two SKUs from one repo.**

- **`clean` flavor** → `com.bobassist` → **Google Play, closed-source**, freemium + subscription.
  Overlay-only (hero tier + trinket recs via MediaProjection OCR). **ZERO GPL code.**
- **`full` flavor** → `com.bobassist.full` → **sideload / F-Droid / GitHub Releases, GPL-3.0 open-source**, free.
  Everything in `clean` PLUS 拔线 (VpnService + mihomo). Lead-gen / enthusiast funnel.

**Why the split is legally clean:** mihomo (GPL-3.0) is linked ONLY in `full`, so only `full` is a GPL
derivative → published as GPL-3.0 source. `clean` contains no GPL code, so it stays proprietary. We own all
non-mihomo Kotlin and use permissive libs (ML Kit, ONNX = Apache-2.0), so we may dual-license our own code
(GPL in `full`, proprietary in `clean`).

**Monetization:** premium data (trinket/comp recommendations) is **server-gated by subscription token**. The
open `full` client cannot unlock it without a valid subscription (server + data stay proprietary; mihomo is
GPL not AGPL → no network copyleft). Both SKUs hit the same backend. `full` is un-monetizable by GPL (anyone
may redistribute it free) — that's fine, it's the free funnel; revenue lives in the `clean` Play subscription.

**Honest expectation:** hobby/portfolio scale (~$1–6万/yr gross), declining niche. Validate demand with a free
beta before heavy paid-funnel investment. Blizzard ban risk on `full` is unchanged by open-sourcing — warn users.

## Standing acceptance gates (EVERY stage)
No stage is "done" on unit tests alone. Each stage must pass, in order:
1. **Unit tests green** (full Robolectric/JVM suite).
2. **Codex review** — `codex review --base <ref>` on each step/commit; address findings before moving on.
3. **Real-app acceptance** — build the flavor, install to the emulator HS rig (`emulator-5554`; phone `e85c3473`
   fallback), and exercise the live flow once in the running app (MediaProjection capture + real-frame OCR +
   overlay render + permission flow). If the emulator is too unstable, fall back to the phone or report the
   blocker honestly — never fake the real-sim run.

---

## Stage 1: Build-flavor split + extract OverlayService — **[Not Started]**
**Goal:** `clean` builds with zero GPL refs; `full` keeps 拔线; overlay runs on MediaProjection alone.
**Steps:**
- Extract the MediaProjection tier pipeline out of `BobVpnService` into a standalone `OverlayService`
  (foregroundServiceType=mediaProjection only), in `src/main`.
- Add `clean`/`full` product flavors. Move `BobVpnService`, `MihomoCore`, `CoreFacades`,
  `ConnectionCoreProvider`, `BattleConnectionController` + `fullImplementation(files("libs/bobcore.aar"))`
  into `src/full`.
- `clean` manifest: no VpnService, no specialUse FGS; FGS subtype renamed to honest OCR/overlay purpose.
**Success Criteria:** `grep com.bobassist.gomobile src/main src/clean` → empty; both flavors assemble; full
unit suite green; `clean` shows the overlay with NO VPN consent dialog.

## Stage 2: Licensing hygiene — **[Not Started]**
- `full`: bundle GPL-3.0 LICENSE + mihomo NOTICE (incl. name clause); About screen with Corresponding-Source URL.
- `clean`: proprietary EULA; verify no GPL artifacts in the assembled APK.

## Stage 3: De-prototype + release hygiene — **[Not Started]**
- Finalize applicationIds; real versionName/versionCode scheme; app label + adaptive icon + theme.
- Signing config reading from gitignored `keystore.properties` (user supplies the upload key).
- Enable R8 minify + resource shrink (release) with keep rules for ONNX Runtime / ML Kit / gomobile.

## Stage 4: Onboarding + Settings — **[Not Started]**
- Replace the 5-button debug `MainActivity` with per-permission rationale onboarding + denial recovery.
- Settings: language override (en/zh-CN/zh-TW), feature toggles, overlay opacity/badge size.

## Stage 5: Localization — **[Not Started]**
- Externalize all UI literals → `strings.xml`; add `values-zh-rCN` + `values-zh-rTW` (after onboarding
  stabilizes the string set). Localize the notification channel too.

## Stage 6: Real-device polish — **[Not Started]**
- Density-scale badge geometry (`BADGE_PX`/`GAP_PX`/`HIGHLIGHT_INFLATE_PX` → dp).
- Device matrix: resolutions, display cutouts, 90/120/144Hz, OnePlus/Samsung/Xiaomi overlay quirks.

## Stage 7: Monetization — **[Not Started]**
- Backend subscription entitlement (Play purchase-token validation + server-delivered tier data).
- Play Billing in `clean`; freemium split (free hero overlay; premium trinket/comp + Firestone-style daily taste).
- Pricing: US $2.49/mo · $14.99/yr; TW NT$69/mo · NT$390/yr; annual-led + 7-day trial.

## Stage 8: Launch prep — **[Not Started]**
- Privacy policy + Play Data Safety (MediaProjection + Usage Stats; on-device, no upload).
- Tri-locale store listings; free beta → measure reach/retention; demand polling (Reddit/Discord/Bahamut zh-TW).
- User-facing Blizzard-ToS/ban-risk warning in `full`.
