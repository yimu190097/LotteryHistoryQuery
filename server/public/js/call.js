/**
 * 管理员 Web 端 - WebRTC 通话模块（被叫）
 *
 * 被叫流程：
 *   1. 收到 call 信令 → 弹出接听弹窗
 *   2. 点接听 → 发 accept → 等对方 offer
 *   3. 收到 offer → 创建 PeerConnection + setRemoteDescription + createAnswer
 *   4. answer 发回 → 等 ICE 完成
 *   5. 收到对方 candidate → addIceCandidate
 *   6. oniceconnectionstatechange=connected → 显示通话中
 *   7. 挂断 → 发 hangup + close PeerConnection
 */

const CallState = {
  pc: null,
  localStream: null,
  remoteAudio: null,
  callId: null,
  callerIdentity: null,
  ringing: false,
  connected: false,
  startTime: 0,
  timer: null,
  audioCtx: null
};

// 监听 WebSocket 来电
document.addEventListener('DOMContentLoaded', () => {
  // 周期性确保 ws 已连
  setInterval(() => {
    if (window.ChatState && !ChatState.wsReady) {
      ensureWsConnected && ensureWsConnected();
    }
  }, 5000);
});

function handleIncomingCall(msg) {
  if (CallState.ringing || CallState.connected) {
    // 已有通话中，自动拒绝
    ChatState.ws.send(JSON.stringify({
      type: 'reject', to: msg.from, payload: { callId: msg.callId }
    }));
    return;
  }
  CallState.callId = msg.callId;
  CallState.callerIdentity = msg.from;
  CallState.ringing = true;
  showCallRingModal(msg.from);
  playRingTone();
}

function handleCallSignal(msg) {
  switch (msg.type) {
    case 'offer':
      handleOffer(msg);
      break;
    case 'candidate':
      handleRemoteCandidate(msg);
      break;
    case 'hangup':
      handleRemoteHangup(msg);
      break;
    case 'call_canceled':
      // 其他管理员已接听，关闭响铃
      closeRingModal();
      break;
  }
}

// ==================== 响铃弹窗 ====================
function showCallRingModal(from) {
  const phone = from.startsWith('user:') ? from.slice(5) : from;
  let modal = document.getElementById('callRingModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'callRingModal';
    modal.className = 'call-ring-modal';
    document.body.appendChild(modal);
  }
  modal.innerHTML = `
    <div class="call-ring-content">
      <div class="call-ring-avatar">📞</div>
      <div class="call-ring-title">来电</div>
      <div class="call-ring-from">${escapeHtml(phone)}</div>
      <div class="call-ring-actions">
        <button class="btn-reject" onclick="rejectCall()">拒接</button>
        <button class="btn-accept" onclick="acceptCall()">接听</button>
      </div>
    </div>
  `;
  modal.style.display = 'flex';
}

function closeRingModal() {
  const modal = document.getElementById('callRingModal');
  if (modal) modal.style.display = 'none';
  stopRingTone();
}

// 模拟响铃音
let ringAudioEl = null;
function playRingTone() {
  try {
    // 用 AudioContext 生成响铃音（避免依赖音频文件）
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.frequency.value = 440;
    gain.gain.value = 0.1;
    osc.start();
    CallState.audioCtx = ctx;
    ringAudioEl = { osc, gain };
    // 间断响铃
    let on = true;
    CallState.timer && clearInterval(CallState.timer);
  } catch (e) {
    console.warn('ring tone failed:', e);
  }
}

function stopRingTone() {
  try {
    if (ringAudioEl) {
      ringAudioEl.osc.stop();
      ringAudioEl = null;
    }
    if (CallState.audioCtx) {
      CallState.audioCtx.close();
      CallState.audioCtx = null;
    }
  } catch (e) {}
}

// ==================== 接听 / 拒绝 ====================
async function acceptCall() {
  if (!CallState.callId) return;
  closeRingModal();
  CallState.ringing = false;

  // 发送 accept
  ChatState.ws.send(JSON.stringify({
    type: 'accept',
    to: CallState.callerIdentity,
    payload: { callId: CallState.callId }
  }));

  // 显示通话中界面（等待对方 offer）
  showCallInProgressUI('正在建立通话...');
}

function rejectCall() {
  if (!CallState.callId) return;
  closeRingModal();
  ChatState.ws.send(JSON.stringify({
    type: 'reject',
    to: CallState.callerIdentity,
    payload: { callId: CallState.callId }
  }));
  CallState.ringing = false;
  CallState.callId = null;
  CallState.callerIdentity = null;
}

// ==================== 通话中界面 ====================
function showCallInProgressUI(status) {
  let modal = document.getElementById('callProgressModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'callProgressModal';
    modal.className = 'call-progress-modal';
    document.body.appendChild(modal);
  }
  modal.innerHTML = `
    <div class="call-progress-content">
      <div class="call-progress-icon">🔊</div>
      <div class="call-progress-status" id="callProgressStatus">${escapeHtml(status)}</div>
      <div class="call-progress-duration" id="callDuration">00:00</div>
      <div class="call-progress-actions">
        <button class="btn-mute" onclick="toggleMute()">静音</button>
        <button class="btn-hangup" onclick="hangupCall()">挂断</button>
      </div>
      <audio id="remoteAudio" autoplay></audio>
    </div>
  `;
  modal.style.display = 'flex';
}

