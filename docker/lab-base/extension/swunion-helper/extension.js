'use strict';

// "실습 도우미" 확장. 학생 컨테이너의 환경변수 SWUNION_SERVER_URL, SWUNION_IDE_KEY로 수업 서버의 /api/ide/*를
// 부르고, 결과를 사이드바 webview에 그린다. 서버가 꺼졌거나 변수가 없어도 예외나 오류 창으로 IDE를 방해하지 않고
// 패널 안 문구로만 알린다. 키는 로그에도 webview에도 내보내지 않는다.

const crypto = require('crypto');
const vscode = require('vscode');
const { createClient, MSG_NO_ENV } = require('./lib/client');
const { toView } = require('./lib/view-model');
const { createPoller } = require('./lib/poller');
const { panelHtml } = require('./lib/panel-html');

const VIEW_ID = 'swunionHelper.panel';
const COMMAND_ID = 'swunionHelper.sendSelection';
// webview가 보내는 글을 이 길이에서 자른다. 서버도 이 이상은 분석하지 않는다.
const MAX_TEXT = 100000;

const HELP_OK = '도움을 요청했습니다. 원인을 확인하는 동안 잠시 기다려 주세요.';
const ESCALATE_OK = '강사에게 알렸습니다.';
const BAD_STATUS = '상태를 읽지 못했습니다';
const FILLED = "선택한 내용을 넣었습니다. 확인한 뒤 '도움 요청'을 누르세요.";
const NOTHING_SELECTED = '선택한 내용이 없습니다. 오류를 드래그해 선택한 뒤 다시 시도하세요.';
const TERMINAL_UNREADABLE = '터미널에서 선택한 내용을 읽지 못했습니다. 브라우저의 클립보드 읽기를 허용하거나, 복사해서 직접 붙여 넣으세요.';

class HelperPanel {
  constructor(client) {
    this.client = client;
    this.view = null;
    this.state = { view: null, error: client.configured ? null : MSG_NO_ENV, updatedAt: null };
    this.draft = '';
    this.notice = null;
    this.busy = { help: false, escalate: false };
    // 패널이 보이고 서버 정보가 있을 때만 읽는다. 숨겨지면 멈춘다.
    this.poller = createPoller({
      run: () => this.refresh(),
      isActive: () => this.client.configured && !!this.view && this.view.visible,
    });
  }

  resolveWebviewView(view) {
    this.view = view;
    view.webview.options = { enableScripts: true, localResourceRoots: [] };
    view.webview.html = panelHtml(crypto.randomBytes(16).toString('base64'));
    const subs = [
      view.webview.onDidReceiveMessage((m) => this.onMessage(m)),
      view.onDidChangeVisibility(() => {
        if (view.visible) {
          this.poller.now();
        } else {
          this.poller.stop();
        }
      }),
    ];
    view.onDidDispose(() => {
      this.poller.stop();
      for (const d of subs) {
        d.dispose();
      }
      if (this.view === view) {
        this.view = null;
      }
    });
  }

  onMessage(m) {
    if (!m || typeof m !== 'object') {
      return;
    }
    switch (m.type) {
      case 'ready':
        // 숨겼다 다시 열면 webview 문서가 새로 만들어진다. 마지막 상태와 쓰던 글을 다시 넘긴다.
        this.post({
          type: 'init',
          configured: this.client.configured,
          state: this.state,
          draft: this.draft,
          busy: this.busy,
          notice: this.notice,
        });
        this.notice = null;
        if (!this.poller.inFlight && !this.poller.scheduled) {
          this.poller.now();
        }
        break;
      case 'draft':
        if (typeof m.text === 'string') {
          this.draft = m.text.slice(0, MAX_TEXT);
          this.notice = null;
        }
        break;
      case 'help':
        this.help(typeof m.text === 'string' ? m.text.slice(0, MAX_TEXT) : '');
        break;
      case 'escalate':
        this.escalate();
        break;
      default:
        break;
    }
  }

  async refresh() {
    const r = await this.client.status();
    if (r.ok && r.data && typeof r.data === 'object') {
      this.state = { view: toView(r.data), error: null, updatedAt: new Date().toISOString() };
    } else {
      // 마지막으로 받은 내용(최근 도움 요청 등)은 남기고 환경 상태 자리에 오류 문구를 보인다.
      this.state = { view: this.state.view, error: r.ok ? BAD_STATUS : r.message, updatedAt: this.state.updatedAt };
    }
    this.post({ type: 'state', ...this.state });
    return r.ok;
  }

