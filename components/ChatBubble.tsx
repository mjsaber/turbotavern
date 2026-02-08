import React, { memo } from "react";
import { View, Text, StyleSheet } from "react-native";
import { type ChatMessage } from "../lib/claude";
import { t } from "../i18n/zh";

interface ChatBubbleProps {
  message: ChatMessage;
}

export const ChatBubble = memo(function ChatBubble({ message }: ChatBubbleProps) {
  const isUser = message.role === "user";

  return (
    <View style={[styles.container, isUser ? styles.userContainer : styles.botContainer]}>
      {!isUser && <Text style={styles.botName}>{t.components.bob}</Text>}
      <View style={[styles.bubble, isUser ? styles.userBubble : styles.botBubble]}>
        <Text style={[styles.text, isUser ? styles.userText : styles.botText]}>
          {message.content}
        </Text>
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: 16,
    paddingVertical: 4,
  },
  userContainer: {
    alignItems: "flex-end",
  },
  botContainer: {
    alignItems: "flex-start",
  },
  botName: {
    color: "#f5a623",
    fontSize: 12,
    fontWeight: "600",
    marginBottom: 4,
    marginLeft: 4,
  },
  bubble: {
    maxWidth: "80%",
    padding: 12,
    borderRadius: 16,
  },
  userBubble: {
    backgroundColor: "#e94560",
    borderBottomRightRadius: 4,
  },
  botBubble: {
    backgroundColor: "#16213e",
    borderBottomLeftRadius: 4,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  text: {
    fontSize: 15,
    lineHeight: 21,
  },
  userText: {
    color: "#ffffff",
  },
  botText: {
    color: "#eaeaea",
  },
});
