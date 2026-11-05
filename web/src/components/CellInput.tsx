"use client";

import { type ClipboardEvent, type KeyboardEvent, useEffect, useRef } from "react";

type Props = {
  length: number;
  value: string;
  onChange: (next: string) => void;
  accept: (raw: string) => string;
  label: string;
  isSecret?: boolean;
  isBig?: boolean;
  isNumeric?: boolean;
  autoFocus?: boolean;
};

/** 한 글자씩 칸으로 나뉜 입력. 값은 앞에서부터 채워지는 문자열 하나다. */
export function CellInput({ length, value, onChange, accept, label, isSecret, isBig, isNumeric, autoFocus }: Props) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  // 다시 그려지기 전에 다음 입력이 와도 이전 값에 덮어쓰지 않도록 최신 값을 따로 쥔다.
  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
  }, [value]);

  const focus = (index: number) => refs.current[Math.min(index, length - 1)]?.focus();
  const commit = (next: string) => {
    latest.current = next;
    onChange(next);
  };

  function typed(index: number, raw: string) {
    const current = latest.current;
    const existing = current[index] ?? "";
    let chars = accept(raw);
    // 칸에 있던 글자가 입력값에 함께 오면 뺀다. 자동 완성처럼 여러 글자가 한 번에 오면 뒤 칸까지 이어서 채운다.
    if (existing && chars.length > 1) {
      if (chars.startsWith(existing)) chars = chars.slice(existing.length);
      else if (chars.endsWith(existing)) chars = chars.slice(0, -existing.length);
    }
    if (!chars) return;
    const at = Math.min(index, current.length);
    commit(`${current.slice(0, at)}${chars}${current.slice(at + chars.length)}`.slice(0, length));
    focus(at + chars.length);
  }

  function keyDown(index: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== "Backspace") return;
    e.preventDefault();
    const current = latest.current;
    if (current[index]) {
      commit(current.slice(0, index) + current.slice(index + 1));
      focus(index);
    } else if (index > 0) {
      commit(current.slice(0, index - 1) + current.slice(index));
      focus(index - 1);
    }
  }

  function paste(e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault();
    const next = accept(e.clipboardData.getData("text")).slice(0, length);
    commit(next);
    focus(next.length);
  }

  return (
    <div className={isBig ? "cells cells--big" : "cells"} role="group" aria-label={label}>
      {Array.from({ length }, (_, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          className="cell"
          type={isSecret ? "password" : "text"}
          inputMode={isNumeric ? "numeric" : undefined}
          autoCapitalize="characters"
          autoComplete="off"
          spellCheck={false}
          autoFocus={autoFocus && i === 0}
          aria-label={`${label} ${i + 1}번째`}
          value={value[i] ?? ""}
          onChange={(e) => typed(i, e.target.value)}
          onKeyDown={(e) => keyDown(i, e)}
          onPaste={paste}
          onFocus={(e) => e.currentTarget.select()}
        />
      ))}
    </div>
  );
}
