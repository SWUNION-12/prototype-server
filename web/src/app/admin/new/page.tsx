"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { IconButton } from "@astryxdesign/core/IconButton";
import { Step, Stepper } from "@astryxdesign/core/Stepper";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { VStack } from "@astryxdesign/core/VStack";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { AppHeader } from "@/components/AppHeader";
import { PlusIcon } from "@/components/icons";
import { ApiError, courses } from "@/lib/api";
import {
  DEFAULT_PYTHON,
  isValidPackage,
  MAX_PACKAGES,
  packageName,
  PYTHON_GROUPS,
  SUGGESTED_PACKAGES,
} from "@/lib/packages";

function PythonStep({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const selectedRow = useRef<HTMLLabelElement>(null);

  // 기본 선택이 목록 중간에 있어 처음 열 때 보이도록 스크롤한다.
  useEffect(() => {
    selectedRow.current?.scrollIntoView({ block: "center" });
  }, []);

  return (
    <Card padding={0}>
      <HStack hAlign="between" vAlign="center" gap={3} paddingBlock={4} paddingInline={6}>
        <Text weight="medium" id="py-label">
          Python 버전
        </Text>
        <Text type="supporting">
          선택: <Text type="code">{value}</Text>
        </Text>
      </HStack>
      <div className="version-scroll" role="radiogroup" aria-labelledby="py-label">
        {PYTHON_GROUPS.map((group) => (
          <div key={group.minor} role="group" aria-label={`Python ${group.minor}`}>
            <div className="version-group-title">Python {group.minor}</div>
            {group.versions.map((version) => (
              <label
                key={version}
                ref={version === value ? selectedRow : undefined}
                className="version-row"
                data-selected={version === value}
              >
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
        ))}
      </div>
    </Card>
  );
}

function DependencyStep({
  selected,
  onChange,
}: {
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const [query, setQuery] = useState("");
  const typed = query.trim();
  const selectedNames = useMemo(() => new Set(selected.map(packageName)), [selected]);

  const suggestions = SUGGESTED_PACKAGES.filter(
    (name) => !typed || name.includes(packageName(typed)),
  );
  const canAddTyped =
    typed !== "" &&
    isValidPackage(typed) &&
    !selected.includes(typed) &&
    !(SUGGESTED_PACKAGES.includes(typed) && selectedNames.has(packageName(typed)));
  const typedIsInvalid = typed !== "" && !isValidPackage(typed) && suggestions.length === 0;

  function toggle(name: string) {
    onChange(
      selectedNames.has(packageName(name))
        ? selected.filter((line) => packageName(line) !== packageName(name))
        : [...selected, name],
    );
  }

  function addTyped() {
    if (!canAddTyped || selected.length >= MAX_PACKAGES) return;
    // 같은 패키지를 버전만 바꿔 넣으면 앞의 것을 대체한다. 서버는 중복을 거부한다.
    onChange([...selected.filter((line) => packageName(line) !== packageName(typed)), typed]);
    setQuery("");
  }

  return (
    <Card padding={0}>
      <div className="deps">
        <VStack className="deps-main" gap={3}>
          <TextInput
            label="라이브러리"
            type="search"
            startIcon="search"
            placeholder="라이브러리 검색 또는 numpy==2.1.0 입력"
            value={query}
            onChange={setQuery}
            onEnter={addTyped}
            status={
              typedIsInvalid
                ? { type: "error", message: "이름, 이름==버전, 이름>=버전 형식으로 입력해 주세요." }
                : undefined
            }
          />
          <VStack gap={0} className="deps-list">
            {suggestions.map((name) => (
              <HStack key={name} paddingBlock={2} paddingInline={2}>
                <CheckboxInput
                  label={name}
                  value={selectedNames.has(packageName(name))}
                  onChange={() => toggle(name)}
                />
              </HStack>
            ))}
            {canAddTyped && !suggestions.includes(typed) && (
              <HStack paddingBlock={2}>
                <Button
                  variant="ghost"
                  label={`"${typed}" 추가`}
                  icon={<Icon icon={PlusIcon} size="sm" />}
                  onClick={addTyped}
                />
              </HStack>
            )}
          </VStack>
        </VStack>
        <VStack className="deps-side" gap={3}>
          <Text weight="medium">
            선택된 의존성 <Text color="secondary">{selected.length}</Text>
          </Text>
          {selected.length === 0 ? (
            <Text type="supporting">선택된 라이브러리가 없습니다.</Text>
          ) : (
            <VStack as="ul" gap={1} paddingInlineStart={0} style={{ listStyle: "none", margin: 0 }}>
              {selected.map((line) => (
                <li key={line}>
                  <Card padding={1}>
                    <HStack hAlign="between" vAlign="center" gap={2} paddingInlineStart={2}>
                      <Text type="code" wordBreak="break-all">
                        {line}
                      </Text>
                      <IconButton
                        variant="ghost"
                        size="sm"
                        label={`${line} 제거`}
                        icon={<Icon icon="close" size="sm" />}
                        onClick={() => onChange(selected.filter((x) => x !== line))}
                      />
                    </HStack>
                  </Card>
                </li>
              ))}
            </VStack>
          )}
        </VStack>
      </div>
    </Card>
  );
}

export default function NewProjectPage() {
  const router = useRouter();
  const [step, setStep] = useState(0);
  const [name, setName] = useState("");
  const [python, setPython] = useState(DEFAULT_PYTHON);
  const [packages, setPackages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const course = await courses.create({
        name: name.trim(),
        pythonVersion: python,
        packages: packages.join("\n"),
      });
      router.push(`/admin/courses/${course.id}/students`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "프로젝트를 만들지 못했습니다.");
      setBusy(false);
    }
  }

  const trimmed = name.trim();
  const nameTooLong = trimmed.length > 100;

  return (
    <VStack className="app-root">
      <AppHeader />
      <VStack as="main" className="page page--narrow" gap={8}>
        <Heading level={1}>프로젝트 설정</Heading>
        <Stepper activeStep={step} label="진행 단계">
          <Step step={0} label="1. 수업명" indicator="none" />
          <Step step={1} label="2. Python 버전" indicator="none" />
          <Step step={2} label="3. 의존성" indicator="none" />
        </Stepper>

        {step === 0 && (
          <Card padding={8}>
            <TextInput
              label="수업명"
              placeholder="예: 파이썬 기초"
              value={name}
              onChange={setName}
              hasAutoFocus
              onEnter={() => {
                if (trimmed && !nameTooLong) setStep(1);
              }}
              status={nameTooLong ? { type: "error", message: "100자 이하로 입력해 주세요." } : undefined}
            />
          </Card>
        )}
        {step === 1 && <PythonStep value={python} onChange={setPython} />}
        {step === 2 && <DependencyStep selected={packages} onChange={setPackages} />}

        {error && <Banner status="error" title="프로젝트를 만들지 못했습니다" description={error} />}

        <HStack hAlign="end" gap={2}>
          {step === 0 ? (
            <Button label="이전" href="/admin" />
          ) : (
            <Button label="이전" onClick={() => setStep(step - 1)} isDisabled={busy} />
          )}
          {step < 2 ? (
            <Button
              variant="primary"
              label="다음"
              onClick={() => setStep(step + 1)}
              isDisabled={step === 0 && (!trimmed || nameTooLong)}
            />
          ) : (
            <Button
              variant="primary"
              label="프로젝트 생성"
              onClick={create}
              isLoading={busy}
              isDisabled={packages.length === 0}
              tooltip={packages.length === 0 ? "라이브러리를 한 개 이상 선택해 주세요." : undefined}
            />
          )}
        </HStack>
      </VStack>
    </VStack>
  );
}
