/**
 * WebRTC 通话状态管理
 *
 * 通话状态机：
 *   IDLE → RINGING（用户发起 call，等管理员接听）
 *   RINGING → CONNECTING（管理员 accept，开始 SDP 交换）
 *   CONNECTING → ACTIVE（ICE 连通，对话中）
 *   任何状态 → ENDED（任一方 hangup 或超时）
 *
 * 状态变更会推送给双方：
 *   {type:"call_state", state:"ringing"|"connecting"|"active"|"ended", callId, reason?}
 */

const calls = new Map(); // callId → CallState

const CALL_RING_TIMEOUT_MS = 35 * 1000; // 响铃超时 35s
const CALL_CONNECT_TIMEOUT_MS = 30 * 1000; // SDP/ICE 建立超时

/**
 * 发起通话
 * @param {string} fromIdentity "user:<phone>"
 * @param {string} toIdentity  "admin:<id>" 或 "user:<phone>"
 * @param {object} ws 发起方 ws 引用
 * @returns {object} callState
 */
function startCall(fromIdentity, toIdentity, ws) {
  // 同一对组合已有通话则拒绝
  for (const c of calls.values()) {
    if ((c.from === fromIdentity && c.to === toIdentity) ||
        (c.from === toIdentity && c.to === fromIdentity)) {
      return { error: '已有进行中的通话' };
    }
  }

  const callId = 'call_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
  const state = {
    callId,
    from: fromIdentity,
    to: toIdentity,
    state: 'ringing',
    startedAt: Date.now(),
    timer: null
  };

  // 响铃超时
  state.timer = setTimeout(() => {
    if (calls.get(callId)?.state === 'ringing') {
      endCall(callId, 'timeout');
    } else if (calls.get(callId)?.state === 'connecting') {
      endCall(callId, 'connect_timeout');
    }
  }, CALL_RING_TIMEOUT_MS + CALL_CONNECT_TIMEOUT_MS);

  calls.set(callId, state);
  return state;
}

function acceptCall(callId) {
  const c = calls.get(callId);
  if (!c) return { error: '通话不存在' };
  if (c.state !== 'ringing') return { error: '通话状态异常' };
  c.state = 'connecting';
  return c;
}

function markActive(callId) {
  const c = calls.get(callId);
  if (!c) return { error: '通话不存在' };
  if (c.state !== 'connecting' && c.state !== 'active') {
    c.state = 'active';
  }
  return c;
}

function endCall(callId, reason = 'normal') {
  const c = calls.get(callId);
  if (!c) return null;
  if (c.timer) clearTimeout(c.timer);
  c.state = 'ended';
  calls.delete(callId);
  return { ...c, reason };
}

function getCall(callId) {
  return calls.get(callId);
}

function getActiveCallBetween(a, b) {
  for (const c of calls.values()) {
    if ((c.from === a && c.to === b) || (c.from === b && c.to === a)) {
      if (c.state === 'ringing' || c.state === 'connecting' || c.state === 'active') {
        return c;
      }
    }
  }
  return null;
}

module.exports = {
  startCall, acceptCall, markActive, endCall, getCall, getActiveCallBetween,
  CALL_RING_TIMEOUT_MS
};
