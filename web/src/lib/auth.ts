// 서버가 HTTP Basic만 받으므로 강사 계정을 탭 수명 동안만 보관한다. 탭을 닫으면 사라진다.
const KEY = "lab.instructor";

export type Credentials = { username: string; basic: string };

export function encodeBasic(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  return btoa(String.fromCharCode(...bytes));
}

export function getCredentials(): Credentials | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Credentials) : null;
  } catch {
    return null;
  }
}

export function setCredentials(credentials: Credentials): void {
  sessionStorage.setItem(KEY, JSON.stringify(credentials));
}

export function clearCredentials(): void {
  sessionStorage.removeItem(KEY);
}
