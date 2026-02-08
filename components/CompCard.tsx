import React, { memo, useCallback } from "react";
import { View, Text, Pressable, StyleSheet } from "react-native";
import { type Composition } from "../data/comps";
import { t } from "../i18n/zh";

const COMP_TIER_COLORS: Record<string, string> = {
  S: "#ff6b6b",
  A: "#ffa502",
  B: "#2ed573",
  C: "#1e90ff",
};

const TRIBE_COLORS: Record<string, string> = {
  "野兽": "#8B4513",
  "机械": "#708090",
  "鱼人": "#00CED1",
  "龙": "#DC143C",
  "恶魔": "#8B008B",
  "元素": "#FF4500",
  "亡灵": "#556B2F",
  "混合": "#DAA520",
};

interface CompCardProps {
  comp: Composition;
  onPress: (comp: Composition) => void;
}

export const CompCard = memo(function CompCard({ comp, onPress }: CompCardProps) {
  const handlePress = useCallback(() => {
    onPress(comp);
  }, [comp, onPress]);

  const tribeColor = TRIBE_COLORS[comp.tribe] || "#8b8b9e";

  return (
    <Pressable
      onPress={handlePress}
      style={({ pressed }) => [
        styles.card,
        pressed && styles.cardPressed,
      ]}
    >
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <Text style={styles.name}>{comp.name}</Text>
          <View style={[styles.tierBadge, { backgroundColor: COMP_TIER_COLORS[comp.tier] + "22", borderColor: COMP_TIER_COLORS[comp.tier] }]}>
            <Text style={[styles.tierText, { color: COMP_TIER_COLORS[comp.tier] }]}>{comp.tier}</Text>
          </View>
        </View>
        <View style={styles.metaRow}>
          <View style={[styles.tribeBadge, { backgroundColor: tribeColor + "33" }]}>
            <Text style={[styles.tribeText, { color: tribeColor }]}>{comp.tribe}</Text>
          </View>
          <Text style={styles.difficulty}>{comp.difficulty}</Text>
        </View>
      </View>
      <Text style={styles.description} numberOfLines={2}>{comp.description}</Text>
      <View style={styles.minionsContainer}>
        <Text style={styles.minionsLabel}>{t.components.keyMinionsLabel}</Text>
        <View style={styles.minionsList}>
          {comp.keyMinions.map((minion) => (
            <View key={minion} style={styles.minionChip}>
              <Text style={styles.minionText}>{minion}</Text>
            </View>
          ))}
        </View>
      </View>
    </Pressable>
  );
});

const styles = StyleSheet.create({
  card: {
    backgroundColor: "#16213e",
    borderRadius: 12,
    padding: 16,
    marginHorizontal: 16,
    marginVertical: 6,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  cardPressed: {
    opacity: 0.8,
    transform: [{ scale: 0.98 }],
  },
  header: {
    marginBottom: 10,
  },
  titleRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 6,
  },
  name: {
    color: "#eaeaea",
    fontSize: 18,
    fontWeight: "bold",
  },
  tierBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
    borderWidth: 1,
  },
  tierText: {
    fontSize: 14,
    fontWeight: "bold",
  },
  metaRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  tribeBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
  },
  tribeText: {
    fontSize: 12,
    fontWeight: "600",
  },
  difficulty: {
    color: "#8b8b9e",
    fontSize: 12,
  },
  description: {
    color: "#8b8b9e",
    fontSize: 13,
    lineHeight: 18,
    marginBottom: 10,
  },
  minionsContainer: {
    marginTop: 4,
  },
  minionsLabel: {
    color: "#f5a623",
    fontSize: 12,
    fontWeight: "600",
    marginBottom: 6,
  },
  minionsList: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 6,
  },
  minionChip: {
    backgroundColor: "#2a2a4e",
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  minionText: {
    color: "#eaeaea",
    fontSize: 12,
  },
});
