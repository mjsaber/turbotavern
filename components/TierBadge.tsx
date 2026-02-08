import React, { memo } from "react";
import { View, Text, StyleSheet } from "react-native";
import { type Tier, TIER_COLORS } from "../data/heroes";

interface TierBadgeProps {
  tier: Tier;
  size?: "small" | "large";
}

export const TierBadge = memo(function TierBadge({ tier, size = "small" }: TierBadgeProps) {
  const isLarge = size === "large";
  return (
    <View
      style={[
        styles.badge,
        { backgroundColor: TIER_COLORS[tier] + "22", borderColor: TIER_COLORS[tier] },
        isLarge && styles.badgeLarge,
      ]}
    >
      <Text
        style={[
          styles.text,
          { color: TIER_COLORS[tier] },
          isLarge && styles.textLarge,
        ]}
      >
        {tier}
      </Text>
    </View>
  );
});

const styles = StyleSheet.create({
  badge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
    borderWidth: 1,
  },
  badgeLarge: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 12,
  },
  text: {
    fontSize: 14,
    fontWeight: "bold",
  },
  textLarge: {
    fontSize: 20,
  },
});
