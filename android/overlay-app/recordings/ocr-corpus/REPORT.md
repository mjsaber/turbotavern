# OCR card-corpus report (Layer 2)

Per-locale OCR→match on clean HearthstoneJSON **512x card renders** (card-name-banner font), via the app's ML Kit on an emulator. **This is a PROXY with locale-dependent bias — read the numbers accordingly:**

- **enUS (Latin) ≈ representative** — Latin reads fine even in the small name band.

- **zhCN/zhTW (CJK) are a PESSIMISTIC LOWER BOUND, not an upper bound.** The card name-band is small at 512x and ML Kit mangles dense CJK glyphs (e.g. BG20_HERO_102 薩魯法爾霸王 → '福魯法霸文'), beyond fuzzy tolerance. This UNDERSTATES real select-screen accuracy: the in-game select banner renders names much larger — a real zhTW select frame matched **4/4**. So CJK ground truth = accumulate real select frames, NOT card renders; a name-band crop+upscale could raise these card numbers (future refinement).

15 newer BG heroes have no render (listed at bottom) — not covered here.


## Per-locale OCR→match rate

| locale | matched | total | rate |
|---|---|---|---|
| enUS | 92 | 98 | 0.939 |
| zhCN | 39 | 98 | 0.398 |
| zhTW | 25 | 98 | 0.255 |


## enUS misses (6)

| cardId | OCR read | matched instead |
|---|---|---|
| BG24_HERO_100 | e Denathrius / e / Denathrius / Sire / e Denathrius / e / Denathrius / Sire | ∅ |
| TB_BaconShop_HERO_16 | A, E Kay / A, / E / Kay / 14 / A, E Kay / A, / E / Kay / 14 | ∅ |
| TB_BaconShop_HERO_25 | Lich Bahiaj / Lich / Bahiaj / 18 / Lich Bahiaj / Lich / Bahiaj | ∅ |
| TB_BaconShop_HERO_35 | Yogg-Saron, / ,Hope's End / ,Hope's / End / 14 / Yogg-Saron, / ,Hope's End / ,Hope's / End | ∅ |
| TB_BaconShop_HERO_57 | nuiopzON / nuiopzON | ∅ |
| TB_BaconShop_HERO_76 | AlARir / 13 / AlARir | ∅ |

## zhCN misses (59)

