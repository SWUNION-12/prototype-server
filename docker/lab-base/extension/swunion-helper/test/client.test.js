'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { EventEmitter } = require('node:events');
const { createClient, MSG_DOWN, MSG_NO_ENV } = require('../lib/client');

const KEY = 'a'.repeat(64);
const URL_BASE = 'http://host.docker.internal:8080';

// 요청마다 behavior(req, cb, call)를 부르는 가짜 http 모듈. 실제 소켓을 열지 않는다.
function fakeHttp(behavior) {
  const calls = [];
  return {
    calls,
    request(url, options, cb) {
      const req = new EventEmitter();
      const call = { url: String(url), method: options.method, headers: options.headers, body: '', destroyed: false };
      calls.push(call);
      req.write = (chunk) => { call.body += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk); };
      req.end = () => { setImmediate(() => behavior(req, cb, call)); };
      req.destroy = () => { call.destroyed = true; };
      return req;
    },
  };
}

function respond(status, body) {
  return (req, cb) => {
    const res = new EventEmitter();
    res.statusCode = status;
    cb(res);
    if (body !== undefined) {
      res.emit('data', Buffer.from(typeof body === 'string' ? body : JSON.stringify(body), 'utf8'));
    }
    res.emit('end');
  };
}

function refuse() {
  return (req) => {
    const e = new Error('connect ECONNREFUSED 127.0.0.1:8080');
    e.code = 'ECONNREFUSED';
    req.emit('error', e);
  };
}

const flush = () => new Promise((r) => setImmediate(r));

test('statusOk: 200 JSON은 {ok:true, data}이고 X-Session-Key 헤더에 키를 싣는다', async () => {
  const data = { name: '홍길동', envStatus: 'OK', issuesText: [] };
  const fake = fakeHttp(respond(200, data));
  const c = createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fake });

  const r = await c.status();

  assert.deepEqual(r, { ok: true, data });
  assert.equal(fake.calls.length, 1);
  assert.equal(fake.calls[0].method, 'GET');
  assert.equal(fake.calls[0].url, URL_BASE + '/api/ide/status');
  assert.equal(fake.calls[0].headers['X-Session-Key'], KEY);
});

test('statusOk: 서버 주소 끝의 /와 경로 접두사를 지킨다', async () => {
  const fake = fakeHttp(respond(200, {}));
  await createClient({ serverUrl: 'http://h:9/lab/', key: KEY, httpModule: fake }).status();
  await createClient({ serverUrl: 'http://h:9', key: KEY, httpModule: fake }).status();
  assert.equal(fake.calls[0].url, 'http://h:9/lab/api/ide/status');
  assert.equal(fake.calls[1].url, 'http://h:9/api/ide/status');
});

test('serverDown: ECONNREFUSED는 reject 없이 "서버에 연결하지 못했습니다"', async () => {
  const c = createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fakeHttp(refuse()) });

  for (const call of [() => c.status(), () => c.help('x'), () => c.escalate()]) {
    const r = await call();
    assert.deepEqual(r, { ok: false, message: '서버에 연결하지 못했습니다' });
  }
  assert.equal(MSG_DOWN, '서버에 연결하지 못했습니다');
});

test('serverDown: 실제 http로 닫힌 포트에 보내도 reject하지 않는다', async () => {
  const srv = http.createServer();
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const port = srv.address().port;
  await new Promise((r) => srv.close(r));

  const r = await createClient({ serverUrl: 'http://127.0.0.1:' + port, key: KEY }).status();

  assert.deepEqual(r, { ok: false, message: MSG_DOWN });
});

test('serverDown: request가 동기로 던져도 reject하지 않는다', async () => {
  const throwing = { request() { throw new TypeError('Invalid character in header content'); } };
  const r = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: throwing }).status();
  assert.deepEqual(r, { ok: false, message: MSG_DOWN });
});

