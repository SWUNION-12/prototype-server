'use strict';

// GET /api/ide/status 응답을 패널이 그대로 그릴 값으로 바꾼다. 문구는 학생 실습 화면(/lab)과 같다.
// 판단은 여기서 끝내고 webview는 받은 문자열을 textContent로 옮기기만 한다. vscode 모듈을 쓰지 않는다.

const ENV = {
  OK: { kind: 'ok', label: '정상' },
  MISMATCH: { kind: 'mismatch', label: '수업 환경과 다름' },
  REPAIRING: { kind: 'repairing', label: '복구 중' },
};
const ENV_UNKNOWN = { kind: 'unknown', label: '확인 중' };

const HELP_LABEL = {
  PROCESSING: '분석 중',
  EXPLAINED: '설명',
  RESOLVED: '자동으로 해결',
  ESCALATED: '강사에게 전달',
  NO_ACTION: '조치 없음',
};

const ESCALATED_TEXT = '강사에게 전달했습니다';
const PROCESSING_TEXT = '원인을 확인하고 있습니다...';
const ESCALATED_LATER_NOTE = '강사에게도 전달했습니다.';

function has(map, k) {
  return typeof k === 'string' && Object.prototype.hasOwnProperty.call(map, k);
}

// 서버 값은 문자열·숫자·불리언만 글자로 옮긴다. 객체를 String()에 넣으면 던질 수 있다.
function str(v) {
  if (typeof v === 'string') {
    return v;
  }
  if (typeof v === 'number' || typeof v === 'boolean') {
    return String(v);
  }
  return '';
}

function toHelp(h) {
  if (!h || typeof h !== 'object') {
    return null;
  }
  const status = str(h.status);
  const escalated = h.escalated === true || status === 'ESCALATED';
  let explanation = str(h.explanation).trim() === '' ? null : str(h.explanation);
  if (explanation === null && status === 'ESCALATED') {
    explanation = ESCALATED_TEXT;
  } else if (explanation === null && status === 'PROCESSING') {
    explanation = PROCESSING_TEXT;
  }
  return {
    statusLabel: has(HELP_LABEL, status) ? HELP_LABEL[status] : status,
    explanation,
    escalated,
    // 설명이 끝난 뒤 강사에게도 넘어간 경우. ESCALATED 자체는 설명 자리에 이미 그 문구가 있다.
    note: escalated && status !== 'ESCALATED' ? ESCALATED_LATER_NOTE : null,
    pending: status === 'PROCESSING',
    errorType: str(h.errorType) || null,
    createdAt: str(h.createdAt) || null,
  };
}

/**
 * @returns {{name:string, courseName:string, envLabel:string, envKind:'ok'|'mismatch'|'repairing'|'unknown',
 *   issues:string[], needsHelp:boolean, workspaceLost:boolean,
 *   help:{statusLabel:string, explanation:string|null, escalated:boolean, note:string|null, pending:boolean,
 *     errorType:string|null, createdAt:string|null}|null}}
 */
function toView(statusJson) {
  const s = statusJson && typeof statusJson === 'object' ? statusJson : {};
  const env = has(ENV, s.envStatus) ? ENV[s.envStatus] : ENV_UNKNOWN;
  const issues = Array.isArray(s.issuesText) ? s.issuesText.map(str).filter((t) => t !== '') : [];
  return {
    name: str(s.name),
    courseName: str(s.courseName),
    envLabel: env.label,
    envKind: env.kind,
    issues,
    needsHelp: s.needsHelp === true,
    workspaceLost: s.workspaceLost === true,
    help: toHelp(s.latestHelp),
  };
}

module.exports = { toView };
