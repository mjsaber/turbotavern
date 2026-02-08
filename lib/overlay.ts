import { NativeModules, Platform } from "react-native";

const { OverlayModule } = NativeModules;

export async function checkOverlayPermission(): Promise<boolean> {
  if (Platform.OS !== "android" || !OverlayModule) return false;
  return OverlayModule.checkPermission();
}

export function requestOverlayPermission(): void {
  if (Platform.OS !== "android" || !OverlayModule) return;
  OverlayModule.requestPermission();
}

export async function checkAudioPermission(): Promise<boolean> {
  if (Platform.OS !== "android" || !OverlayModule) return false;
  return OverlayModule.checkAudioPermission();
}

export function requestAudioPermission(): void {
  if (Platform.OS !== "android" || !OverlayModule) return;
  OverlayModule.requestAudioPermission();
}

export function startOverlay(heroDataJson: string, apiKey: string): void {
  if (Platform.OS !== "android" || !OverlayModule) return;
  OverlayModule.startOverlay(heroDataJson, apiKey);
}

export function stopOverlay(): void {
  if (Platform.OS !== "android" || !OverlayModule) return;
  OverlayModule.stopOverlay();
}

export function isOverlaySupported(): boolean {
  return Platform.OS === "android" && OverlayModule != null;
}
