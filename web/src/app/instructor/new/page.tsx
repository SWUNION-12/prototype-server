"use client";

import { useRouter } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { InstructorHeader } from "@/components/admin/InstructorHeader";
import { CloseIcon, SearchIcon } from "@/components/icons";
import { Notice } from "@/components/Notice";
import { ApiError, courses, INSTRUCTOR_HOME } from "@/lib/api";
import {
  DEFAULT_PYTHON,
  isValidPackage,
  MAX_PACKAGES,
  packageName,
  PYTHON_VERSIONS,
  SUGGESTED_PACKAGES,
} from "@/lib/packages";

const STEPS = ["1. 수업명", "2. Python 버전", "3. 의존성"];

function Summary({ name, python, packages }: { name: string; python: string; packages: string[] }) {
  return (
    <dl className="summary">
      <dt>수업명</dt>
      <dd className={name ? undefined : "is-empty"}>{name || "입력 전"}</dd>
      <dt>Python 버전</dt>
      <dd className="mono">{python}</dd>
      <dt>의존성 {packages.length}개</dt>
      <dd className={packages.length ? "mono" : "mono is-empty"}>{packages.join(", ") || "없음"}</dd>
    </dl>
  );
}

function PythonStep({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="card py-card">
      <div className="py-head">
        <b id="py-label">Python 버전</b>
        <span>
          선택: <span className="mono">{value}</span>
        </span>
      </div>
      <div role="radiogroup" aria-labelledby="py-label">
        {PYTHON_VERSIONS.map((version) => (
          <label key={version} className={version === value ? "py-row is-on" : "py-row"}>
            <input
              type="radio"
              name="python"
              value={version}
              checked={version === value}
              onChange={() => onChange(version)}
            />
            {version}
          </label>
        ))}
      </div>
    </div>
  );
}

function DependencyStep({ selected, onChange }: { selected: string[]; onChange: (next: string[]) => void }) {
  const [query, setQuery] = useState("");
  const [isInvalid, setIsInvalid] = useState(false);
  const typed = query.trim();
  const selectedNames = useMemo(() => new Set(selected.map(packageName)), [selected]);
  const suggestions = SUGGESTED_PACKAGES.filter((name) => !typed || name.includes(packageName(typed)));

  function toggle(name: string) {
    onChange(
      selectedNames.has(packageName(name))
        ? selected.filter((line) => packageName(line) !== packageName(name))
        : [...selected, name],
    );
  }

  function addTyped() {
    if (!typed) return;
    if (!isValidPackage(typed)) {
      setIsInvalid(true);
      return;
    }
    if (selected.length >= MAX_PACKAGES && !selectedNames.has(packageName(typed))) return;
    // 같은 패키지를 버전만 바꿔 넣으면 앞의 것을 대체한다. 서버는 중복을 거부한다.
    onChange([...selected.filter((line) => packageName(line) !== packageName(typed)), typed]);
    setQuery("");
  }

  return (
    <div className="card split">
      <div className="split-main">
        <label className="label" htmlFor="lib-search">
          라이브러리
        </label>
        <div className="search lib-search">
          <SearchIcon />
          <input
            id="lib-search"
            placeholder="라이브러리 검색 또는 numpy==2.1.0 입력"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setIsInvalid(false);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addTyped();
              }
            }}
          />
        </div>
        {isInvalid && <div className="field-error">이름, 이름==버전, 이름&gt;=버전 형식으로 입력해 주세요.</div>}
        <div className="lib-list">
          {suggestions.map((name) => (
            <label key={name} className="lib-row">
              <input
                type="checkbox"
                checked={selectedNames.has(packageName(name))}
                onChange={() => toggle(name)}
              />
              {name}
            </label>
          ))}
        </div>
      </div>
      <div className="split-side">
        <div className="label">
          선택된 의존성 <span>{selected.length}</span>
        </div>
        {selected.map((line) => (
          <div key={line} className="dep">
            {line}
            <button
              type="button"
              aria-label={`${line} 빼기`}
              onClick={() => onChange(selected.filter((item) => item !== line))}
            >
              <CloseIcon />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

function NewCourse() {
  const router = useRouter();
  const [step, setStep] = useState(0);
  const [name, setName] = useState("");
  const [python, setPython] = useState(DEFAULT_PYTHON);
  const [packages, setPackages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = name.trim();
  const nameTooLong = trimmed.length > 100;
  const canLeaveName = trimmed !== "" && !nameTooLong;

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const course = await courses.create({ name: trimmed, pythonVersion: python, packages: packages.join("\n") });
      router.push(`/instructor/course?id=${course.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "프로젝트를 만들지 못했습니다.");
      setBusy(false);
    }
  }

  return (
    <>
      <InstructorHeader />
      <main className="form">
        <h1 className="pagetitle">프로젝트 설정</h1>
        <ol className="steps" aria-label="진행 단계">
          {STEPS.map((label, index) => (
            <li
              key={label}
              className={index < step ? "is-done" : index === step ? "is-active" : undefined}
              aria-current={index === step ? "step" : undefined}
            >
              {label}
            </li>
          ))}
        </ol>

        {step === 0 && (
          <div className="card split">
            <div className="split-main">
              <label className="label" htmlFor="course-name">
                수업명
              </label>
              <input
                id="course-name"
                className="field"
                placeholder="예: 파이썬 기초"
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && canLeaveName) setStep(1);
                }}
              />
              {nameTooLong && <div className="field-error">100자 이하로 입력해 주세요.</div>}
            </div>
            <div className="split-side">
              <div className="label">설정 내용</div>
              <Summary name={trimmed} python={python} packages={packages} />
            </div>
          </div>
        )}
        {step === 1 && <PythonStep value={python} onChange={setPython} />}
        {step === 2 && <DependencyStep selected={packages} onChange={setPackages} />}

        {error && <Notice status="error" title="프로젝트를 만들지 못했습니다">{error}</Notice>}

        <div className="form-foot">
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => (step === 0 ? router.push(INSTRUCTOR_HOME) : setStep(step - 1))}
          >
            이전
          </button>
          {step < 2 ? (
            <button
              type="button"
              className="btn btn--blue"
              disabled={step === 0 && !canLeaveName}
              onClick={() => setStep(step + 1)}
            >
              다음
            </button>
          ) : (
            <button
              type="button"
              className="btn btn--blue"
              disabled={busy || packages.length === 0}
              title={packages.length === 0 ? "라이브러리를 한 개 이상 선택해 주세요." : undefined}
              onClick={create}
            >
              {busy ? "만드는 중" : "프로젝트 생성"}
            </button>
          )}
        </div>
      </main>
    </>
  );
}

export default function NewCoursePage() {
  return (
    <div className="page page--nav page--corners">
      <Suspense>
        <NewCourse />
      </Suspense>
    </div>
  );
}
