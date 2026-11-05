'use strict';

// "실습 도우미" webview 문서. 스크립트와 스타일은 이 문서 안에 nonce를 붙여 넣고, CSP로 그 밖의 모든 것
// (외부 리소스, 인라인 이벤트 속성, eval)을 막는다. 서버 문자열은 textContent로만 옮긴다(innerHTML을 쓰지 않는다).
// 서버는 webview가 아니라 확장(Node 쪽)이 부른다. webview와는 postMessage로만 주고받는다.

const STYLE = `
body {
  padding: 0 12px 16px;
  color: var(--vscode-foreground);
  font-family: var(--vscode-font-family);
  font-size: var(--vscode-font-size);
  line-height: 1.45;
}
[hidden] { display: none !important; }
h2 {
  margin: 16px 0 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--vscode-sideBarSectionHeader-foreground, var(--vscode-foreground));
}
p { margin: 4px 0; }
.muted { color: var(--vscode-descriptionForeground); }
.small { font-size: 11px; }
.wrap { white-space: pre-wrap; overflow-wrap: anywhere; }
.ok { color: var(--vscode-testing-iconPassed, var(--vscode-charts-green)); }
.mismatch, .err { color: var(--vscode-errorForeground); }
.repairing, .warn { color: var(--vscode-editorWarning-foreground, var(--vscode-charts-yellow)); }
.unknown { color: var(--vscode-descriptionForeground); }
.env { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.env .label { overflow-wrap: anywhere; }
.dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
ul { margin: 6px 0 0; padding-left: 18px; }
li { margin: 2px 0; overflow-wrap: anywhere; }
label { display: block; margin-bottom: 4px; }
textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 96px;
  margin: 0 0 8px;
  padding: 4px 6px;
  resize: vertical;
  color: var(--vscode-input-foreground);
  background: var(--vscode-input-background);
  border: 1px solid var(--vscode-input-border, transparent);
  border-radius: 2px;
  font-family: var(--vscode-editor-font-family, monospace);
  font-size: var(--vscode-editor-font-size, 12px);
}
textarea::placeholder { color: var(--vscode-input-placeholderForeground); }
textarea:focus { outline: 1px solid var(--vscode-focusBorder); outline-offset: -1px; }
button {
  display: block;
  box-sizing: border-box;
  width: 100%;
  padding: 4px 8px;
  border: 1px solid var(--vscode-button-border, transparent);
  border-radius: 2px;
  color: var(--vscode-button-foreground);
  background: var(--vscode-button-background);
  font-family: inherit;
  font-size: inherit;
  line-height: 18px;
  cursor: pointer;
}
button:hover:not(:disabled) { background: var(--vscode-button-hoverBackground); }
button:focus-visible { outline: 1px solid var(--vscode-focusBorder); outline-offset: 2px; }
button.secondary { color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground); }
button.secondary:hover:not(:disabled) { background: var(--vscode-button-secondaryHoverBackground); }
button:disabled { opacity: 0.5; cursor: default; }
.box {
  margin-top: 10px;
  padding: 6px 8px;
  border-left: 2px solid var(--vscode-textBlockQuote-border, var(--vscode-focusBorder));
  background: var(--vscode-textBlockQuote-background, transparent);
}
.box > div + div { margin-top: 4px; }
`;

