import React, { useState, useCallback, useRef } from "react";
import {
  View,
  Text,
  TextInput,
  Pressable,
  FlatList,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Alert,
  StyleSheet,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { Ionicons } from "@expo/vector-icons";
import { useAppStore } from "../../lib/store";
import { sendMessage, type ChatMessage } from "../../lib/claude";
import { ChatBubble } from "../../components/ChatBubble";
import { t } from "../../i18n/zh";

export default function ChatScreen() {
  const [input, setInput] = useState("");
  const flatListRef = useRef<FlatList>(null);

  const { messages, isLoading, apiKey, addMessage, setLoading, setApiKey } =
    useAppStore();

  const handleSend = useCallback(
    async (text?: string) => {
      const messageText = text || input.trim();
      if (!messageText || isLoading) return;

      if (!apiKey) {
        Alert.prompt(
          t.chat.apiKeyRequired,
          t.chat.apiKeyPrompt,
          [
            { text: t.chat.cancel, style: "cancel" },
            {
              text: t.chat.save,
              onPress: (key) => {
                if (key) {
                  setApiKey(key);
                  handleSend(messageText);
                }
              },
            },
          ],
          "plain-text"
        );
        return;
      }

      const userMessage: ChatMessage = { role: "user", content: messageText };
      addMessage(userMessage);
      setInput("");
      setLoading(true);

      try {
        const allMessages = [...messages, userMessage];
        const reply = await sendMessage(allMessages, apiKey);
        addMessage({ role: "assistant", content: reply });
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : t.chat.genericError;
        addMessage({
          role: "assistant",
          content: `${t.chat.errorPrefix}${errorMessage}`,
        });
      } finally {
        setLoading(false);
      }
    },
    [input, isLoading, apiKey, messages, addMessage, setLoading, setApiKey]
  );

  const renderMessage = useCallback(
    ({ item }: { item: ChatMessage }) => <ChatBubble message={item} />,
    []
  );

  const keyExtractor = useCallback(
    (_item: ChatMessage, index: number) => index.toString(),
    []
  );

  const handleSuggestedQuestion = useCallback(
    (question: string) => {
      handleSend(question);
    },
    [handleSend]
  );

  return (
    <SafeAreaView style={styles.container} edges={["bottom"]}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
        keyboardVerticalOffset={90}
      >
        {messages.length === 0 ? (
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>🍺</Text>
            <Text style={styles.emptyTitle}>{t.chat.askBobAnything}</Text>
            <Text style={styles.emptySubtitle}>
              {t.chat.emptySubtitle}
            </Text>
            <View style={styles.suggestions}>
              {t.chat.suggestedQuestions.map((question) => (
                <Pressable
                  key={question}
                  style={({ pressed }) => [
                    styles.suggestionButton,
                    pressed && styles.suggestionPressed,
                  ]}
                  onPress={() => handleSuggestedQuestion(question)}
                >
                  <Text style={styles.suggestionText}>{question}</Text>
                </Pressable>
              ))}
            </View>
          </View>
        ) : (
          <FlatList
            ref={flatListRef}
            data={messages}
            renderItem={renderMessage}
            keyExtractor={keyExtractor}
            contentContainerStyle={styles.messageList}
            onContentSizeChange={() => flatListRef.current?.scrollToEnd()}
            showsVerticalScrollIndicator={false}
          />
        )}

        {isLoading && (
          <View style={styles.loadingContainer}>
            <ActivityIndicator color="#f5a623" size="small" />
            <Text style={styles.loadingText}>{t.chat.bobIsThinking}</Text>
          </View>
        )}

        <View style={styles.inputContainer}>
          <TextInput
            style={styles.input}
            placeholder={t.chat.inputPlaceholder}
            placeholderTextColor="#8b8b9e"
            value={input}
            onChangeText={setInput}
            multiline
            maxLength={500}
            returnKeyType="send"
            onSubmitEditing={() => handleSend()}
          />
          <Pressable
            style={({ pressed }) => [
              styles.sendButton,
              (!input.trim() || isLoading) && styles.sendButtonDisabled,
              pressed && styles.sendButtonPressed,
            ]}
            onPress={() => handleSend()}
            disabled={!input.trim() || isLoading}
          >
            <Ionicons
              name="send"
              size={20}
              color={input.trim() && !isLoading ? "#ffffff" : "#8b8b9e"}
            />
          </Pressable>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#1a1a2e",
  },
  flex: {
    flex: 1,
  },
  emptyState: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingHorizontal: 32,
  },
  emptyIcon: {
    fontSize: 64,
    marginBottom: 16,
  },
  emptyTitle: {
    color: "#eaeaea",
    fontSize: 24,
    fontWeight: "bold",
    marginBottom: 8,
  },
  emptySubtitle: {
    color: "#8b8b9e",
    fontSize: 15,
    textAlign: "center",
    lineHeight: 22,
    marginBottom: 32,
  },
  suggestions: {
    width: "100%",
    gap: 10,
  },
  suggestionButton: {
    backgroundColor: "#16213e",
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  suggestionPressed: {
    opacity: 0.7,
  },
  suggestionText: {
    color: "#eaeaea",
    fontSize: 14,
    textAlign: "center",
  },
  messageList: {
    paddingVertical: 16,
  },
  loadingContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 8,
    gap: 8,
  },
  loadingText: {
    color: "#f5a623",
    fontSize: 13,
  },
  inputContainer: {
    flexDirection: "row",
    alignItems: "flex-end",
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderTopWidth: 1,
    borderTopColor: "#2a2a4e",
    gap: 10,
  },
  input: {
    flex: 1,
    backgroundColor: "#16213e",
    borderRadius: 20,
    paddingHorizontal: 16,
    paddingVertical: 10,
    color: "#eaeaea",
    fontSize: 15,
    maxHeight: 100,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  sendButton: {
    backgroundColor: "#e94560",
    borderRadius: 20,
    width: 40,
    height: 40,
    justifyContent: "center",
    alignItems: "center",
  },
  sendButtonDisabled: {
    backgroundColor: "#2a2a4e",
  },
  sendButtonPressed: {
    opacity: 0.8,
  },
});