  async help(text) {
    if (this.busy.help) {
      return;
    }
    this.draft = text;
    await this.act('help', () => this.client.help(text), HELP_OK);
  }

  async escalate() {
    if (this.busy.escalate) {
      return;
    }
    await this.act('escalate', () => this.client.escalate(), ESCALATE_OK);
  }

  // 버튼은 요청이 끝날 때까지 막는다. 409 같은 실패는 서버의 {error} 문구를 그대로 보인다.
  async act(action, send, okMessage) {
    this.busy[action] = true;
    this.post({ type: 'busy', action, busy: true });
    try {
      const r = await send();
      this.post({ type: 'result', action, ok: r.ok, message: r.ok ? okMessage : r.message });
      if (r.ok) {
        this.poller.now();
      }
    } catch {
      // client는 reject하지 않는다. 그래도 여기서 새면 버튼이 영영 막히므로 막아 둔다.
    } finally {
      this.busy[action] = false;
      this.post({ type: 'busy', action, busy: false });
    }
  }

  // 입력칸을 채우고 패널을 연다. 보내기는 학생이 확인한 뒤 직접 누른다.
  async fill(text, emptyNotice) {
    if (text) {
      this.draft = text.slice(0, MAX_TEXT);
      this.notice = { text: FILLED, kind: 'muted' };
      this.post({ type: 'fill', text: this.draft, notice: this.notice });
    } else {
      this.notice = { text: emptyNotice, kind: 'warn' };
      this.post({ type: 'notice', ...this.notice });
    }
    await vscode.commands.executeCommand(VIEW_ID + '.focus');
  }

  // 보이지 않거나 사라진 webview에 보내면 거절될 수 있다. 다음 'ready'에서 마지막 값을 다시 보내므로 버린다.
  post(msg) {
    const v = this.view;
    if (!v) {
      return;
    }
    try {
      Promise.resolve(v.webview.postMessage(msg)).catch(() => {});
    } catch {
      // 이미 닫힌 webview
    }
  }

  dispose() {
    this.poller.stop();
  }
}

function editorSelection(editor) {
  if (!editor) {
    return '';
  }
  return editor.selections
    .filter((s) => !s.isEmpty)
    .map((s) => editor.document.getText(s))
    .join('\n');
}

// 터미널 우클릭 메뉴는 그 터미널(또는 목록)을 인자로 넘긴다.
function isTerminalArg(arg) {
  const terminals = vscode.window.terminals;
  if (Array.isArray(arg)) {
    return arg.length > 0 && arg.every((t) => terminals.includes(t));
  }
  return !!arg && terminals.includes(arg);
}

// 확장 API로는 터미널 선택 영역을 읽을 수 없어 복사 명령을 거쳐 클립보드로 읽는다. 터미널 메뉴 항목은
// 선택이 있을 때만 보이므로(terminalTextSelected) 복사 뒤 클립보드에는 그 선택이 들어 있다.
// 브라우저가 클립보드 읽기를 막으면 VS Code가 알림을 띄우고, 알림이 닫힌 뒤 빈 문자열을 돌려준다.
async function terminalSelection() {
  await vscode.commands.executeCommand('workbench.action.terminal.copySelection');
  try {
    // 터미널 전체 선택은 화면 아래 빈 줄까지 딸려 온다.
    return (await vscode.env.clipboard.readText()).replace(/\s+$/, '');
  } catch {
    return '';
  }
}

async function sendSelection(panel, arg) {
  try {
    if (isTerminalArg(arg)) {
      await panel.fill(await terminalSelection(), TERMINAL_UNREADABLE);
      return;
    }
    // 편집기 우클릭은 문서 Uri를 넘기고, 명령 팔레트는 인자가 없다. 둘 다 편집기 선택을 쓴다. 팔레트에서
    // 터미널 선택까지 읽으면 선택이 없을 때 예전 클립보드 내용을 선택으로 착각할 수 있어 터미널은 우클릭 메뉴로만 받는다.
    await panel.fill(editorSelection(vscode.window.activeTextEditor), NOTHING_SELECTED);
  } catch {
    // 선택 보내기가 실패해도 IDE에 오류 창을 띄우지 않는다.
  }
}

function activate(context) {
  const client = createClient({ serverUrl: process.env.SWUNION_SERVER_URL, key: process.env.SWUNION_IDE_KEY });
  const panel = new HelperPanel(client);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(VIEW_ID, panel),
    vscode.commands.registerCommand(COMMAND_ID, (arg) => sendSelection(panel, arg)),
    panel,
  );
}

function deactivate() {}

module.exports = { activate, deactivate };
