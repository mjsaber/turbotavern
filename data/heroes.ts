export type Tier = "S" | "A" | "B" | "C" | "D";

export interface Hero {
  id: string;
  name: string;
  heroPower: string;
  heroPowerDescription: string;
  tier: Tier;
  tips: string;
  armor: number;
}

export const TIER_COLORS: Record<Tier, string> = {
  S: "#ff6b6b",
  A: "#ffa502",
  B: "#2ed573",
  C: "#1e90ff",
  D: "#a4a4a4",
};

export const heroes: Hero[] = [
  {
    id: "rafaam",
    name: "大反派拉法姆",
    heroPower: "我要了！",
    heroPowerDescription: "在你消灭一个随从后，将它的一张白板复制加入你的手牌。（1金币）",
    tier: "S",
    tips: "强力节奏英雄。前期多买三连，利用偷取的复制找到关键棋子。和铺场型随从配合极佳。",
    armor: 10,
  },
  {
    id: "millhouse",
    name: "米尔豪斯·法力风暴",
    heroPower: "法力风暴",
    heroPowerDescription: "被动：随从花费2金币。刷新花费2金币。初始2金币。",
    tier: "S",
    tips: "刷新很贵但购买便宜。积极购买，前期不要刷太多。对子和三连会自然出现。",
    armor: 0,
  },
  {
    id: "yogg",
    name: "尤格-萨隆",
    heroPower: "谜之匣",
    heroPowerDescription: "雇佣鲍勃酒馆中一个随机随从并使其获得+1/+1。（2金币）",
    tier: "A",
    tips: "非常适合寻找你需要的关键棋子。金币有余时使用英雄技能。中期很强。",
    armor: 7,
  },
  {
    id: "omu",
    name: "森林看守者奥穆",
    heroPower: "常青术",
    heroPowerDescription: "被动：在你升级鲍勃酒馆后，本回合获得2金币。",
    tier: "A",
    tips: "积极升本。几乎比任何人都能更快到达5或6级酒馆。提前规划你的升本曲线。",
    armor: 7,
  },
  {
    id: "jandice",
    name: "詹迪斯·巴罗夫",
    heroPower: "交换",
    heroPowerDescription: "将一个友方非金色随从与鲍勃酒馆中的一个随从交换。（0金币）",
    tier: "A",
    tips: "对大型铺场或战吼随从使用交换。免费交换意味着你可以高效循环酒馆。",
    armor: 5,
  },
  {
    id: "reno",
    name: "雷诺·杰克逊",
    heroPower: "要发财了！",
    heroPowerDescription: "使一个友方随从变为金色。（4金币，每场游戏一次）",
    tier: "A",
    tips: "留给高价值目标如布莱恩、男爵或关键成长随从。时机就是一切——不要急。",
    armor: 10,
  },
  {
    id: "galewing",
    name: "疾风之翼",
    heroPower: "疾风鼓舞",
    heroPowerDescription: "使一个随从获得+3/+3。每回合在攻击力和生命值之间切换。（1金币）",
    tier: "B",
    tips: "每回合稳定的属性加成。将加成集中在你的核心随从上。适合任何阵容。",
    armor: 7,
  },
  {
    id: "alexstrasza",
    name: "阿莱克丝塔萨",
    heroPower: "龙之女王",
    heroPowerDescription: "被动：当你将鲍勃酒馆升级到5级后，发现两条龙。",
    tier: "B",
    tips: "按节奏升到5级。两条免费的龙可以启动龙族阵容或提供三连。尽早确定龙族方向。",
    armor: 0,
  },
  {
    id: "pyramad",
    name: "金字塔",
    heroPower: "砖砌术",
    heroPowerDescription: "使一个随机友方随从获得+4生命值。（1金币）",
    tier: "B",
    tips: "生命值加成帮助前期存活。将核心随从单独放置以确保加成命中它。和元素配合良好。",
    armor: 7,
  },
  {
    id: "patchwerk",
    name: "帕奇维克",
    heroPower: "全部缝好",
    heroPowerDescription: "被动：以60点生命值而非40点开始游戏。",
    tier: "B",
    tips: "额外生命值让你可以贪心并更快升本。不要浪费生命值优势——用它来寻找后期阵容。",
    armor: 0,
  },
  {
    id: "deathwing",
    name: "死亡之翼",
    heroPower: "一切尽焚！",
    heroPowerDescription: "被动：所有随从获得+2攻击力。",
    tier: "B",
    tips: "全场攻击力加成有利于所有阵容。略微偏向拥有大量小随从的阵容。前期战斗力强。",
    armor: 5,
  },
  {
    id: "lich_king",
    name: "巫妖王",
    heroPower: "重生仪式",
    heroPowerDescription: "使一个友方随从在下次战斗中获得复生。（1金币）",
    tier: "B",
    tips: "对亡语随从或大型嘲讽随从效果最佳。和机械及亡灵阵容配合良好。",
    armor: 5,
  },
  {
    id: "sindragosa",
    name: "辛达苟萨",
    heroPower: "保持冷静",
    heroPowerDescription: "在你的回合结束时，被冻结的随从获得+2/+1。（0金币）",
    tier: "C",
    tips: "有策略地冻结来增益关键随从。如果持续冻结同一个商店，可以多回合叠加增益。",
    armor: 7,
  },
  {
    id: "jaraxxus",
    name: "加拉克苏斯大王",
    heroPower: "血怒",
    heroPowerDescription: "使你的恶魔获得+1/+1。（2金币）",
    tier: "C",
    tips: "只对恶魔有效。如果对局中有恶魔就尽早确定方向。增益虽小但随时间累积。",
    armor: 5,
  },
  {
    id: "nzoth",
    name: "恩佐斯",
    heroPower: "恩佐斯的化身",
    heroPowerDescription: "战斗开始时：你的随从触发它们的亡语。（2金币）",
    tier: "C",
    tips: "配合正确的亡语随从非常强力。恩佐斯之鱼和机械蛋是极好的目标。注意触发时机。",
    armor: 10,
  },
  {
    id: "curator",
    name: "馆长",
    heroPower: "博物学家",
    heroPowerDescription: "被动：以一个拥有所有随从类型的1/1融合怪开始游戏。",
    tier: "C",
    tips: "融合怪受益于所有种族增益。尽可能长时间保留它，用任何种族配合来增益它。",
    armor: 5,
  },
  {
    id: "afk",
    name: "挂机哥",
    heroPower: "拖延术",
    heroPowerDescription: "被动：跳过你的前2个回合。以两个3级酒馆随从开始。",
    tier: "D",
    tips: "前期会受到伤害但3级随从可以稳住局面。祈祷好的发现。高方差英雄。",
    armor: 15,
  },
  {
    id: "wagtoggle",
    name: "瓦格托格女王",
    heroPower: "蜡铸战团",
    heroPowerDescription: "使一个随机友方机械、恶魔、鱼人和野兽获得+2生命值。（1金币）",
    tier: "D",
    tips: "需要动物园型战场才能发挥。无法保持种族多样性时很弱。在有很多种族可用的对局中更好。",
    armor: 7,
  },
];

export function getHeroesByTier(tier: Tier): Hero[] {
  return heroes.filter((h) => h.tier === tier);
}

export function searchHeroes(query: string): Hero[] {
  const lower = query.toLowerCase();
  return heroes.filter(
    (h) =>
      h.name.toLowerCase().includes(lower) ||
      h.heroPower.toLowerCase().includes(lower)
  );
}