// webview 안에서 도는 코드. 확장이 보낸 메시지를 화면에 옮기고, 버튼과 입력을 확장에 알린다.
const SCRIPT = `
(function () {
  'use strict';
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const KIND = { ok: 'ok', mismatch: 'mismatch', repairing: 'repairing', unknown: 'unknown' };
  const MSG_CLASS = { ok: 'ok', err: 'err', warn: 'warn', muted: 'muted' };
  let configured = true;
  const busy = { help: false, escalate: false };

  function str(v) { return typeof v === 'string' ? v : ''; }

  function setMsg(el, text, kind) {
    el.textContent = str(text);
    el.className = 'small ' + (MSG_CLASS[kind] || 'muted');
    el.hidden = str(text) === '';
  }

  function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? '' : d.toLocaleTimeString('ko-KR');
  }

  function syncButtons() {
    $('helpBtn').disabled = !configured || busy.help;
    $('escalateBtn').disabled = !configured || busy.escalate;
    $('errorText').disabled = !configured;
  }

  function renderLatest(h) {
    const box = $('latest');
    if (!h || typeof h !== 'object') { box.hidden = true; return; }
    box.hidden = false;
    $('latestLabel').textContent = '최근 도움 요청: ' + str(h.statusLabel);
    const when = fmtTime(h.createdAt);
    $('latestTime').textContent = when ? '(' + when + ')' : '';
    const type = $('latestType');
    type.textContent = h.errorType ? '오류 유형: ' + str(h.errorType) : '';
    type.hidden = !h.errorType;
    const text = $('latestText');
    text.textContent = str(h.explanation);
    text.className = 'wrap' + (h.pending ? ' muted' : '');
    text.hidden = !h.explanation;
    const note = $('latestNote');
    note.textContent = str(h.note);
    note.hidden = !h.note;
  }

  function renderState(st) {
    const v = st && st.view && typeof st.view === 'object' ? st.view : null;
    const who = v ? [str(v.name), str(v.courseName)].filter(Boolean).join(' · ') : '';
    $('who').textContent = who;
    $('who').hidden = who === '';

    const issues = $('issues');
    if (st && st.error) {
      $('env').className = 'env err';
      $('envLabel').textContent = str(st.error);
      issues.replaceChildren();
      issues.hidden = true;
      $('workspaceLost').hidden = true;
    } else if (v) {
      $('env').className = 'env ' + (KIND[v.envKind] || 'unknown');
      $('envLabel').textContent = str(v.envLabel);
      const items = (Array.isArray(v.issues) ? v.issues : []).map(function (t) {
        const li = document.createElement('li');
        li.textContent = str(t);
        return li;
      });
      issues.replaceChildren.apply(issues, items);
      issues.hidden = items.length === 0;
      $('workspaceLost').hidden = v.workspaceLost !== true;
    }
    const at = fmtTime(st && st.updatedAt);
    $('updated').textContent = at ? '5초마다 갱신 · 마지막 갱신 ' + at : '';
    $('updated').hidden = !at || !!(st && st.error);
    $('needsHelp').hidden = !(v && v.needsHelp === true);
    renderLatest(v ? v.help : null);
  }

  function fill(text) {
    const ta = $('errorText');
    ta.value = str(text);
    ta.focus();
    ta.setSelectionRange(0, 0);
    ta.scrollTop = 0;
  }

  window.addEventListener('message', function (event) {
    const m = event.data;
    if (!m || typeof m !== 'object') return;
    switch (m.type) {
      case 'init':
        configured = m.configured !== false;
        busy.help = m.busy && m.busy.help === true;
        busy.escalate = m.busy && m.busy.escalate === true;
        if (typeof m.draft === 'string' && $('errorText').value === '') $('errorText').value = m.draft;
        renderState(m.state);
        if (m.notice) setMsg($('helpMsg'), m.notice.text, m.notice.kind);
        syncButtons();
        break;
      case 'state':
        renderState(m);
        break;
      case 'busy':
        if (m.action === 'help' || m.action === 'escalate') busy[m.action] = m.busy === true;
        syncButtons();
        break;
      case 'result':
        if (m.action === 'help') setMsg($('helpMsg'), m.message, m.ok ? 'ok' : 'err');
        if (m.action === 'escalate') setMsg($('escalateMsg'), m.message, m.ok ? 'ok' : 'err');
        break;
      case 'fill':
        fill(m.text);
        if (m.notice) setMsg($('helpMsg'), m.notice.text, m.notice.kind);
        break;
      case 'notice':
        setMsg($('helpMsg'), m.text, m.kind);
        break;
    }
  });

  let draftTimer = null;
  function sendDraft() {
    clearTimeout(draftTimer);
    draftTimer = null;
    vscode.postMessage({ type: 'draft', text: $('errorText').value });
  }
  // 패널이 숨겨지면 문서가 다시 만들어진다. 쓰던 내용을 확장에 맡겨 두었다가 다시 보일 때 돌려받는다.
  $('errorText').addEventListener('input', function () {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(sendDraft, 300);
  });
  $('errorText').addEventListener('blur', sendDraft);

  $('helpBtn').addEventListener('click', function () {
    if (!configured || busy.help) return;
    busy.help = true;
    syncButtons();
    setMsg($('helpMsg'), '요청을 보내는 중...', 'muted');
    vscode.postMessage({ type: 'help', text: $('errorText').value });
  });

  $('escalateBtn').addEventListener('click', function () {
    if (!configured || busy.escalate) return;
    busy.escalate = true;
    syncButtons();
    setMsg($('escalateMsg'), '강사를 부르는 중...', 'muted');
    vscode.postMessage({ type: 'escalate' });
  });

  vscode.postMessage({ type: 'ready' });
})();
`;

