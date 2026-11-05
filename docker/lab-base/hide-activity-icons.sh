#!/bin/sh
# 학생 code-server 활동 막대(왼쪽 아이콘 줄)에서 Source Control, Run and Debug, Extensions, Testing 아이콘 네 개를 숨긴다.
# 설정으로는 숨길 수 없다. git.enabled=false여도 Source Control 컨테이너는 남고, 나머지 셋은 끄는 설정이 없다.
# 그래서 code-server가 내려주는 workbench.html의 </head> 앞에 인라인 <style>을 한 번 넣는다.
# 페이지 CSP가 style-src 'self' 'unsafe-inline'이라 인라인 스타일이 통한다. 이미 있는 inline <style>(시작 실패 안내)도 같은 방식이다.
# 선택자는 라벨 문구(번역됨)가 아니라 활동 막대 항목 안 아이콘 클래스로 잡는다. 클래스 이름은 VS Code 1.144(code-server 4.144.0) 화면에서 확인했다.
# 대상 파일이나 삽입 위치가 없으면 빌드가 멈춘다. code-server를 올렸을 때 패치가 조용히 빠지는 일을 막는다.
# 이미 넣었으면 다시 넣지 않는다.
set -eu

HTML="${WORKBENCH_HTML:-/usr/lib/code-server/lib/vscode/out/vs/code/browser/workbench/workbench.html}"
MARK="swunion-hide-activity-icons"

fail() {
    echo "ERROR: hide-activity-icons: $1" >&2
    exit 1
}

[ -f "$HTML" ] || fail "$HTML 이 없다. code-server를 올린 뒤 경로가 바뀌었는지 확인한다."

if grep -q "$MARK" "$HTML"; then
    echo "hide-activity-icons: 이미 적용됨"
    exit 0
fi

[ "$(grep -c '</head>' "$HTML")" = "1" ] || fail "</head>가 정확히 한 번 나오지 않는다. workbench.html 구조가 바뀌었다."

SNIPPET="$(mktemp)"
OUT="$(mktemp)"
trap 'rm -f "$SNIPPET" "$OUT"' EXIT

# 중괄호를 겹쳐 쓰지 않는다(code-server가 {{...}}를 템플릿 자리표시자로 치환한다).
cat > "$SNIPPET" <<'CSS'
		<!-- swunion-hide-activity-icons: 활동 막대에서 Source Control, Run and Debug, Extensions, Testing 숨김 -->
		<style id="swunion-hide-activity-icons">
			.monaco-workbench .action-item:has(> a.action-label.codicon-source-control-view-icon),
			.monaco-workbench .action-item:has(> a.action-label.codicon-run-view-icon),
			.monaco-workbench .action-item:has(> a.action-label.codicon-extensions-view-icon),
			.monaco-workbench .action-item:has(> a.action-label.codicon-test-view-icon) { display: none !important; }
		</style>
CSS

awk -v snip="$SNIPPET" '
    /<\/head>/ && !done {
        while ((getline line < snip) > 0) print line
        close(snip)
        done = 1
    }
    { print }
' "$HTML" > "$OUT"

grep -q "$MARK" "$OUT" || fail "삽입에 실패했다."
cat "$OUT" > "$HTML"
echo "hide-activity-icons: 적용함 ($HTML)"