| cardId | OCR read | matched instead |
|---|---|---|
| BG20_HERO_100 | 12 / 洛卡益 / 12 | ∅ |
| BG20_HERO_102 | 17 / 萨得活尔大全 / 17 | ∅ |
| BG20_HERO_103 | 10 / E语者布克松 | ∅ |
| BG20_HERO_242 | 10 / 古夫。 / 行文图楼 | ∅ |
| BG20_HERO_280 | 15 / 「库尔特書斯 。陨炽 / 「库尔特書斯 / 。陨炽 / 15 | ∅ |
| BG20_HERO_282 | 10 / 塔姆等。因奴 / 10 | ∅ |
| BG20_HERO_301 | 13 / 吞者穆坦多斯 | ∅ |
| BG21_HERO_000 | 16 / 16 | ∅ |
| BG21_HERO_010 | 斯卡布數 。刀油 / 斯卡布數 / 。刀油 | ∅ |
| BG22_HERO_000 | 15 / 塔進什。雷系 | ∅ |
| BG22_HERO_003 | 10 / 范达尔 ,。雷矛 / 范达尔 / ,。雷矛 | ∅ |
| BG22_HERO_004 | 13 / 瓦尔登 。晨拥 / 瓦尔登 / 。晨拥 | ∅ |
| BG22_HERO_007 | 13 / 女萨拉女里 / 13 | ∅ |
| BG22_HERO_200 | 8 / 伊妮。积, | ∅ |
| BG22_HERO_201 | 14 / 林大使 / 14 | ∅ |
| BG23_HERO_303 | ¦摩洛克。福尔學斯 | ∅ |
| BG23_HERO_304 | 13 / 瓦丝琪小 | ∅ |
| BG23_HERO_306 | 18 / 「希尔瓦斯 。风行者 / 「希尔瓦斯 / 。风行者 / 不要妄想遨靖她- / 起游泳。女王绝不 / 沉浮。 | ∅ |
| BG24_HERO_100 | 纳修大帝 | ∅ |
| BG25_HERO_103 | 12 / 塔匯。蜃 / 12 | ∅ |
| BG26_HERO_102 | 11 / 「因葛, / 汉铁颂歌 / 11 | ∅ |
| BG26_HERO_104 | 12 / 家教父汉8 / 12 | ∅ |
| BG27_HERO_801 | 15 / ¦风暴后 L托里娘 / ¦风暴后 / L托里娘 | ∅ |
| BG28_HERO_400 | 6 / 地眼 | ∅ |
| BG28_HERO_800 | 16 / 泰瑟兰。血里者 / 16 | ∅ |
| TB_BaconShop_HERO_01 | 17 / 艾德温。 范克里失 / 艾德温。 / 范克里失 / 17 | ∅ |
| TB_BaconShop_HERO_02 | 17 / 也拉克隆 / 17 | ∅ |
| TB_BaconShop_HERO_08 | 14 / 伊利开 怒风 / 伊利开 / 怒风 / 14 | ∅ |
| TB_BaconShop_HERO_10 | 6 / 贸易大 里维克斯 / 贸易大 / 里维克斯 | ∅ |
| TB_BaconShop_HERO_14 | 15 / 包关格尔女足 / 15 | ∅ |
| TB_BaconShop_HERO_16 |  | ∅ |
| TB_BaconShop_HERO_17 | 16 / 米尔丝。 法力风暴 / 米尔丝。 / 法力风暴 / 16 | ∅ |
| TB_BaconShop_HERO_18 | 10 / 一海盈帕司 | ∅ |
| TB_BaconShop_HERO_23 | 5 / 秒德沃克 | ∅ |
| TB_BaconShop_HERO_25 | 18 / A大巴亚尔 / 18 | ∅ |
| TB_BaconShop_HERO_27 | 18 / 年达苟 / 18 | ∅ |
| TB_BaconShop_HERO_28 | 13 / 13 | ∅ |
| TB_BaconShop_HERO_29 | 18 / 克苏息 / 18 | ∅ |
| TB_BaconShop_HERO_34 | 帕奇维 | ∅ |
| TB_BaconShop_HERO_40 | 12 / 芬利。 莫格顿第士 / 芬利。 / 莫格顿第士 / 12 | ∅ |
| TB_BaconShop_HERO_41 | 16 / 雷诺。 泰克逊 / 雷诺。 / 泰克逊 / 16 | ∅ |
| TB_BaconShop_HERO_42 | 15 / 伊莉斯。:星 | ∅ |
| TB_BaconShop_HERO_45 | 15 / 至。尊盗王法, | ∅ |
| TB_BaconShop_HERO_49 | 10 / 米尔豪。 法力风暴 / 米尔豪。 / 法力风暴 / 10 | ∅ |
| TB_BaconShop_HERO_50 | 17 / 苔丝。 ,格雷迈恩 / 苔丝。 / ,格雷迈恩 / 17 | ∅ |
| TB_BaconShop_HERO_52 | 10 / 死之象 | ∅ |
| TB_BaconShop_HERO_55 | 15 / 菌菇术。士弗於格尔 / 15 | ∅ |
| TB_BaconShop_HERO_56 | 13 / 阿菜 丝塔萨 / 阿菜 / 丝塔萨 | ∅ |
| TB_BaconShop_HERO_57 | 13 / 诺的多嫂 | ∅ |
| TB_BaconShop_HERO_58 | 14 / 里苟斯 / 14 | ∅ |
| TB_BaconShop_HERO_60 | 18 / 肌尔萨斯 。逐日者 / 肌尔萨斯 / 。逐日者 / 18 | ∅ |
| TB_BaconShop_HERO_62 | 15 / 妈维。歌 | ∅ |
| TB_BaconShop_HERO_67 | 物牙船 | ∅ |
| TB_BaconShop_HERO_70 | 16 / 化格沃斯先经 / 16 | ∅ |
| TB_BaconShop_HERO_71 | 15 / 詹迪斯。 巴罗失 / 詹迪斯。 / 巴罗失 | ∅ |
| TB_BaconShop_HERO_75 | 15 / 拉市尼休 | ∅ |
| TB_BaconShop_HERO_78 | 15 / 所恩瓦 | ∅ |
| TB_BaconShop_HERO_90 | 10 / o暗月 / 希拉斯 | ∅ |
| TB_BaconShop_HERO_92 | 16 / 亚煞校 / 16 | ∅ |