const BODY = `
<p id="who" class="muted small" hidden></p>

<h2>환경 상태</h2>
<div id="env" class="env unknown" role="status"><span class="dot"></span><span id="envLabel" class="label">불러오는 중...</span></div>
<ul id="issues" hidden></ul>
<p id="workspaceLost" class="warn small" hidden>이전 작업 공간을 찾지 못해 새로 만들었습니다.</p>
<p id="updated" class="muted small" hidden></p>

<h2>도움 요청</h2>
<p id="needsHelp" class="warn small" hidden>강사에게 도움 요청이 전달되어 있습니다.</p>
<label for="errorText" class="muted small">막힌 오류를 붙여 넣으세요 (선택). 편집기나 터미널에서 선택한 뒤 우클릭 메뉴로도 넣을 수 있습니다.</label>
<textarea id="errorText" rows="6" spellcheck="false" placeholder="오류 메시지"></textarea>
<button id="helpBtn" type="button">도움 요청</button>
<p id="helpMsg" class="small" role="status" hidden></p>
<div id="latest" class="box" hidden>
  <div><strong id="latestLabel"></strong> <span id="latestTime" class="muted small"></span></div>
  <div id="latestType" class="muted small" hidden></div>
  <div id="latestText" class="wrap" hidden></div>
  <div id="latestNote" class="warn small" hidden></div>
</div>

<h2>강사 호출</h2>
<p class="muted small">직접 와서 도와 달라고 강사를 부릅니다.</p>
<button id="escalateBtn" type="button" class="secondary">강사 호출</button>
<p id="escalateMsg" class="small" role="status" hidden></p>
`;

const NONCE = /^[A-Za-z0-9+/_-]{16,}={0,2}$/;

/** @param {string} nonce 요청마다 새로 만든 무작위 값(base64). CSP와 script·style 태그에 같은 값을 쓴다. */
function panelHtml(nonce) {
  if (typeof nonce !== 'string' || !NONCE.test(nonce)) {
    throw new Error('invalid nonce');
  }
  const csp = "default-src 'none'; style-src 'nonce-" + nonce + "'; script-src 'nonce-" + nonce + "';";
  return '<!DOCTYPE html>\n<html lang="ko">\n<head>\n<meta charset="UTF-8">\n'
    + '<meta http-equiv="Content-Security-Policy" content="' + csp + '">\n'
    + '<meta name="viewport" content="width=device-width, initial-scale=1.0">\n'
    + '<title>실습 도우미</title>\n'
    + '<style nonce="' + nonce + '">' + STYLE + '</style>\n'
    + '</head>\n<body>\n' + BODY
    + '<script nonce="' + nonce + '">' + SCRIPT + '</script>\n'
    + '</body>\n</html>\n';
}

module.exports = { panelHtml };
