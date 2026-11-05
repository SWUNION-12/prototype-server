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

// 서버 규칙(^3\.[0-9]{1,2}(\.[0-9]{1,2})?$)에 맞는 값 중 실제로 확인한 3.8~3.14만 보인다.
// 패치 번호는 uv가 그 계열의 최신판으로 고르므로 고르지 않는다.
export const PYTHON_VERSIONS = ["3.14", "3.13", "3.12", "3.11", "3.10", "3.9", "3.8"];

export const DEFAULT_PYTHON = "3.12";
