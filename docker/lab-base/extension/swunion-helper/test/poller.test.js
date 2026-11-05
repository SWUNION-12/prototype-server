'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createPoller } = require('../lib/poller');

const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
};

// 끝내는 시점을 시험이 정하는 run.
function controlledRun() {
  const pending = [];
  let calls = 0;
  return {
    get calls() { return calls; },
    run: () => {
      calls++;
      return new Promise((resolve) => pending.push(resolve));
    },
    finish: (ok = true) => pending.shift()(ok),
  };
}

test('보이는 동안 5초마다 읽는다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const r = controlledRun();
  const p = createPoller({ run: r.run, isActive: () => true });

  p.now();
  assert.equal(r.calls, 1);
  r.finish(true);
  await flush();
  assert.equal(p.scheduled, true);

  t.mock.timers.tick(4999);
  assert.equal(r.calls, 1);
  t.mock.timers.tick(1);
  assert.equal(r.calls, 2);
});

test('진행 중에 다시 불러도 겹쳐 보내지 않고, 끝난 뒤 한 번만 더 읽는다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const r = controlledRun();
  const p = createPoller({ run: r.run, isActive: () => true });

  p.now();
  p.now();
  p.now();
  assert.equal(r.calls, 1);
  assert.equal(p.inFlight, true);

  r.finish(true);
  await flush();
  assert.equal(r.calls, 2);

  r.finish(true);
  await flush();
  assert.equal(r.calls, 2);
  assert.equal(p.scheduled, true);
});

test('숨겨지면 다음 차례를 잡지 않는다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let visible = true;
  const r = controlledRun();
  const p = createPoller({ run: r.run, isActive: () => visible });

  p.now();
  visible = false;
  p.stop();
  r.finish(true);
  await flush();
  assert.equal(p.scheduled, false);

  t.mock.timers.tick(60000);
  assert.equal(r.calls, 1);

  p.now();
  assert.equal(r.calls, 1);
});

test('실패가 이어지면 간격을 늘리고 성공하면 5초로 돌아온다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const r = controlledRun();
  const p = createPoller({ run: r.run, isActive: () => true });

  p.now();
  r.finish(false);
  await flush();
  t.mock.timers.tick(9999);
  assert.equal(r.calls, 1);
  t.mock.timers.tick(1);
  assert.equal(r.calls, 2);

  r.finish(true);
  await flush();
  t.mock.timers.tick(5000);
  assert.equal(r.calls, 3);
});

test('run이 던지거나 reject해도 멈추지 않는다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let calls = 0;
  const p = createPoller({
    run: () => {
      calls++;
      if (calls === 1) {
        throw new Error('boom');
      }
      return Promise.reject(new Error('boom'));
    },
    isActive: () => true,
  });

  p.now();
  await flush();
  t.mock.timers.tick(10000);
  assert.equal(calls, 2);
  await flush();
  assert.equal(p.scheduled, true);
});