## zhTW misses (73)

| cardId | OCR read | matched instead |
|---|---|---|
| BG20_HERO_100 | 12 / 洛卡益 | ∅ |
| BG20_HERO_101 | 8 / 賽瑞 | ∅ |
| BG20_HERO_102 | 17 / 福魯法霸文 | ∅ |
| BG20_HERO_103 | 10 / 亡語音某林 | ∅ |
| BG20_HERO_242 | 10 / 葛夫。 行文勝 / 葛夫。 / 行文勝 | ∅ |
| BG20_HERO_280 | 15 / 克圖斯 。次强 / 克圖斯 / 。次强 / 15 | ∅ |
| BG20_HERO_282 | 10 / 塔姆令。: / 10 | ∅ |
| BG20_HERO_301 | 13 / 者坦努斯 | ∅ |
| BG21_HERO_000 | 16 / 凱瑞(。 / 16 | ∅ |
| BG21_HERO_010 | 10 / 史蓋1伯斯 。油切 / 史蓋1伯斯 / 。油切 / 10 | ∅ |
| BG21_HERO_020 | 餅敦大廚 | ∅ |
| BG21_HERO_030 | 20 / 斯尼速 / 20 | ∅ |
| BG22_HERO_000 | 15 / 為維西。電系 | ∅ |
| BG22_HERO_001 | 15 / 布會坎 | ∅ |
| BG22_HERO_003 | 10 / Ea達期。電第 / 10 | ∅ |
| BG22_HERO_004 | 13 / 瓦堂。長援 | ∅ |
| BG22_HERO_007 | 13 / 失薩拉女 | ∅ |
| BG22_HERO_200 | 伊尼 / 6風基線图 | ∅ |
| BG22_HERO_305 | 奥妮克亞 | ∅ |
| BG23_HERO_201 | 18 / 欧蘇瑪特 / 18 | ∅ |
| BG23_HERO_303 | 8 / 魚學斯 | ∅ |
| BG23_HERO_304 |  | ∅ |
| BG23_HERO_305 | 14 / 劫匪大; ,发瓦哥 / 劫匪大; / ,发瓦哥 | ∅ |
| BG23_HERO_306 | 18 / 希瓦娜斯。 風行春 / 希瓦娜斯。 / 風行春 / 別根約她出來喝咖 / 啡,她絕不會服 / 從。 | ∅ |
| BG24_HERO_100 | 載納瑟斯至 | ∅ |
| BG24_HERO_204 | 13 / 强化機器人 | ∅ |
| BG25_HERO_103 | 12 / 訊。血屬 / 12 | ∅ |
| BG26_HERO_101 | 10 / 電格澳飛長 / 10 | ∅ |
| BG26_HERO_102 | 1 / R鋼鐵禮演) 英格 / R鋼鐵禮演) / 英格 | ∅ |
| BG26_HERO_104 | 12 / 羌滾 師沃恩 / 羌滾 / 師沃恩 / 12 | ∅ |
| BG27_HERO_801 | 15 / 『風基之年 索林 / 『風基之年 / 索林 | ∅ |
| BG28_HERO_400 | 6 / 妃眼 | ∅ |
| BG28_HERO_800 | 16 / 「泰瑟退。血看守春 | ∅ |
| BG28_HERO_801 | 8 / 喬生词利飛 / 8 | ∅ |
| TB_BaconShop_HERO_01 | 17 / 艾德温 / 。范克里失 | ∅ |
| TB_BaconShop_HERO_02 | 17 / 高拉克房 | ∅ |
| TB_BaconShop_HERO_08 | 14 / 伊利开 / 。怒風 | ∅ |
| TB_BaconShop_HERO_10 | 6 / L王加里組克斯 | ∅ |
| TB_BaconShop_HERO_14 | 15 / 瓦哥夫女王 | ∅ |
| TB_BaconShop_HERO_15 | 15 / 愛巷的魯治 / 15 | ∅ |
| TB_BaconShop_HERO_16 | 14 / 制。伊夏 / 14 | ∅ |
| TB_BaconShop_HERO_17 | 16 / 米欧菲瑟。 曼納斯頓 / 米欧菲瑟。 / 曼納斯頓 | ∅ |
| TB_BaconShop_HERO_18 | 10 / 海盜派 | ∅ |
| TB_BaconShop_HERO_21 | 10 / 「魔術大師阿十贊れ克 | ∅ |
| TB_BaconShop_HERO_25 | 18 / 巫妖拜的希聞 / 18 | ∅ |
| TB_BaconShop_HERO_27 | 18 / 惠拉苟 / 18 | ∅ |
| TB_BaconShop_HERO_34 | 維補春 | ∅ |
| TB_BaconShop_HERO_35 | 14 / 尤格薩係倫 / 14 | ∅ |
| TB_BaconShop_HERO_36 | 舞的談瑞 | ∅ |
| TB_BaconShop_HERO_38 | 10 / 移克拉 / 10 | ∅ |
| TB_BaconShop_HERO_39 | 16 / 佩狂金字塔 | ∅ |
| TB_BaconShop_HERO_40 | 12 / 苏利。戈頓士 / 12 | ∅ |
| TB_BaconShop_HERO_42 | 15 / 伊莉線 。尋星 / 伊莉線 / 。尋星 | ∅ |
| TB_BaconShop_HERO_43 | SUART- / 18 / 恐龍馴服者布菜.恩 | ∅ |
| TB_BaconShop_HERO_45 | 15 / 强天王五拉法娛 | ∅ |
| TB_BaconShop_HERO_50 | 17 / 「泰線 。葛 5雷恩 / 「泰線 / 。葛 / 5雷恩 | ∅ |
| TB_BaconShop_HERO_52 | 10 / 死白之翼 / 10 | ∅ |
| TB_BaconShop_HERO_55 | 15 / 具苗滿師師弟象 | ∅ |
| TB_BaconShop_HERO_57 | 13 / 諾统多幼 | ∅ |
| TB_BaconShop_HERO_59 | 12 / 艾蘭娜。 尋星春 / 艾蘭娜。 / 尋星春 / 12 | ∅ |
| TB_BaconShop_HERO_60 | 18 / 「凱應德粉。 を日省 / 「凱應德粉。 / を日省 | ∅ |
| TB_BaconShop_HERO_62 | 15 / 瑪翼失 ,影歌 / 瑪翼失 / ,影歌 | ∅ |
| TB_BaconShop_HERO_67 | 釣牙船 | ∅ |
| TB_BaconShop_HERO_702 | 19 / 間獄之主 / 19 | ∅ |
| TB_BaconShop_HERO_70 | 16 / 集匀沃斯先く / 16 | ∅ |
| TB_BaconShop_HERO_71 | 15 / 詹迪斯 。巴雞失 / 詹迪斯 / 。巴雞失 | ∅ |
| TB_BaconShop_HERO_74 | 「森林看守音穆 | ∅ |
| TB_BaconShop_HERO_75 | 15 / 拉卡尼你 / 15 | ∅ |
| TB_BaconShop_HERO_76 | 13 / 奥拉基原 | ∅ |
| TB_BaconShop_HERO_78 | 15 / 瓦拉 | ∅ |
| TB_BaconShop_HERO_90 | 10 / 东拉斯。暗月 / 10 | ∅ |
| TB_BaconShop_HERO_91 | 17 / 偉大的赛弗瑞斯! | ∅ |
| TB_BaconShop_HERO_94 | 12 / 提卡斯 / 12 | ∅ |

## Not covered — no card render (15 heroes)

BG30_HERO_304, BG31_HERO_003, BG31_HERO_005, BG31_HERO_006, BG31_HERO_801, BG31_HERO_802, BG31_HERO_811, BG32_HERO_001, BG32_HERO_002, BG33_HERO_001, BG34_HERO_000, BG34_HERO_001, BG34_HERO_002, BG34_HERO_004, BG35_HERO_001
