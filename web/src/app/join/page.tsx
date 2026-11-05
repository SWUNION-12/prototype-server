"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useEffect, useRef, useState } from "react";
import { AppHeader } from "@/components/AppHeader";
import { CellInput } from "@/components/CellInput";
import { Notice } from "@/components/Notice";
import { ApiError, INSTRUCTOR_HOME, student } from "@/lib/api";
import { RESUMED_KEY } from "@/lib/display";

const CODE_LENGTH = 6;
const PIN_LENGTH = 4;
const acceptCode = (raw: string) => raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
const acceptPin = (raw: string) => raw.replace(/\D/g, "");

function CodeStep({ code, onCode, onNext }: { code: string; onCode: (v: string) => void; onNext: () => void }) {
  const ready = code.length === CODE_LENGTH;
  return (
    <form
      className="center-body"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready) onNext();
      }}
    >
      <h1 className="pagetitle">수업 입장</h1>
      <div className="flabel">수업 코드</div>
      <CellInput length={CODE_LENGTH} value={code} onChange={onCode} accept={acceptCode} label="수업 코드" isBig autoFocus />
      <button type="submit" className="btn btn--blue btn--block submit-gap" disabled={!ready}>
        다음
      </button>
    </form>
  );
}

function InfoStep({ code, onBack }: { code: string; onBack: () => void }) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [pin, setPin] = useState("");
  const [pinAgain, setPinAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 요청이 끝나기 전에 다시 보내면 세션 쿠키가 두 번 새로 발급되어 먼저 받은 화면이 404가 된다.
  const joining = useRef(false);

  // 이동한 뒤 뒤로 가기로 돌아오면 브라우저가 이 화면을 요청 중이던 상태 그대로 되살릴 수 있다.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return;
      joining.current = false;
      setBusy(false);
      setPin("");
      setPinAgain("");
    };
    window.addEventListener("pageshow", onShow);
    return () => window.removeEventListener("pageshow", onShow);
  }, []);

  const isComplete = pin.length === PIN_LENGTH && pinAgain.length === PIN_LENGTH;
  const isMismatch = isComplete && pin !== pinAgain;
  const ready = name.trim() !== "" && isComplete && !isMismatch;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || joining.current) return;
    joining.current = true;
    setBusy(true);
    setError(null);
    try {
      const { resumed } = await student.join({ entryCode: code, name: name.trim(), pin });
      setPin("");
      setPinAgain("");
      // 재입장 안내는 주소에 싣지 않고 탭 저장소로 넘긴다(쿠키가 세션을 들고 있어 주소는 늘 /lab).
      try {
        if (resumed) sessionStorage.setItem(RESUMED_KEY, "1");
      } catch {
        // 저장소가 막혀 있으면 안내만 빠진다.
      }
      router.replace("/lab");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "입장하지 못했습니다.");
      // 틀린 PIN을 그대로 두면 같은 값으로 다시 눌러 실패 횟수만 쌓는다.
      if (err instanceof ApiError && err.status === 403) {
        setPin("");
        setPinAgain("");
      }
      joining.current = false;
      setBusy(false);
    }
  }

  return (
    <form className="center-body" onSubmit={submit}>
      <h1 className="pagetitle is-tight">수업 입장</h1>
      <div className="joinclass">
        <span className="mono">{code}</span>
        <button type="button" onClick={onBack}>
          코드 바꾸기
        </button>
      </div>
      {error && <Notice status="error" title={error} />}
      <label className="flabel" htmlFor="student-name" style={{ marginTop: 0 }}>
        이름
      </label>
      <input
        id="student-name"
        className="field"
        autoComplete="name"
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <div className="flabel">PIN 4자리</div>
      <CellInput length={PIN_LENGTH} value={pin} onChange={setPin} accept={acceptPin} label="PIN" isSecret isNumeric />
      <div className="flabel">PIN 확인</div>
      <CellInput
        length={PIN_LENGTH}
        value={pinAgain}
        onChange={setPinAgain}
        accept={acceptPin}
        label="PIN 확인"
        isSecret
        isNumeric
      />
      {isMismatch && <div className="field-error">PIN이 일치하지 않아요.</div>}
      <button type="submit" className="btn btn--blue btn--block submit-gap" disabled={!ready || busy}>
        {busy ? "입장하는 중" : "입장하기"}
      </button>
      {busy && (
        <p className="wait-note" role="status">
          클라우드 환경을 만드는 데 1분 넘게 걸릴 수 있어요. 이 화면을 닫지 말고 기다려 주세요.
        </p>
      )}
    </form>
  );
}

function JoinFlow() {
  const params = useSearchParams();
  const [code, setCode] = useState(() => acceptCode(params.get("code") ?? "").slice(0, CODE_LENGTH));
  const [step, setStep] = useState<"code" | "info">(code.length === CODE_LENGTH ? "info" : "code");

  return step === "code" ? (
    <CodeStep code={code} onCode={setCode} onNext={() => setStep("info")} />
  ) : (
    <InfoStep code={code} onBack={() => setStep("code")} />
  );
}

export default function JoinPage() {
  return (
    <div className="page page--corners">
      <AppHeader
        home="/join"
        actions={
          <Link className="btn btn--sm btn--line" href={INSTRUCTOR_HOME}>
            선생님 로그인
          </Link>
        }
      />
      <main className="center">
        <Suspense>
          <JoinFlow />
        </Suspense>
      </main>
    </div>
  );
}