test('noEnv: 서버 주소나 키가 없으면 요청 없이 안내 문구', async () => {
  const expected = { ok: false, message: '이 수업에서는 IDE 도움 패널을 쓸 수 없습니다. 학생 화면을 이용하세요' };
  assert.equal(MSG_NO_ENV, expected.message);
  const cases = [
    { serverUrl: undefined, key: KEY },
    { serverUrl: URL_BASE, key: undefined },
    { serverUrl: '', key: KEY },
    { serverUrl: URL_BASE, key: '  ' },
  ];
  for (const opts of cases) {
    const fake = fakeHttp(respond(200, {}));
    const c = createClient({ ...opts, httpModule: fake });
    assert.equal(c.configured, false);
    assert.deepEqual(await c.status(), expected);
    assert.deepEqual(await c.help('x'), expected);
    assert.deepEqual(await c.escalate(), expected);
    assert.equal(fake.calls.length, 0);
  }
});

test('timeout: 10초 동안 응답이 없으면 "서버에 연결하지 못했습니다"', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fake = fakeHttp(() => { /* 응답하지 않는다 */ });
  const c = createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fake });

  let result = null;
  c.status().then((r) => { result = r; });
  await flush();

  t.mock.timers.tick(9999);
  await flush();
  assert.equal(result, null);

  t.mock.timers.tick(1);
  await flush();
  assert.deepEqual(result, { ok: false, message: MSG_DOWN });
  assert.equal(fake.calls[0].destroyed, true);
});

test('help409: 409 {error}는 그 문구를 그대로 message로', async () => {
  const error = '에이전트가 처리 중입니다. 잠시 후 다시 시도해 주세요';
  const fake = fakeHttp(respond(409, { error }));
  const r = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fake }).help('Traceback ...');

  assert.deepEqual(r, { ok: false, message: error });
  assert.equal(fake.calls[0].method, 'POST');
  assert.equal(fake.calls[0].url, URL_BASE + '/api/ide/help');
  assert.deepEqual(JSON.parse(fake.calls[0].body), { errorText: 'Traceback ...' });
});

test('help: 202 {helpId}는 ok, 빈 입력은 errorText null로 보낸다', async () => {
  const fake = fakeHttp(respond(202, { helpId: 7 }));
  const c = createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fake });

  assert.deepEqual(await c.help('  \n '), { ok: true, data: { helpId: 7 } });
  assert.deepEqual(await c.help(undefined), { ok: true, data: { helpId: 7 } });
  assert.deepEqual(JSON.parse(fake.calls[0].body), { errorText: null });
  assert.deepEqual(JSON.parse(fake.calls[1].body), { errorText: null });
  assert.match(fake.calls[0].headers['Content-Type'], /^application\/json/);
});

test('escalate: 204 본문 없음은 {ok:true, data:null}', async () => {
  const fake = fakeHttp(respond(204));
  const r = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fake }).escalate();
  assert.deepEqual(r, { ok: true, data: null });
  assert.equal(fake.calls[0].method, 'POST');
  assert.equal(fake.calls[0].url, URL_BASE + '/api/ide/escalate');
});

test('401 {error}와 JSON이 아닌 500은 사용자 문구로', async () => {
  const r401 = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fakeHttp(respond(401, { error: '인증 정보가 없습니다' })) }).status();
  assert.deepEqual(r401, { ok: false, message: '인증 정보가 없습니다' });

  const r500 = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fakeHttp(respond(500, '<html>oops</html>')) }).status();
  assert.deepEqual(r500, { ok: false, message: '요청에 실패했습니다 (HTTP 500)' });
});

test('결과 어디에도 키를 싣지 않는다', async () => {
  const behaviors = [respond(200, {}), respond(409, { error: 'busy' }), respond(500, 'x'), refuse()];
  for (const b of behaviors) {
    const r = await createClient({ serverUrl: URL_BASE, key: KEY, httpModule: fakeHttp(b) }).status();
    assert.equal(JSON.stringify(r).includes(KEY), false);
  }
});

test('실제 http 서버: 헤더의 키와 본문이 그대로 닿는다', async () => {
  let seen = null;
  const srv = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      seen = { method: req.method, url: req.url, key: req.headers['x-session-key'], body };
      res.writeHead(202, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ helpId: 3 }));
    });
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  try {
    const c = createClient({ serverUrl: 'http://127.0.0.1:' + srv.address().port, key: KEY });
    const r = await c.help('오류 한 줄');
    assert.deepEqual(r, { ok: true, data: { helpId: 3 } });
    assert.deepEqual(seen, { method: 'POST', url: '/api/ide/help', key: KEY, body: JSON.stringify({ errorText: '오류 한 줄' }) });
  } finally {
    await new Promise((r) => srv.close(r));
  }
});
