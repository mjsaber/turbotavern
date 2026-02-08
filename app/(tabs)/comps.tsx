import React, { useState, useCallback } from "react";
import {
  View,
  Text,
  FlatList,
  Modal,
  ScrollView,
  Pressable,
  StyleSheet,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { compositions, type Composition } from "../../data/comps";
import { CompCard } from "../../components/CompCard";
import { t } from "../../i18n/zh";

export default function CompsScreen() {
  const [selectedComp, setSelectedComp] = useState<Composition | null>(null);

  const handleCompPress = useCallback((comp: Composition) => {
    setSelectedComp(comp);
  }, []);

  const handleCloseModal = useCallback(() => {
    setSelectedComp(null);
  }, []);

  const renderComp = useCallback(
    ({ item }: { item: Composition }) => (
      <CompCard comp={item} onPress={handleCompPress} />
    ),
    [handleCompPress]
  );

  const keyExtractor = useCallback((item: Composition) => item.id, []);

  return (
    <SafeAreaView style={styles.container} edges={["bottom"]}>
      <FlatList
        data={compositions}
        renderItem={renderComp}
        keyExtractor={keyExtractor}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text style={styles.subtitle}>
              {t.comps.subtitle}
            </Text>
          </View>
        }
      />

      <Modal
        visible={!!selectedComp}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={handleCloseModal}
      >
        {selectedComp && (
          <View style={styles.modalContainer}>
            <View style={styles.modalHeader}>
              <Pressable onPress={handleCloseModal} style={styles.closeButton}>
                <Text style={styles.closeText}>{t.comps.close}</Text>
              </Pressable>
            </View>
            <ScrollView style={styles.modalContent}>
              <Text style={styles.modalName}>{selectedComp.name}</Text>
              <View style={styles.metaRow}>
                <View style={styles.tribeBadge}>
                  <Text style={styles.tribeText}>{selectedComp.tribe}</Text>
                </View>
                <Text style={styles.difficultyText}>
                  {t.comps.difficulty(selectedComp.difficulty)}
                </Text>
              </View>

              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.comps.description}</Text>
                <Text style={styles.sectionText}>{selectedComp.description}</Text>
              </View>

              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.comps.keyMinions}</Text>
                {selectedComp.keyMinions.map((minion) => (
                  <View key={minion} style={styles.minionItem}>
                    <Text style={styles.minionDot}>•</Text>
                    <Text style={styles.minionName}>{minion}</Text>
                  </View>
                ))}
              </View>

              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.comps.supports}</Text>
                <View style={styles.chipRow}>
                  {selectedComp.supports.map((support) => (
                    <View key={support} style={styles.chip}>
                      <Text style={styles.chipText}>{support}</Text>
                    </View>
                  ))}
                </View>
              </View>

              <View style={styles.section}>
                <Text style={styles.sectionTitle}>{t.comps.strategy}</Text>
                <Text style={styles.sectionText}>{selectedComp.strategy}</Text>
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
  list: {
    paddingBottom: 20,
  },
  header: {
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  subtitle: {
    color: "#8b8b9e",
    fontSize: 14,
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
  modalName: {
    color: "#eaeaea",
    fontSize: 28,
    fontWeight: "bold",
    marginBottom: 8,
  },
  metaRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    marginBottom: 24,
  },
  tribeBadge: {
    backgroundColor: "#2a2a4e",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 6,
  },
  tribeText: {
    color: "#f5a623",
    fontSize: 13,
    fontWeight: "600",
  },
  difficultyText: {
    color: "#8b8b9e",
    fontSize: 13,
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
  minionItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 4,
  },
  minionDot: {
    color: "#f5a623",
    marginRight: 8,
    fontSize: 16,
  },
  minionName: {
    color: "#eaeaea",
    fontSize: 16,
  },
  chipRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
  },
  chip: {
    backgroundColor: "#2a2a4e",
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
  },
  chipText: {
    color: "#eaeaea",
    fontSize: 13,
  },
});
