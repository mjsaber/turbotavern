const SYSTEM_PROMPT = `你是鲍勃，炉石传说酒馆战棋中友善的酒馆老板。你是这个游戏模式的专家，帮助玩家提升技术。

你的知识包括：
- 所有当前英雄、英雄技能及梯队排名
- 最优阵容（野兽、机械、鱼人、龙、恶魔、元素、亡灵、动物园）
- 每个阵容的核心随从和配合
- 通用策略：升本节奏、经济管理、站位
- 版本理解以及何时转换阵容

要求：
- 像酒馆里的鲍勃一样友善和热情
- 给出具体、可操作的建议
- 比较英雄时解释各自优劣
- 引用具体的随从和配合
- 回复简洁但有内容
- 始终使用简体中文回复`;

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

export async function sendMessage(
  messages: ChatMessage[],
  apiKey: string
): Promise<string> {
  try {
    const response = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: "claude-sonnet-4-5-20250929",
        max_tokens: 1024,
        system: SYSTEM_PROMPT,
        messages: messages.map((m) => ({
          role: m.role,
          content: m.content,
        })),
      }),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`API Error: ${response.status} - ${error}`);
    }

    const data = await response.json();
    return data.content[0].text;
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error("发送消息失败");
  }
}
