import { View, type ViewProps } from "react-native";
import { SafeAreaView, type Edge } from "react-native-safe-area-context";

export function ScreenContainer({ children, edges = ["top", "left", "right"], style, ...props }: ViewProps & { edges?: Edge[] }) {
  return <View style={[{ flex: 1 }, style]} {...props}><SafeAreaView edges={edges} style={{ flex: 1 }}>{children}</SafeAreaView></View>;
}
