"use client";

import { InternationalizationProvider } from "@astryxdesign/core/i18n";
import { LinkProvider } from "@astryxdesign/core/Link";
import koKR from "@astryxdesign/core/locales/ko-KR.generated.js";
import { Theme } from "@astryxdesign/core/theme";
import NextLink from "next/link";
import type { ReactNode } from "react";
import { defaultThemeSlug, themes } from "@/astryx-themes";

const messages = { "ko-KR": koKR };

export function Providers({ children }: { children: ReactNode }) {
  return (
    <InternationalizationProvider locale="ko-KR" messages={messages}>
      <Theme theme={themes[defaultThemeSlug]} mode="light">
        <LinkProvider component={NextLink}>{children}</LinkProvider>
      </Theme>
    </InternationalizationProvider>
  );
}
