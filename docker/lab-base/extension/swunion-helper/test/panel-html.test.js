'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { panelHtml } = require('../lib/panel-html');

const NONCE = 'q1w2e3r4t5y6u7i8o9p0aA==';

test('CSP: default-src none, 스크립트·스타일은 nonce만', () => {
  const html = panelHtml(NONCE);
  const csp = html.match(/http-equiv="Content-Security-Policy" content="([^"]+)"/)[1];
  assert.equal(csp, "default-src 'none'; style-src 'nonce-" + NONCE + "'; script-src 'nonce-" + NONCE + "';");
  assert.equal((html.match(/<script nonce="/g) || []).length, 1);
  assert.equal((html.match(/<script/g) || []).length, 1);
  assert.equal((html.match(/<style nonce="/g) || []).length, 1);
  assert.equal((html.match(/<style/g) || []).length, 1);
});

test('외부 리소스와 HTML 삽입 경로가 없다', () => {
  const html = panelHtml(NONCE);
  assert.doesNotMatch(html, /https?:\/\//);
  assert.doesNotMatch(html, /\ssrc=|\shref=|@import|url\(/);
  assert.doesNotMatch(html, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|new Function/);
  assert.doesNotMatch(html, /\son[a-z]+=/i);
  assert.doesNotMatch(html, /\sstyle="/);
});

test('webview 스크립트가 문법 오류 없이 읽힌다', () => {
  const html = panelHtml(NONCE);
  const script = html.match(/<script nonce="[^"]+">([\s\S]*?)<\/script>/)[1];
  assert.doesNotThrow(() => new vm.Script(script));
});

test('이상한 nonce는 받지 않는다', () => {
  for (const bad of [undefined, '', 'short', 'abc"onload="x"aaaaaaaaaa', "aaaaaaaaaaaaaaaa'"]) {
    assert.throws(() => panelHtml(bad));
  }
});
