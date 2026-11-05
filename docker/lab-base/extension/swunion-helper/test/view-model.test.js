'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toView } = require('../lib/view-model');

function status(extra) {
  return {
    name: '홍길동',
    courseName: '파이썬 기초',
    envStatus: 'OK',
    issues: [],
    issuesText: [],
    needsHelp: false,
    workspaceLost: false,
    latestHelp: null,
    ...extra,
  };
}

test('viewOk: OK는 정상, 이슈 없음, 최근 요청 없음', () => {
  const v = toView(status());
  assert.equal(v.envKind, 'ok');
  assert.equal(v.envLabel, '정상');
  assert.deepEqual(v.issues, []);
  assert.equal(v.help, null);
  assert.equal(v.name, '홍길동');
  assert.equal(v.courseName, '파이썬 기초');
});

test('viewRepairing: REPAIRING은 envKind repairing, envLabel "복구 중"', () => {
  const v = toView(status({ envStatus: 'REPAIRING' }));
  assert.equal(v.envKind, 'repairing');
  assert.equal(v.envLabel, '복구 중');
});

test('viewMismatch: MISMATCH와 issuesText 2개는 issues 2개 그대로', () => {
  const texts = ['six 없음 (기준 1.16.0)', 'requests 버전 다름 (2.31.0, 기준 2.32.3)'];
  const v = toView(status({ envStatus: 'MISMATCH', issues: [{ type: 'MISSING' }, { type: 'VERSION_MISMATCH' }], issuesText: texts }));
  assert.equal(v.envKind, 'mismatch');
  assert.equal(v.envLabel, '수업 환경과 다름');
  assert.deepEqual(v.issues, texts);
});

test('viewUnknown: UNKNOWN이나 모르는 값, 빈 입력은 확인 중', () => {
  for (const s of [status({ envStatus: 'UNKNOWN' }), status({ envStatus: 'WHATEVER' }), status({ envStatus: null }), null, 'x']) {
    const v = toView(s);
    assert.equal(v.envKind, 'unknown');
    assert.equal(v.envLabel, '확인 중');
    assert.deepEqual(v.issues, []);
    assert.equal(v.help, null);
  }
});

test('viewEscalatedNoExplanation: ESCALATED이고 explanation null이면 "강사에게 전달했습니다"', () => {
  const v = toView(status({
    needsHelp: true,
    latestHelp: { id: 1, status: 'ESCALATED', errorType: null, explanation: null, escalated: true, createdAt: '2026-11-05T01:02:03Z' },
  }));
  assert.equal(v.help.statusLabel, '강사에게 전달');
  assert.equal(v.help.explanation, '강사에게 전달했습니다');
  assert.equal(v.help.escalated, true);
  assert.equal(v.help.note, null);
  assert.equal(v.needsHelp, true);
});

test('viewHelpLabels: 학생 화면과 같은 처리 상태 문구', () => {
  const labels = { PROCESSING: '분석 중', EXPLAINED: '설명', RESOLVED: '자동으로 해결', ESCALATED: '강사에게 전달', NO_ACTION: '조치 없음' };
  for (const [st, label] of Object.entries(labels)) {
    const v = toView(status({ latestHelp: { status: st, explanation: null, escalated: false } }));
    assert.equal(v.help.statusLabel, label);
  }
});

test('viewProcessing: 설명이 아직 없으면 확인 중 안내, 설명이 오면 그 설명', () => {
  const p = toView(status({ latestHelp: { status: 'PROCESSING', explanation: null, escalated: false } }));
  assert.equal(p.help.explanation, '원인을 확인하고 있습니다...');
  assert.equal(p.help.pending, true);

  const e = toView(status({ latestHelp: { status: 'EXPLAINED', errorType: 'ModuleNotFoundError', explanation: 'six가 없습니다', escalated: false } }));
  assert.equal(e.help.explanation, 'six가 없습니다');
  assert.equal(e.help.errorType, 'ModuleNotFoundError');
  assert.equal(e.help.pending, false);
});

test('viewEscalatedLater: 설명 뒤에 강사에게도 넘어가면 덧붙임 문구', () => {
  const v = toView(status({ latestHelp: { status: 'EXPLAINED', explanation: '설명', escalated: true } }));
  assert.equal(v.help.escalated, true);
  assert.equal(v.help.note, '강사에게도 전달했습니다.');
});

test('viewText: 서버 값은 문자열로만 옮긴다', () => {
  const v = toView(status({ name: { toString: null }, issuesText: ['<b>x</b>', null, 3] }));
  assert.equal(typeof v.name, 'string');
  assert.deepEqual(v.issues, ['<b>x</b>', '3']);
});
