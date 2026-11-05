/**
 * 클립보드에 복사한다. 성공 여부를 돌려준다.
 * Clipboard API는 보안 컨텍스트(localhost, https)에서만 된다. 학생이 http 주소로 들어오면 막히므로
 * execCommand로 한 번 더 시도하고, 그것도 안 되면 false를 돌려줘 화면이 값을 직접 보이게 한다.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 권한이 거부되면 아래 대체 방법으로 넘어간다.
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.opacity = "0";
  document.body.appendChild(area);
  let ok = false;
  try {
    area.select();
    ok = document.execCommand("copy");
  } catch {
    ok = false;
  }
  document.body.removeChild(area);
  return ok;
}
