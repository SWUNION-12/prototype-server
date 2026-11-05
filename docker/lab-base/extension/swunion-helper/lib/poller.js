'use strict';

// 패널이 보이는 동안만 일정 간격으로 run을 부른다. 요청은 겹치지 않는다: 진행 중에 다시 부르면 끝난 뒤 한 번만 더 돈다.
// 실패가 이어지면 간격을 늘려(최대 maxIntervalMs) 서버가 꺼진 동안 헛된 요청을 줄인다. vscode 모듈을 쓰지 않는다.

/**
 * @param {{run: () => Promise<boolean>, isActive: () => boolean, intervalMs?: number, maxIntervalMs?: number}} opts
 *   run은 성공 여부로 끝나는 Promise. isActive가 false면(패널이 숨겨짐) 다음 차례를 잡지 않는다.
 */
function createPoller(opts) {
  const { run, isActive, intervalMs = 5000, maxIntervalMs = 30000 } = opts;
  let timer = null;
  let inFlight = false;
  let rerun = false;
  let fails = 0;

  function clear() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function schedule() {
    clear();
    if (!isActive()) {
      return;
    }
    const delay = fails === 0 ? intervalMs : Math.min(intervalMs * Math.pow(2, fails), maxIntervalMs);
    timer = setTimeout(now, delay);
  }

  // 바로 한 번 읽는다. 앞선 요청이 진행 중이면 겹쳐 보내지 않고 끝난 뒤 한 번 더 읽는다.
  function now() {
    clear();
    if (!isActive()) {
      return;
    }
    if (inFlight) {
      rerun = true;
      return;
    }
    inFlight = true;
    let p;
    try {
      p = Promise.resolve(run());
    } catch {
      p = Promise.resolve(false);
    }
    p.then(
      (ok) => { fails = ok ? 0 : fails + 1; },
      () => { fails += 1; },
    ).then(() => {
      inFlight = false;
      if (rerun) {
        rerun = false;
        now();
      } else {
        schedule();
      }
    });
  }

  // 숨겨지면 다음 차례를 지운다. 진행 중인 요청은 끝까지 두되 그 뒤 차례는 isActive가 막는다.
  function stop() {
    clear();
    rerun = false;
  }

  return {
    now,
    stop,
    get inFlight() { return inFlight; },
    get scheduled() { return timer !== null; },
  };
}

module.exports = { createPoller };
