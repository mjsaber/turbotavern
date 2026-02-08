import React, { useState, useCallback, useEffect } from "react";
import {
  View,
  Text,
  Pressable,
  Switch,
  Platform,
  StyleSheet,
  Alert,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { Ionicons } from "@expo/vector-icons";
import { t } from "../../i18n/zh";
import { useAppStore } from "../../lib/store";
import { heroes } from "../../data/heroes";
import {
  isOverlaySupported,
  checkOverlayPermission,
  requestOverlayPermission,
  checkAudioPermission,
  requestAudioPermission,
  startOverlay,
  stopOverlay,
} from "../../lib/overlay";

export default function OverlayScreen() {
  const [hasPermission, setHasPermission] = useState(false);
  const { overlayActive, setOverlayActive, apiKey } = useAppStore();
  const supported = isOverlaySupported();

  useEffect(() => {
    if (supported) {
      checkOverlayPermission().then(setHasPermission);
    }
  }, [supported]);

  const handleRequestPermission = useCallback(() => {
    requestOverlayPermission();
    // Re-check after a delay (user returns from settings)
    setTimeout(() => {
      checkOverlayPermission().then(setHasPermission);
    }, 1000);
  }, []);

  const handleToggle = useCallback(
    async (value: boolean) => {
      if (value) {
        const permitted = await checkOverlayPermission();
        setHasPermission(permitted);
        if (!permitted) {
          Alert.alert(
            t.overlay.permissionRequired,
            t.overlay.permissionMessage,
            [
              { text: t.chat.cancel, style: "cancel" },
              { text: t.overlay.openSettings, onPress: handleRequestPermission },
            ]
          );
          return;
        }
        const audioPermitted = await checkAudioPermission();
        if (!audioPermitted) {
          requestAudioPermission();
        }
        const heroData = JSON.stringify(
          heroes.map((h) => ({
            name: h.name,
            tier: h.tier,
            heroPower: h.heroPower,
            tips: h.tips,
            armor: h.armor,
          }))
        );
        startOverlay(heroData, apiKey);
        setOverlayActive(true);
      } else {
        stopOverlay();
        setOverlayActive(false);
      }
    },
    [handleRequestPermission, setOverlayActive]
  );

  if (!supported) {
    return (
      <SafeAreaView style={styles.container} edges={["bottom"]}>
        <View style={styles.center}>
          <Ionicons name="phone-portrait-outline" size={64} color="#8b8b9e" />
          <Text style={styles.unsupportedText}>{t.overlay.notSupported}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["bottom"]}>
      <View style={styles.content}>
        <View style={styles.iconContainer}>
          <View style={styles.iconCircle}>
            <Text style={styles.iconText}>B</Text>
          </View>
        </View>

        <Text style={styles.title}>{t.overlay.heroTierList}</Text>
        <Text style={styles.description}>{t.overlay.overlayDescription}</Text>

        <View style={styles.permissionRow}>
          <View style={styles.permissionInfo}>
            <Ionicons
              name={hasPermission ? "checkmark-circle" : "close-circle"}
              size={24}
              color={hasPermission ? "#2ed573" : "#e94560"}
            />
            <Text style={styles.permissionText}>
              {hasPermission ? t.overlay.permissionRequired : t.overlay.permissionRequired}
            </Text>
          </View>
          {!hasPermission && (
            <Pressable
              style={({ pressed }) => [
                styles.permissionButton,
                pressed && styles.buttonPressed,
              ]}
              onPress={handleRequestPermission}
            >
              <Text style={styles.permissionButtonText}>
                {t.overlay.openSettings}
              </Text>
            </Pressable>
          )}
        </View>

        <View style={styles.toggleRow}>
          <View style={styles.toggleInfo}>
            <Ionicons name="layers" size={24} color="#f5a623" />
            <Text style={styles.toggleLabel}>
              {overlayActive ? t.overlay.disable : t.overlay.enable}
            </Text>
          </View>
          <Switch
            value={overlayActive}
            onValueChange={handleToggle}
            trackColor={{ false: "#2a2a4e", true: "#f5a623" + "66" }}
            thumbColor={overlayActive ? "#f5a623" : "#8b8b9e"}
          />
        </View>

        <View style={styles.statusContainer}>
          <View
            style={[
              styles.statusDot,
              { backgroundColor: overlayActive ? "#2ed573" : "#e94560" },
            ]}
          />
          <Text style={styles.statusText}>
            {overlayActive ? t.overlay.serviceRunning : t.overlay.serviceStopped}
          </Text>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#1a1a2e",
  },
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingHorizontal: 32,
  },
  unsupportedText: {
    color: "#8b8b9e",
    fontSize: 16,
    marginTop: 16,
    textAlign: "center",
  },
  content: {
    flex: 1,
    paddingHorizontal: 24,
    paddingTop: 32,
  },
  iconContainer: {
    alignItems: "center",
    marginBottom: 24,
  },
  iconCircle: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: "#16213e",
    borderWidth: 2,
    borderColor: "#f5a623",
    justifyContent: "center",
    alignItems: "center",
  },
  iconText: {
    color: "#f5a623",
    fontSize: 36,
    fontWeight: "bold",
  },
  title: {
    color: "#eaeaea",
    fontSize: 24,
    fontWeight: "bold",
    textAlign: "center",
    marginBottom: 8,
  },
  description: {
    color: "#8b8b9e",
    fontSize: 14,
    textAlign: "center",
    lineHeight: 20,
    marginBottom: 32,
  },
  permissionRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    backgroundColor: "#16213e",
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  permissionInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  permissionText: {
    color: "#eaeaea",
    fontSize: 14,
  },
  permissionButton: {
    backgroundColor: "#e94560",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  buttonPressed: {
    opacity: 0.8,
  },
  permissionButtonText: {
    color: "#ffffff",
    fontSize: 13,
    fontWeight: "600",
  },
  toggleRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    backgroundColor: "#16213e",
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: "#2a2a4e",
  },
  toggleInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  toggleLabel: {
    color: "#eaeaea",
    fontSize: 16,
    fontWeight: "600",
  },
  statusContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
    marginTop: 16,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  statusText: {
    color: "#8b8b9e",
    fontSize: 13,
  },
});
