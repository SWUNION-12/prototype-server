"use client";

import { useEffect, useState } from "react";
import { CopyIcon } from "@/components/icons";
import { Notice } from "@/components/Notice";
import { useToast } from "@/components/Toast";
import { ApiError, CODE_ZIP_URL, student } from "@/lib/api";
import { copyText } from "@/lib/clipboard";
import type { InstallInfo, Os, VerifyResult } from "@/lib/types";

// 서버가 준 주소는 http(s)나 같은 사이트 경로일 때만 링크로 쓴다. 브라우저는 `\`를 `/`로 읽어 `/\evil.com`이
// 다른 사이트 주소가 되므로 둘째 글자로 `/`뿐 아니라 `\`도 막는다.
const isSafeHref = (u: string) => /^https?:\/\/\S+$/i.test(u) || /^\/[^\s/\\]\S*$/.test(u);

const message = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback);

function Command({ code }: { code: string }) {
  const showToast = useToast();

  async function copy() {
    const ok = await copyText(code);
    showToast(
      ok ? { body: "명령을 복사했습니다." } : { body: "복사하지 못했습니다. 명령을 직접 선택해 복사해 주세요.", type: "error" },
    );
  }

  return (
    <div className="cmd">
      <button type="button" aria-label="명령 복사" onClick={copy}>
        <CopyIcon />
      </button>
      {code}
    </div>
  );
}

function Steps({ os, info }: { os: Os; info: InstallInfo }) {
  const [diagText, setDiagText] = useState("");
  const [diagBusy, setDiagBusy] = useState(false);
  const [diagError, setDiagError] = useState<string | null>(null);
  const [diagnosed, setDiagnosed] = useState(false);

  const [verifyText, setVerifyText] = useState("");
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [result, setResult] = useState<VerifyResult | null>(null);

  async function submitDiagnostics() {
    setDiagBusy(true);
    setDiagError(null);
    try {
      await student.diagnostics(os, diagText);
      setDiagnosed(true);
    } catch (e) {
      setDiagError(message(e, "출력을 확인하지 못했습니다."));
    } finally {
      setDiagBusy(false);
    }
  }

  async function submitVerify() {
    setVerifyBusy(true);
    setVerifyError(null);
    setResult(null);
    try {
      setResult(await student.verify(verifyText));
    } catch (e) {
      setVerifyError(message(e, "검증 결과를 제출하지 못했습니다."));
    } finally {
      setVerifyBusy(false);
    }
  }

  const terminal = os === "windows" ? "PowerShell" : "터미널";

  return (
    <>
      <p className="step-title">1. 내 컴퓨터 환경 확인</p>
      <p className="small">{terminal}에서 실행하고, 출력된 세 줄을 붙여넣어 주세요.</p>
      <Command code={info.diagnoseCommand} />
      <textarea
        aria-label="출력 결과"
        placeholder={"os=...\narch=...\npython=..."}
        spellCheck={false}
        value={diagText}
        onChange={(e) => setDiagText(e.target.value)}
      />
      {diagError && <p className="panel-note panel-note--error">{diagError}</p>}
      {diagnosed && <p className="panel-note">확인했어요. 이제 설치를 진행해 주세요.</p>}
      <div className="panel-foot">
        <button type="button" className="btn" disabled={diagBusy || !diagText.trim()} onClick={submitDiagnostics}>
          확인
        </button>
      </div>

      {diagnosed && (
        <>
          <div className="divider" />
          <p className="step-title">2. 설치</p>
          <p className="small">{terminal}에서 실행하면 수업과 같은 환경이 설치되고 검증 결과가 출력돼요.</p>
          <Command code={info.installCommand} />
          {isSafeHref(info.scriptUrl) ? (
            <a className="link-underline" href={info.scriptUrl} target="_blank" rel="noopener noreferrer">
              실행 전에 스크립트 내용 보기
            </a>
          ) : (
            <p className="small">스크립트 주소를 확인할 수 없습니다. 선생님에게 알려 주세요.</p>
          )}

          <div className="divider" />
          <p className="step-title">3. 검증 결과 제출</p>
          <textarea
            aria-label="검증 결과"
            placeholder="설치가 끝나면 출력된 내용을 붙여넣어 주세요"
            spellCheck={false}
            value={verifyText}
            onChange={(e) => setVerifyText(e.target.value)}
          />
          {verifyError && <p className="panel-note panel-note--error">{verifyError}</p>}
          <div className="panel-foot">
            <button type="button" className="btn" disabled={verifyBusy || !verifyText.trim()} onClick={submitVerify}>
              제출
            </button>
          </div>
          {result && (
            <Notice status={result.ok ? "success" : "error"} title={result.ok ? "설치가 끝났어요" : "설치 검증에 실패했어요"}>
              {result.message}
              {result.failed.length > 0 && <div className="mono">{result.failed.join(", ")}</div>}
            </Notice>
          )}
        </>
      )}
    </>
  );
}

export function LocalPanel() {
  const [os, setOs] = useState<Os>("macos");
  const [loaded, setLoaded] = useState<{ os: Os; info: InstallInfo } | null>(null);
  const [error, setError] = useState<{ os: Os; text: string } | null>(null);
  // 다운로드 코드는 받을 때마다 새로 만들어지고 10분 뒤 끝난다. "다시 받기"는 이 값을 올려 다시 요청한다.
  const [round, setRound] = useState(0);

  useEffect(() => {
    let live = true;
    student
      .localLink(os)
      .then((next) => {
        if (!live) return;
        setError(null);
        setLoaded({ os, info: next });
      })
      .catch((e: unknown) => {
        if (live) setError({ os, text: message(e, "설치 안내를 불러오지 못했습니다.") });
      });
    return () => {
      live = false;
    };
  }, [os, round]);

  const info = loaded?.os === os ? loaded.info : null;
  const errorText = error?.os === os ? error.text : null;

  return (
    <>
      <div className="seg seg--fill" role="group" aria-label="내 컴퓨터 운영체제">
        <button type="button" aria-pressed={os === "macos"} onClick={() => setOs("macos")}>
          macOS
        </button>
        <button type="button" aria-pressed={os === "windows"} onClick={() => setOs("windows")}>
          Windows
        </button>
      </div>

      {errorText && (
        <Notice status="error" title={errorText}>
          {!info && (
            <div>
              <button type="button" className="btn btn--pill" style={{ marginTop: 8 }} onClick={() => setRound((n) => n + 1)}>
                다시 받기
              </button>
            </div>
          )}
        </Notice>
      )}
      {!info && !errorText && (
        <div className="state">
          <span className="spinner" role="status" aria-label="불러오는 중" />
        </div>
      )}
      {/* 운영체제를 바꾸면 붙여넣은 내용이 맞지 않으므로 처음부터 다시 받는다. */}
      {info && (
        <>
          <div className="relink">
            10분 동안 쓸 수 있습니다. 지나면 다시 받으세요.
            <button type="button" className="btn btn--pill" onClick={() => setRound((n) => n + 1)}>
              다시 받기
            </button>
          </div>
          <Steps key={os} os={os} info={info} />
        </>
      )}

      <div className="divider" />
      <p className="step-title">내 코드 내려받기</p>
      <p className="small" style={{ marginBottom: 12 }}>
        클라우드에서 작성한 코드를 zip으로 받아요. 가상환경과 캐시는 빠져요.
      </p>
      {/* 파일 주소는 Next 링크가 미리 받지 않게 일반 앵커로 둔다. */}
      <a className="btn btn--full" href={CODE_ZIP_URL}>
        코드 zip 다운로드
      </a>
    </>
  );
}
