export const t = {
  tabs: {
    heroes: "英雄",
    comps: "阵容",
    askBob: "问问鲍勃",
    heroTierList: "英雄梯队榜",
    compositions: "阵容推荐",
    overlay: "悬浮窗",
  },

  heroes: {
    searchPlaceholder: "搜索英雄...",
    all: "全部",
    close: "关闭",
    heroPower: "英雄技能",
    armor: "护甲",
    armorValue: (value: number) => `${value} 护甲`,
    strategyTips: "策略提示",
  },

  comps: {
    subtitle: "当前版本热门阵容与配合",
    close: "关闭",
    description: "描述",
    keyMinions: "核心随从",
    supports: "辅助",
    strategy: "策略",
    difficulty: (level: string) => `难度: ${level}`,
  },

  chat: {
    suggestedQuestions: [
      "现在最强的英雄是哪些？",
      "野兽流怎么玩？",
      "什么时候该升到4级酒馆？",
      "米尔豪斯和尤格萨隆选哪个？",
    ],
    apiKeyRequired: "需要API密钥",
    apiKeyPrompt: "请输入你的Anthropic API密钥来与鲍勃聊天：",
    cancel: "取消",
    save: "保存",
    askBobAnything: "有什么都问鲍勃！",
    emptySubtitle: "你的酒馆老板鲍勃随时准备帮你分析策略、选择英雄等。",
    bobIsThinking: "鲍勃正在思考...",
    inputPlaceholder: "问鲍勃一个问题...",
    errorPrefix: "抱歉，出了点问题：",
    genericError: "发送消息失败",
  },

  components: {
    bob: "鲍勃",
    armorLabel: (armor: number) => `护甲: ${armor}`,
    keyMinionsLabel: "核心随从:",
  },

  difficulty: {
    Easy: "简单",
    Medium: "中等",
    Hard: "困难",
  } as Record<string, string>,

  overlay: {
    title: "悬浮窗",
    enable: "启用悬浮窗",
    disable: "关闭悬浮窗",
    permissionRequired: "需要悬浮窗权限",
    permissionMessage: "请在设置中授予悬浮窗权限，以便在游戏中显示英雄梯队。",
    openSettings: "打开设置",
    heroTierList: "英雄梯队",
    notSupported: "悬浮窗功能仅支持安卓设备",
    serviceRunning: "悬浮窗服务运行中（支持语音搜索）",
    serviceStopped: "悬浮窗已关闭",
    overlayDescription:
      "开启悬浮窗后，可在炉石传说酒馆战棋游戏中直接查看英雄梯队排名。点击🎤按钮可语音搜索英雄进行对比。",
    audioPermissionRequired: "需要麦克风权限以使用语音搜索",
  },
} as const;
