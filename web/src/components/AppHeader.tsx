"use client";

import { Text } from "@astryxdesign/core/Text";
import { SERVICE_NAME } from "@/lib/display";

export function AppHeader() {
  return (
    <header className="app-header">
      <Text type="large" weight="semibold">
        {SERVICE_NAME}
      </Text>
    </header>
  );
}
