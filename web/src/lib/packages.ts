// 서버 SpecValidator와 같은 규칙. 서버가 최종 검사하지만 잘못된 줄을 미리 막는다.
const NAME = "[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?";
const VERSION = "[0-9]+(\\.[0-9]+){0,3}([a-z]+[0-9]*)?";
const LINE = new RegExp(`^(${NAME})((==|>=)${VERSION})?$`);

export const MAX_PACKAGES = 50;
export const SUGGESTED_PACKAGES = ["torch", "numpy", "matplotlib", "pandas", "scikit-learn", "requests"];

export function isValidPackage(line: string): boolean {
  return LINE.test(line);
}

/** numpy==2.1.0 → numpy. 대소문자와 구분자를 PEP 503 규칙으로 맞춘다. */
export function packageName(line: string): string {
  return line
    .split(/==|>=/)[0]
    .toLowerCase()
    .replace(/[-_.]+/g, "-");
}

// uv 0.11.32가 설치할 수 있다고 알려 준 CPython 버전. 서버에 조회 API가 없어 시안 값을 그대로 쓴다.
const PATCHES: Record<string, number[]> = {
  "3.14": [6, 5, 4, 3, 2, 1, 0],
  "3.13": [14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
  "3.12": [13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
  "3.11": [15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 1],
  "3.10": [20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 9, 8, 7, 6, 5, 4, 3, 2, 0],
};

export const PYTHON_GROUPS = Object.entries(PATCHES).map(([minor, patches]) => ({
  minor,
  versions: patches.map((p) => `${minor}.${p}`),
}));

export const DEFAULT_PYTHON = "3.12.13";
