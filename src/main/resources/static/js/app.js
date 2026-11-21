// HealPay AI - small vanilla JS layer: live events (SSE), KPI refresh, assistant chat.
(function () {
  'use strict';

  const page = document.body.dataset.page;
  const toastEl = document.getElementById('toast');
  let toastTimer;

  function toast(text) {
    if (!toastEl) return;
    toastEl.textContent = text;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 4000);
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function timeOf(iso) {
    try { return new Date(iso).toLocaleTimeString([], { hour12: false }); } catch (e) { return ''; }
  }

  // ---------------------------------------------------------------- KPI refresh
  let kpiTimer;
  function refreshKpis() {
    clearTimeout(kpiTimer);
    kpiTimer = setTimeout(async () => {
      try {
        const res = await fetch('/api/recon/summary', { headers: { 'Accept': 'application/json' } });
        if (!res.ok) return;
        const s = await res.json();
        document.querySelectorAll('[data-kpi]').forEach(el => {
          const key = el.dataset.kpi;
          if (!(key in s)) return;
          let value = s[key];
          if (key === 'amountAtRiskInr') {
            value = '₹' + Number(value).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
          }
          if (el.textContent !== String(value)) {
            el.textContent = value;
            el.classList.remove('bump');
            void el.offsetWidth;
            el.classList.add('bump');
          }
        });
      } catch (e) { /* ignore */ }
    }, 400);
  }

  // ---------------------------------------------------------------- live events
  function connect() {
    if (!window.EventSource || page === undefined) return;
    const dot = document.getElementById('live-dot');
    const es = new EventSource('/api/events/stream');
    es.addEventListener('hello', () => dot && dot.classList.add('on'));
    es.onerror = () => dot && dot.classList.remove('on');
    es.addEventListener('audit', (msg) => {
      let e;
      try { e = JSON.parse(msg.data); } catch (err) { return; }
      onEvent(e);
    });
  }

  const interesting = new Set(['AUTO_HEALED', 'HEALED_AFTER_APPROVAL', 'APPROVAL_REQUESTED', 'DETECTED', 'ESCALATED', 'VERIFY_FAILED']);

  function onEvent(e) {
    const feed = document.getElementById('live-feed');
    if (feed) {
      const li = document.createElement('li');
      li.className = 'new actor-' + e.actor;
      li.innerHTML =
        '<span class="feed-time">' + esc(timeOf(e.occurredAt)) + '</span>' +
        '<span class="feed-stage">' + esc(e.stage) + '</span>' +
        (e.txnRef ? '<a href="/transactions/' + encodeURIComponent(e.txnRef) + '">' + esc(e.txnRef) + '</a>' : '') +
        '<span class="feed-msg">' + esc(e.message) + '</span>';
      feed.prepend(li);
      while (feed.children.length > 60) feed.removeChild(feed.lastChild);
      refreshKpis();
    }

    if (page === 'transaction' && e.txnRef && e.txnRef === document.body.dataset.txn) {
      const tl = document.getElementById('timeline');
      if (tl) {
        const li = document.createElement('li');
        li.className = 'actor-' + e.actor;
        li.innerHTML = '<div class="tl-dot"></div><div class="tl-body"><div><strong>' + esc(e.stage) +
          '</strong><span class="actor">' + esc(e.actor) + '</span><span class="muted small">' + esc(timeOf(e.occurredAt)) +
          '</span></div><div>' + esc(e.message) + '</div></div>';
        tl.appendChild(li);
      }
      const notice = document.getElementById('updated-notice');
      if (notice) notice.hidden = false;
    }

    if (interesting.has(e.stage)) {
      toast((e.txnRef ? e.txnRef + ' · ' : '') + e.stage.replace(/_/g, ' ').toLowerCase() + ' - ' + e.message);
    }
  }

  // ---------------------------------------------------------------- assistant
  function setupAssistant() {
    const form = document.getElementById('ask-form');
    if (!form) return;
    const input = document.getElementById('question');
    const chat = document.getElementById('chat');

    function add(cls, text, source) {
      const div = document.createElement('div');
      div.className = 'msg ' + cls;
      div.textContent = text;
      if (source) {
        const src = document.createElement('span');
        src.className = 'src';
        src.textContent = 'answered by: ' + source;
        div.appendChild(src);
      }
      chat.appendChild(div);
      chat.scrollTop = chat.scrollHeight;
      return div;
    }

    async function ask(q) {
      add('user', q);
      const thinking = add('bot thinking', 'Looking it up…');
      try {
        const res = await fetch('/api/assistant/ask', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
          body: JSON.stringify({ question: q })
        });
        const data = await res.json();
        thinking.remove();
        add('bot', data.answer || 'No answer.', data.source);
      } catch (err) {
        thinking.remove();
        add('bot', 'Sorry, the assistant is unavailable right now.');
      }
    }

    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      const q = input.value.trim();
      if (!q) return;
      input.value = '';
      ask(q);
    });
    document.querySelectorAll('.chip-btn[data-q]').forEach(b =>
      b.addEventListener('click', () => ask(b.dataset.q)));
  }

  connect();
  setupAssistant();
})();
