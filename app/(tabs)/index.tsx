import React, { useState, useCallback, useMemo } from "react";
import {
  View,
  Text,
  FlatList,
  TextInput,
  Pressable,
  Modal,
  ScrollView,
  StyleSheet,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { heroes, searchHeroes, type Hero, type Tier, TIER_COLORS } from "../../data/heroes";
import { HeroCard } from "../../components/HeroCard";
import { TierBadge } from "../../components/TierBadge";
import { t } from "../../i18n/zh";

const TIERS: Tier[] = ["S", "A", "B", "C", "D"];

export default function HeroesScreen() {
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedTier, setSelectedTier] = useState<Tier | null>(null);
  const [selectedHero, setSelectedHero] = useState<Hero | null>(null);

  const filteredHeroes = useMemo(() => {
    let result = searchQuery ? searchHeroes(searchQuery) : heroes;
    if (selectedTier) {
      result = result.filter((h) => h.tier === selectedTier);
    }
    return result;
  }, [searchQuery, selectedTier]);

  const handleHeroPress = useCallback((hero: Hero) => {
    setSelectedHero(hero);
  }, []);

  const handleCloseModal = useCallback(() => {
    setSelectedHero(null);
  }, []);

  const renderHero = useCallback(
    ({ item }: { item: Hero }) => <HeroCard hero={item} onPress={handleHeroPress} />,
    [handleHeroPress]
  );

  const keyExtractor = useCallback((item: Hero) => item.id, []);

  return (
    <SafeAreaView style={styles.container} edges={["bottom"]}>
      <View style={styles.searchContainer}>
        <TextInput
          style={styles.searchInput}
          placeholder={t.heroes.searchPlaceholder}
          placeholderTextColor="#8b8b9e"
          value={searchQuery}
          onChangeText={setSearchQuery}
          autoCorrect={false}
        />
      </View>

      <View style={styles.tierFilters}>
        <Pressable
          style={[styles.tierChip, !selectedTier && styles.tierChipActive]}
          onPress={() => setSelectedTier(null)}
        >
          <Text style={[styles.tierChipText, !selectedTier && styles.tierChipTextActive]}>
            {t.heroes.all}
          </Text>
        </Pressable>
        {TIERS.map((tier) => (
          <Pressable
            key={tier}
            style={[
              styles.tierChip,
              selectedTier === tier && {
                backgroundColor: TIER_COLORS[tier] + "33",
                borderColor: TIER_COLORS[tier],
              },
            ]}
            onPress={() => setSelectedTier(selectedTier === tier ? null : tier)}
          >
            <Text
              style={[
                styles.tierChipText,
                selectedTier === tier && { color: TIER_COLORS[tier] },
              ]}
            >
              {tier}
            </Text>
          </Pressable>
        ))}
      </View>

      <FlatList
        data={filteredHeroes}
        renderItem={renderHero}
        keyExtractor={keyExtractor}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
      />

      <Modal
        visible={!!selectedHero}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={handleCloseModal}
      >
        {selectedHero && (
          <View style={styles.modalContainer}>
            <View style={styles.modalHeader}>
              <Pressable onPress={handleCloseModal} style={styles.closeButton}>
                <Text style={styles.closeText}>{t.heroes.close}</Text>
              </Pressable>
            </View>
            <ScrollView style={styles.modalContent}>
              <View style={styles.modalTitleRow}>
                <Text style={styles.modalName}>{selectedHero.name}</Text>
                <TierBadge tier={selectedHero.tier} size="large" />
              </View>
              <Text style={styles.modalHeroPower}>{selectedHero.heroPower}</Text>
              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.heroes.heroPower}</Text>
                <Text style={styles.sectionText}>{selectedHero.heroPowerDescription}</Text>
              </View>
              {selectedHero.armor > 0 && (
                <View style={styles.section}>
                  <Text style={styles.sectionTitle}>{t.heroes.armor}</Text>
                  <Text style={styles.sectionText}>{t.heroes.armorValue(selectedHero.armor)}</Text>
                </View>
              )}
              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.heroes.strategyTips}</Text>
                <Text style={styles.sectionText}>{selectedHero.tips}</Text>
              </View>
            </ScrollView>
          </View>
        )}
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#1a1a2e",
  },
  searchContainer: {
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  searchInput: {
    backgroundColor: "#16213e",
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    color: "#eaeaea",
    fontSize: 16,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  tierFilters: {
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingBottom: 8,
    gap: 8,
  },
  tierChip: {
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 8,
    backgroundColor: "#16213e",
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  tierChipActive: {
    backgroundColor: "#f5a623" + "33",
    borderColor: "#f5a623",
  },
  tierChipText: {
    color: "#8b8b9e",
    fontWeight: "600",
    fontSize: 14,
  },
  tierChipTextActive: {
    color: "#f5a623",
  },
  list: {
    paddingBottom: 20,
  },
  modalContainer: {
    flex: 1,
    backgroundColor: "#1a1a2e",
  },
  modalHeader: {
    flexDirection: "row",
    justifyContent: "flex-end",
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 8,
  },
  closeButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  closeText: {
    color: "#f5a623",
    fontSize: 16,
    fontWeight: "600",
  },
  modalContent: {
    flex: 1,
    paddingHorizontal: 24,
  },
  modalTitleRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 4,
  },
  modalName: {
    color: "#eaeaea",
    fontSize: 28,
    fontWeight: "bold",
    flex: 1,
    marginRight: 12,
  },
  modalHeroPower: {
    color: "#f5a623",
    fontSize: 18,
    marginBottom: 24,
  },
  section: {
    marginBottom: 20,
  },
  sectionTitle: {
    color: "#f5a623",
    fontSize: 14,
    fontWeight: "bold",
    textTransform: "uppercase",
    letterSpacing: 1,
    marginBottom: 8,
  },
  sectionText: {
    color: "#eaeaea",
    fontSize: 16,
    lineHeight: 24,
  },
});
