import * as Haptics from "expo-haptics";
import { Platform } from "react-native";

function run(effect: () => Promise<void>) { if (Platform.OS !== "web") void effect().catch(() => undefined); }
export function acknowledgeNativeAction() { run(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light)); }
export function confirmNativeAction() { run(() => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success)); }
