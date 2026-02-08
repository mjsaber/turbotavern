export interface Composition {
  id: string;
  name: string;
  tribe: string;
  tier: "S" | "A" | "B" | "C";
  difficulty: string;
  description: string;
  keyMinions: string[];
  supports: string[];
  strategy: string;
}

export const compositions: Composition[] = [
  {
    id: "beasts",
    name: "野兽流",
    tribe: "野兽",
    tier: "S",
    difficulty: "中等",
    description: "强力的亡语和铺场型野兽阵容，通过召唤大量随从压制对手。",
    keyMinions: ["金铃", "瑞文戴尔男爵", "巨型鹦鹉", "剑齿蛇神"],
    supports: ["复生", "嘲讽"],
    strategy: "利用瑞文戴尔男爵叠加金铃的加成。使用巨型鹦鹉多次触发野兽亡语。注意站位——鹦鹉应该最先攻击。",
  },
  {
    id: "mechs",
    name: "机械流",
    tribe: "机械",
    tier: "S",
    difficulty: "困难",
    description: "圣盾刷新型机械阵容，在战斗中几乎不可能被消灭。",
    keyMinions: ["康格的学徒", "神圣机械鱼", "偏斜机器人", "润滑机器人"],
    supports: ["圣盾", "磁力"],
    strategy: "围绕圣盾刷新构建阵容。润滑机器人恢复圣盾，偏斜机器人在召唤时获得圣盾。康格的学徒会复活你死亡的机械并保留强化效果。",
  },
  {
    id: "murlocs",
    name: "鱼人毒流",
    tribe: "鱼人",
    tier: "A",
    difficulty: "中等",
    description: "拥有剧毒和高生命值的鱼人，能够高效交换任何战场。",
    keyMinions: ["毒鳍鱼人", "鱼人探宝者", "鱼人国王巴格尔格", "年轻的鱼眼"],
    supports: ["布莱恩·铜须", "剧毒"],
    strategy: "让毒鳍鱼人给你的鱼人附加剧毒。布莱恩使战吼翻倍获得海量属性。鱼人国王巴格尔格死亡时增益所有鱼人。",
  },
  {
    id: "dragons",
    name: "龙流·卡雷苟斯",
    tribe: "龙",
    tier: "A",
    difficulty: "中等",
    description: "基于战吼的龙族成长阵容，后期属性增长极快。",
    keyMinions: ["卡雷苟斯", "红龙女王纳蒂娜", "锐角龙", "塔蕾苟萨"],
    supports: ["布莱恩·铜须", "战吼随从"],
    strategy: "卡雷苟斯在你使用战吼时给所有龙属性加成。配合布莱恩获得双倍效果。纳蒂娜为你的龙提供圣盾。",
  },
  {
    id: "demons",
    name: "恶魔杂耍流",
    tribe: "恶魔",
    tier: "B",
    difficulty: "简单",
    description: "自残型恶魔通过战斗成长并向敌人投掷伤害。",
    keyMinions: ["贪食的地狱蝠", "灵魂杂耍者", "小鬼妈妈", "愤怒编织者"],
    supports: ["流浪观察者", "灵魂杂耍者"],
    strategy: "愤怒编织者通过购买恶魔获得前期成长。灵魂杂耍者在恶魔死亡时造成伤害。贪食的地狱蝠和小鬼妈妈提供后期战力。",
  },
  {
    id: "elementals",
    name: "元素叠加流",
    tribe: "元素",
    tier: "B",
    difficulty: "简单",
    description: "每回合打出元素来叠加永久属性加成。",
    keyMinions: ["诺米", "小拉格", "温柔的灯神", "回收幽灵"],
    supports: ["聚会元素", "静滞元素"],
    strategy: "每回合尽可能多打出元素。诺米给商店中所有元素巨额加成。小拉格在你打出元素时给一个随机友方元素加成。",
  },
  {
    id: "undead",
    name: "亡灵复生流",
    tribe: "亡灵",
    tier: "A",
    difficulty: "中等",
    description: "复生的亡灵随从不断复活并触发亡语连锁。",
    keyMinions: ["永恒骑士", "阿努巴拉克", "制作木乃伊者", "克尔苏加德"],
    supports: ["瑞文戴尔男爵", "复生"],
    strategy: "永恒骑士在任何友方复生随从死亡时永久获得属性。制作木乃伊者赋予复生。克尔苏加德复活你死亡的亡灵。",
  },
  {
    id: "menagerie",
    name: "动物园流",
    tribe: "混合",
    tier: "B",
    difficulty: "困难",
    description: "混合种族的战场同时增益所有随从类型。",
    keyMinions: ["光牙执行者", "布莱恩·铜须", "融合怪", "密斯拉克斯"],
    supports: ["多样种族随从"],
    strategy: "光牙执行者每回合给每种类型各一个加成。保持战场种族多样性。融合怪根据你的不同种族获得适应。密斯拉克斯随种族多样性成长。",
  },
];