function hideCallInProgressUI() {
  const modal = document.getElementById('callProgressModal');
  if (modal) modal.style.display = 'none';
  if (CallState.timer) {
    clearInterval(CallState.timer);
    CallState.timer = null;
  }
}

// ==================== WebRTC 流程 ====================
async function handleOffer(msg) {
  const payload = JSON.parse(msg.payload || '{}');
  const sdp = payload.sdp;
  if (!sdp) return;

  try {
    // 初始化 PeerConnection
    const iceServers = await fetchIceServers();
    CallState.pc = new RTCPeerConnection({ iceServers });

    // 添加本地音频
    CallState.localStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false
    });
    CallState.localStream.getTracks().forEach(t => CallState.pc.addTrack(t, CallState.localStream));

    // 远端音频
    CallState.pc.ontrack = (evt) => {
      const audio = document.getElementById('remoteAudio');
      if (audio) {
        audio.srcObject = evt.streams[0];
        audio.play().catch(e => console.warn('audio play:', e));
      }
    };

    // ICE 候选
    CallState.pc.onicecandidate = (evt) => {
      if (evt.candidate) {
        ChatState.ws.send(JSON.stringify({
          type: 'candidate',
          to: CallState.callerIdentity,
          payload: {
            candidate: evt.candidate.candidate,
            sdpMid: evt.candidate.sdpMid,
            sdpMLineIndex: evt.candidate.sdpMLineIndex,
            callId: CallState.callId
          }
        }));
      }
    };

    CallState.pc.oniceconnectionstatechange = () => {
      console.log('ICE state:', CallState.pc.iceConnectionState);
      const statusEl = document.getElementById('callProgressStatus');
      if (CallState.pc.iceConnectionState === 'connected') {
        CallState.connected = true;
        CallState.startTime = Date.now();
        if (statusEl) statusEl.textContent = '通话中';
        // 启动计时
        CallState.timer = setInterval(() => {
          const sec = Math.floor((Date.now() - CallState.startTime) / 1000);
          const m = String(Math.floor(sec / 60)).padStart(2, '0');
          const s = String(sec % 60).padStart(2, '0');
          const durEl = document.getElementById('callDuration');
          if (durEl) durEl.textContent = `${m}:${s}`;
        }, 1000);
      } else if (CallState.pc.iceConnectionState === 'failed' ||
                 CallState.pc.iceConnectionState === 'disconnected') {
        if (statusEl) statusEl.textContent = '连接中断';
        setTimeout(hangupCall, 1500);
      }
    };

    // 设置远端 offer
    await CallState.pc.setRemoteDescription({ type: 'offer', sdp });

    // 创建 answer
    const answer = await CallState.pc.createAnswer();
    await CallState.pc.setLocalDescription(answer);

    // 发回 answer
    ChatState.ws.send(JSON.stringify({
      type: 'answer',
      to: CallState.callerIdentity,
      payload: {
        sdp: answer.sdp,
        callId: CallState.callId
      }
    }));
  } catch (err) {
    console.error('handleOffer failed:', err);
    showToast('接听失败：' + err.message, 'error');
    hangupCall();
  }
}

async function handleRemoteCandidate(msg) {
  if (!CallState.pc) return;
  try {
    const payload = JSON.parse(msg.payload || '{}');
    await CallState.pc.addIceCandidate({
      candidate: payload.candidate,
      sdpMid: payload.sdpMid,
      sdpMLineIndex: payload.sdpMLineIndex
    });
  } catch (err) {
    console.warn('addIceCandidate failed:', err);
  }
}

function handleRemoteHangup(msg) {
  hideCallInProgressUI();
  if (CallState.pc) {
    CallState.pc.close();
    CallState.pc = null;
  }
  if (CallState.localStream) {
    CallState.localStream.getTracks().forEach(t => t.stop());
    CallState.localStream = null;
  }
  CallState.callId = null;
  CallState.callerIdentity = null;
  CallState.connected = false;
  showToast('对方已挂断', 'info');
}

async function hangupCall() {
  if (CallState.callId && CallState.callerIdentity) {
    ChatState.ws.send(JSON.stringify({
      type: 'hangup',
      to: CallState.callerIdentity,
      payload: { callId: CallState.callId }
    }));
  }
  if (CallState.pc) {
    CallState.pc.close();
    CallState.pc = null;
  }
  if (CallState.localStream) {
    CallState.localStream.getTracks().forEach(t => t.stop());
    CallState.localStream = null;
  }
  CallState.callId = null;
  CallState.callerIdentity = null;
  CallState.connected = false;
  hideCallInProgressUI();
}

function toggleMute() {
  if (!CallState.localStream) return;
  const audioTrack = CallState.localStream.getAudioTracks()[0];
  if (!audioTrack) return;
  audioTrack.enabled = !audioTrack.enabled;
  const btn = document.querySelector('.btn-mute');
  if (btn) btn.textContent = audioTrack.enabled ? '静音' : '取消静音';
}

async function fetchIceServers() {
  try {
    const res = await fetch('/api/config/ice', {
      headers: { 'Authorization': `Bearer ${API.token}` }
    });
    const data = await res.json();
    return data.iceServers || [{ urls: 'stun:stun.l.google.com:19302' }];
  } catch (e) {
    return [{ urls: 'stun:stun.l.google.com:19302' }];
  }
}
