import { Tabs } from "expo-router";
import { Ionicons } from "@expo/vector-icons";
import { t } from "../../i18n/zh";

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerStyle: {
          backgroundColor: "#16213e",
        },
        headerTintColor: "#eaeaea",
        headerTitleStyle: {
          fontWeight: "bold",
        },
        tabBarStyle: {
          backgroundColor: "#16213e",
          borderTopColor: "#2a2a4e",
          borderTopWidth: 1,
          paddingBottom: 8,
          paddingTop: 8,
          height: 60,
        },
        tabBarActiveTintColor: "#f5a623",
        tabBarInactiveTintColor: "#8b8b9e",
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: t.tabs.heroes,
          headerTitle: t.tabs.heroTierList,
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="shield" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="comps"
        options={{
          title: t.tabs.comps,
          headerTitle: t.tabs.compositions,
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="grid" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="chat"
        options={{
          title: t.tabs.askBob,
          headerTitle: t.tabs.askBob,
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="chatbubbles" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="overlay"
        options={{
          title: t.tabs.overlay,
          headerTitle: t.tabs.overlay,
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="layers" size={size} color={color} />
          ),
        }}
      />
    </Tabs>
  );
}
