'use strict';

// 수업 서버의 /api/ide/* 를 부르는 클라이언트. vscode 모듈을 쓰지 않아 호스트에서 node --test로 시험한다.
// 모든 호출은 {ok:true, data} 또는 {ok:false, message}로 끝나고 절대 reject하지 않는다. 서버가 꺼져 있어도
// 확장이 예외로 IDE를 방해하지 않게 하려는 것이다. 키는 요청 헤더에만 싣고 결과·오류 문구에는 넣지 않는다.

const MSG_DOWN = '서버에 연결하지 못했습니다';
const MSG_NO_ENV = '이 수업에서는 IDE 도움 패널을 쓸 수 없습니다. 학생 화면을 이용하세요';
const TIMEOUT_MS = 10000;
// 상태 응답은 몇 KB다. 이상한 응답이 메모리를 채우지 않게 끊는다.
const MAX_BODY_BYTES = 1024 * 1024;
// 서버도 끝에서 이만큼만 분석한다(HelpService.MAX_EXTRACT_INPUT). 더 보내 봐야 버려진다.
const MAX_ERROR_TEXT = 100000;

const DOWN = Object.freeze({ ok: false, message: MSG_DOWN });
const NO_ENV = Object.freeze({ ok: false, message: MSG_NO_ENV });

function present(v) {
  return typeof v === 'string' && v.trim() !== '';
}

// 끝에 /를 붙여야 'api/ide/status'를 붙일 때 서버 주소의 경로 접두사가 남는다.
function baseUrl(serverUrl) {
  try {
    const u = new URL(serverUrl.trim().replace(/\/*$/, '/'));
    return u.protocol === 'http:' || u.protocol === 'https:' ? u : null;
  } catch {
    return null;
  }
}

function isObject(v) {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

// 엔드포인트마다 서버가 실제로 주는 성공 응답만 성공으로 친다. 중간 프록시나 공용 와이파이 로그인 화면이
// 200 HTML을 돌려줘도 학생이 "요청했습니다"를 보고 기다리지 않게 하려는 것이다.
const ACCEPT = {
  // 200 상태 JSON 객체
  status: (code, data) => code === 200 && isObject(data),
  // 202 {helpId}
  help: (code, data) => code === 202 && isObject(data) && data.helpId !== undefined && data.helpId !== null,
  // 204, 또는 본문 없는 2xx
  escalate: (code, data, text) => code === 204 || text.trim() === '',
};

function failed(statusCode) {
  return '요청에 실패했습니다 (HTTP ' + statusCode + ')';
}

function toResult(statusCode, text, accept) {
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (statusCode >= 200 && statusCode < 300) {
    return accept(statusCode, data, text) ? { ok: true, data } : { ok: false, message: failed(statusCode) };
  }
  const error = isObject(data) && typeof data.error === 'string' ? data.error.trim() : '';
  return { ok: false, message: error || failed(statusCode) };
}

/** 글이 너무 길면 끝부분을 남긴다. 오류는 보통 마지막 줄에 있고, 서버도 끝에서부터 읽는다. */
function keepTail(text, max = MAX_ERROR_TEXT) {
  return text.length > max ? text.slice(-max) : text;
}

function errorTextBody(errorText) {
  return { errorText: present(errorText) ? keepTail(errorText) : null };
}

/**
 * @param {{serverUrl?: string, key?: string, httpModule?: {request: Function}, timeoutMs?: number}} opts
 *   httpModule은 시험용 가짜 모듈. 없으면 주소에 맞춰 내장 http/https를 쓴다.
 */
function createClient(opts) {
  const { serverUrl, key, httpModule, timeoutMs = TIMEOUT_MS } = opts || {};
  const configured = present(serverUrl) && present(key);
  const base = configured ? baseUrl(serverUrl) : null;

  function call(method, path, accept, body) {
    if (!configured) {
      return Promise.resolve({ ...NO_ENV });
    }
    if (!base) {
      return Promise.resolve({ ...DOWN });
    }
    return new Promise((resolve) => {
      let settled = false;
      let timer = null;
      let req = null;
      const finish = (result) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve(result);
      };
      const abort = () => {
        finish({ ...DOWN });
        try {
          if (req) {
            req.destroy();
          }
        } catch {
          // 이미 끝난 요청이다.
        }
      };
      try {
        const url = new URL(path, base);
        const mod = httpModule || require(url.protocol === 'https:' ? 'https' : 'http');
        const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), 'utf8');
        const headers = { 'X-Session-Key': key, Accept: 'application/json' };
        if (payload) {
          headers['Content-Type'] = 'application/json; charset=utf-8';
          headers['Content-Length'] = payload.length;
        } else if (method !== 'GET') {
          headers['Content-Length'] = 0;
        }
        // agent:false로 매번 새 연결을 연다. 5초 간격 폴링이 서버가 막 닫은 keep-alive 소켓을 다시 쓰다
        // ECONNRESET으로 가끔 "연결하지 못했습니다"가 깜빡이는 일을 피한다.
        req = mod.request(url, { method, headers, agent: false }, (res) => {
          const chunks = [];
          let size = 0;
          res.on('data', (c) => {
            const b = Buffer.isBuffer(c) ? c : Buffer.from(String(c), 'utf8');
            size += b.length;
            if (size > MAX_BODY_BYTES) {
              abort();
              return;
            }
            chunks.push(b);
          });
          res.on('end', () => finish(toResult(res.statusCode, Buffer.concat(chunks).toString('utf8'), accept)));
          res.on('error', abort);
          res.on('aborted', abort);
        });
        req.on('error', abort);
        timer = setTimeout(abort, timeoutMs);
        if (payload) {
          req.write(payload);
        }
        req.end();
      } catch {
        // 잘못된 헤더 값 같은 동기 오류. 원인 문구에 키가 섞일 수 있어 그대로 옮기지 않는다.
        abort();
      }
    });
  }

  return {
    configured,
    status: () => call('GET', 'api/ide/status', ACCEPT.status),
    help: (errorText) => call('POST', 'api/ide/help', ACCEPT.help, errorTextBody(errorText)),
    escalate: () => call('POST', 'api/ide/escalate', ACCEPT.escalate),
  };
}

module.exports = { createClient, keepTail, MSG_DOWN, MSG_NO_ENV, TIMEOUT_MS, MAX_ERROR_TEXT };
