import React, { memo, useCallback } from "react";
import { View, Text, Pressable, StyleSheet } from "react-native";
import { type Hero, TIER_COLORS } from "../data/heroes";
import { TierBadge } from "./TierBadge";
import { t } from "../i18n/zh";

interface HeroCardProps {
  hero: Hero;
  onPress: (hero: Hero) => void;
}

export const HeroCard = memo(function HeroCard({ hero, onPress }: HeroCardProps) {
  const handlePress = useCallback(() => {
    onPress(hero);
  }, [hero, onPress]);

  return (
    <Pressable
      onPress={handlePress}
      style={({ pressed }) => [
        styles.card,
        pressed && styles.cardPressed,
      ]}
    >
      <View style={styles.header}>
        <View style={styles.nameContainer}>
          <Text style={styles.name}>{hero.name}</Text>
          <Text style={styles.heroPower}>{hero.heroPower}</Text>
        </View>
        <TierBadge tier={hero.tier} />
      </View>
      <Text style={styles.description} numberOfLines={2}>
        {hero.heroPowerDescription}
      </Text>
      {hero.armor > 0 && (
        <View style={styles.armorContainer}>
          <Text style={styles.armorText}>{t.components.armorLabel(hero.armor)}</Text>
        </View>
      )}
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
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    marginBottom: 8,
  },
  nameContainer: {
    flex: 1,
    marginRight: 12,
  },
  name: {
    color: "#eaeaea",
    fontSize: 18,
    fontWeight: "bold",
  },
  heroPower: {
    color: "#f5a623",
    fontSize: 14,
    marginTop: 2,
  },
  description: {
    color: "#8b8b9e",
    fontSize: 13,
    lineHeight: 18,
  },
  armorContainer: {
    marginTop: 8,
    alignSelf: "flex-start",
    backgroundColor: "#2a2a4e",
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
  },
  armorText: {
    color: "#a4a4a4",
    fontSize: 12,
  },
});
