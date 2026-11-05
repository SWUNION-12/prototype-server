"use client";

import { useSyncExternalStore } from "react";
import { type Credentials, getCredentials } from "./auth";

const subscribe = () => () => {};

let cachedRaw: string | null = null;
let cached: Credentials | null = null;

function snapshot(): Credentials | null {
  const current = getCredentials();
  const raw = current ? JSON.stringify(current) : null;
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cached = current;
  }
  return cached;
}

/** 서버 렌더링 중에는 undefined(아직 모름), 브라우저에서는 계정 또는 null. */
export function useCredentials(): Credentials | null | undefined {
  return useSyncExternalStore(subscribe, snapshot, () => undefined);
}
