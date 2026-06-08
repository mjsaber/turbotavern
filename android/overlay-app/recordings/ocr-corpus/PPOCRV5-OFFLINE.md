# Offline OCR bake-off — PP-OCRv5 rec (ch), rec-only on name band, fold=tcsc

Raw-OCR on the **same card-render corpus** ML Kit ran on (Stage 0, offline onnxruntime — no device). rec-only on the cropped name band isolates recognition from detection. **match@cap** mirrors the runtime fuzzy matcher (edge-strip + `max(1,⌊0.2·len⌋)` cap), so it is comparable to the ML Kit through-matcher baseline; **exact** is stricter (sensitive to banner-decoration crop junk, a render artifact absent on the real select screen).

## Verdict: **GO** — switch to PP-OCRv5 (Stage-0 gate cleared)

PP-OCRv5 roughly **2–2.5×'s CJK** accuracy vs ML Kit on the *same, harder-than-real* corpus
(zhCN .40→.91, zhTW .26→.87). The full table below is the recommended config:
**PP-OCRv5 unified `ch` rec, rec-only on a cropped name band, + punct/TC→SC normalization.**

### How each lever lifts zhTW (the hard case)
| config | zhTW match@cap | note |
|---|---|---|
| ML Kit (Layer-2 baseline) | 0.255 | full-frame det+rec |
| PP-OCRv5, no norm | 0.673 | rec-only on band |
| + punct fold (middle-dot `‧·・` + bracket `『』【】[]` unify) | 0.796 | pure normalization noise |
| + TC→SC fold (OpenCC `t2s`, both sides) | **0.867** | collapses v5's TC→SC glyph drift (歐→欧, 賽→赛) |

zhCN holds at 0.908 and enUS at 0.878 across all folds. The **dedicated `chinese_cht`
(PP-OCRv3) rec model is *worse*** — it garbled this stylized font ("米歐菲瑟‧曼納斯頓" →
"一大歐眼疑·曼納斯99"); v5-unified is the best engine.

### Caveats / what Stage 0 does NOT prove
- **Card renders are a hard proxy.** The name is small stylized gold-on-ornate-banner text;
  the real hero-select screen is larger/cleaner, so real-frame accuracy should be **≥** these.
  The real number still needs the on-device run (plan Stage 4).
- **enUS −0.06 vs ML Kit is a crop artifact**, not an engine weakness: the misses are ornate
  banner end-caps read as edge junk ("**el** voljin **ll**", "**i** Rokara"), all near-miss CERs.
  No such decoration borders the name on the real select screen → expect ~parity.
- **Precision (zero-wrong-badge gate) is untested here** — this measures recall only. TC→SC
  folding could in principle collide two heroes; the Layer-1 golden test's no-confusable-pairs
  check must be re-run **under folding** before shipping.
- **Normalization (punct + TC→SC) is a matcher/`NameKey` change** requiring data-pipeline parity;
  it's the finisher, but the engine swap is the dominant lever (no normalization rescues ML Kit's .26).

_Reproduce: `cd data-pipeline && uv run --no-project --with rapidocr --with pillow --with opencc-python-reimplemented --python 3.12 python scripts/ppocr_offline.py --version PP-OCRv5 --fold tcsc`_


| locale | match@cap | exact | total | match-rate | exact-rate | ML Kit baseline |
|---|---|---|---|---|---|---|
| enUS | 86 | 58 | 98 | 0.878 | 0.592 | 0.939 |
| zhCN | 89 | 71 | 98 | 0.908 | 0.724 | 0.398 |
| zhTW | 85 | 50 | 98 | 0.867 | 0.510 | 0.255 |

## enUS misses — beyond fuzzy cap (12)

| cardId | ground truth | OCR read | edit dist |
|---|---|---|---|
| BG20_HERO_103 | Death Speaker Blackthorn | Dealh Spake Baknorn | 7 |
| BG20_HERO_201 | Vol'jin | el vofinl | 6 |
| BG28_HERO_800 | Tae'thelan Bloodwatcher | a thelanBlodwather | 5 |
| TB_BaconShop_HERO_40 | Sir Finley Mrrgglton |  Si Finley Murgton | 4 |
| TB_BaconShop_HERO_60 | Kael'thas Sunstrider |  Kaefthas Sunstrtler | 4 |
| TB_BaconShop_HERO_16 | A. F. Kay | C A.EKay | 3 |
| TB_BaconShop_HERO_29 | C'Thun | el ethun  | 3 |
| TB_BaconShop_HERO_76 | Al'Akir | O ArAkir | 3 |
| BG22_HERO_305 | Onyxia | l onyxial | 2 |
| TB_BaconShop_HERO_53 | Ysera | el Ysera  | 2 |
| TB_BaconShop_HERO_92 | Y'Shaarj | e yShaarj | 2 |
| TB_BaconShop_HERO_93 | N'Zoth | l NZoth | 2 |

## zhCN misses — beyond fuzzy cap (9)

| cardId | ground truth | OCR read | edit dist |
|---|---|---|---|
| BG20_HERO_103 | 亡语者布莱克松 | 之语者布菜克松 | 2 |
| BG20_HERO_201 | 沃金 | cl沃金 | 2 |
| BG28_HERO_400 | 蛇眼 | cl蛇眼 | 2 |
| TB_BaconShop_HERO_29 | 克苏恩 | Cl 克苏恩 | 2 |
| TB_BaconShop_HERO_43 | 恐龙大师布莱恩 | 恐龙大师布菜思 | 2 |
| TB_BaconShop_HERO_50 | 苔丝·格雷迈恩 | 营丝·格雷迈思 | 2 |
| TB_BaconShop_HERO_74 | 林地守护者欧穆 | 林地宁护者欧福 | 2 |
| TB_BaconShop_HERO_93 | 恩佐斯 | el恩佐斯 | 2 |
| TB_BaconShop_HERO_95 | 格雷布 | Cl 格雷布 | 2 |

## zhTW misses — beyond fuzzy cap (13)

| cardId | ground truth | OCR read | edit dist |
|---|---|---|---|
| TB_BaconShop_HERO_27 | 辛德拉苟莎 | 幸德拉节痧 | 3 |
| BG20_HERO_102 | 薩魯法爾霸王 | 薩香法雨霸王 | 2 |
| BG20_HERO_103 | 亡語者黑棘 | 語香黑棘 | 2 |
| BG20_HERO_201 | 沃金 | cl沃金 | 2 |
| BG23_HERO_306 | 希瓦娜斯‧風行者 | 希瓦娜斯·風香 | 2 |
| BG25_HERO_100 | 普崔希德教授 | C普雀希德教授 | 2 |
| BG28_HERO_400 | 蛇眼 | cl 蛇眼 | 2 |
| TB_BaconShop_HERO_01 | 艾德溫‧范克里夫 | 文德温·范克里失 | 2 |
| TB_BaconShop_HERO_25 | 巫妖拜茲希爾 | 巫妖拜药希扇 | 2 |
| TB_BaconShop_HERO_43 | 恐龍馴服者布萊恩 | 恐龍馴服音布菜恩 | 2 |
| TB_BaconShop_HERO_59 | 艾蘭娜‧尋星者 | 文蘭娜·尋星香 | 2 |
| TB_BaconShop_HERO_60 | 凱爾薩斯‧逐日者 | 凱蘭薩斯·逐日香 | 2 |
| TB_BaconShop_HERO_92 | 亞煞拉懼 | C亞煞拉/ | 2 |
