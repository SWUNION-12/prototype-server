"use client";

import { Button } from "@astryxdesign/core/Button";
import { HStack } from "@astryxdesign/core/HStack";
import { Text } from "@astryxdesign/core/Text";
import { useToast } from "@astryxdesign/core/Toast";
import { useState } from "react";
import { ApiError, auth, LOGIN_PATH } from "@/lib/api";
import { SERVICE_NAME } from "@/lib/display";

// 강사 화면 머리말. 로그아웃하면 세션이 끝나고, 화면에 남은 수업 정보를 지우려고 로그인 화면을 새로 연다.
export function AppHeader() {
  const showToast = useToast();
  const [busy, setBusy] = useState(false);

  async function logout() {
    setBusy(true);
    try {
      await auth.logout();
      window.location.replace(LOGIN_PATH);
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "로그아웃하지 못했습니다.", type: "error" });
      setBusy(false);
    }
  }

  return (
    <HStack as="header" className="app-header" hAlign="between" vAlign="center">
      <Text type="large" weight="semibold">
        {SERVICE_NAME}
      </Text>
      <Button variant="ghost" size="sm" label="로그아웃" isLoading={busy} onClick={logout} />
    </HStack>
  );
}
