'use strict';

// `node --test test/`처럼 폴더를 넘기면 Node 22 이후의 테스트 러너는 폴더 안을 찾지 않고 폴더를 모듈로 실행한다.
// 그때 이 파일이 실행되어 같은 폴더의 *.test.js를 불러온다. 기본 패턴(`node --test`)으로 이 파일이 따로 실행될 때는
// 각 테스트 파일도 따로 실행되므로 아무것도 하지 않는다(같은 테스트가 두 번 돌지 않게).
const fs = require('node:fs');
const path = require('node:path');

function real(p) {
  try {
    return fs.realpathSync(p);
  } catch {
    return path.resolve(p);
  }
}

if (process.argv[1] && real(process.argv[1]) === real(__dirname)) {
  for (const f of fs.readdirSync(__dirname).filter((n) => n.endsWith('.test.js')).sort()) {
    require(path.join(__dirname, f));
  }
}
