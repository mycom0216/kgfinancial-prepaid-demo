'use strict';

const byId = (id) => document.getElementById(id);
const money = new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' });
const date = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' });
const cardSelect = byId('card');
const transaction = byId('transaction');
let cards = [];
let history = [];
let administrator = false;
let historyRequest = 0;
let busy = false;
let pendingTransaction;

function notice(message, error = false) {
  byId('notice').textContent = message;
  byId('notice').toggleAttribute('data-error', error);
}

class RequestError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  if (options.method === 'POST') {
    const { token } = await request('/api/auth/csrf');
    headers.set('X-XSRF-TOKEN', token);
  }
  const response = await fetch(path, { ...options, headers });
  if (response.status === 204) return;
  const result = await response.json().catch(() => null);
  if (!response.ok) {
    const defaults = {
      400: '입력값 또는 카드 잔액을 확인해 주세요.',
      401: '로그인 정보를 확인해 주세요.',
      403: '이 작업을 수행할 권한이 없거나 세션이 만료되었습니다.',
      404: '요청한 항목을 찾을 수 없습니다.',
      409: '이미 사용 중인 아이디 또는 거래 요청입니다.',
    };
    throw new RequestError(response.status, result?.message || defaults[response.status] || '서버 요청에 실패했습니다.');
  }
  if (result === null) throw new Error('서버 응답을 읽지 못했습니다.');
  return result;
}

function post(path, data, key) {
  const headers = { 'Content-Type': 'application/json' };
  if (key) headers['Idempotency-Key'] = key;
  return request(path, { method: 'POST', headers, body: JSON.stringify(data) });
}

async function run(action) {
  if (busy) return;
  busy = true;
  const controls = [...document.querySelectorAll('button, #card')];
  controls.forEach((control) => { control.disabled = true; });
  notice('');
  try {
    await action();
  } catch (error) {
    if (error.status === 401 && !byId('session').hidden) showGuest();
    notice(error instanceof RequestError ? error.message : '응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.', true);
  } finally {
    busy = false;
    controls.forEach((control) => { control.disabled = false; });
  }
}

function renderRows(id, rows, columns, empty) {
  const body = byId(id);
  body.replaceChildren();
  for (const values of rows) {
    const row = body.insertRow();
    for (const value of values) row.insertCell().textContent = value;
  }
  if (!rows.length) {
    const cell = body.insertRow().insertCell();
    cell.colSpan = columns;
    cell.textContent = empty;
  }
}

function renderHistory() {
  const kind = byId('filter').value;
  const rows = history.filter((item) => !kind || item.kind === kind);
  if (byId('sort').value === 'oldest') rows.reverse();
  renderRows('history-rows', rows.map((item) => [
    item.kind, money.format(item.amount), date.format(new Date(item.createdAt)),
  ]), 3, '표시할 거래가 없습니다.');
}

async function showCard() {
  const selected = cards.find((card) => String(card.id) === cardSelect.value);
  const generation = ++historyRequest;
  byId('empty').hidden = Boolean(selected);
  byId('selected-card').hidden = !selected;
  transaction.hidden = !selected;
  byId('history').hidden = !selected;
  history = [];
  renderHistory();
  if (!selected) return;
  byId('balance').textContent = money.format(selected.balance);
  renderRows('history-rows', [], 3, '거래 내역을 불러오는 중입니다.');
  try {
    const rows = await request('/api/cards/' + selected.id + '/transactions');
    if (generation !== historyRequest) return;
    history = rows;
    renderHistory();
  } catch (error) {
    if (generation !== historyRequest) return;
    renderRows('history-rows', [], 3, '거래 내역을 불러오지 못했습니다.');
    throw error;
  }
}

async function loadCards(preferred = cardSelect.value) {
  cards = await request('/api/cards');
  const options = cards.map((card) => new Option('카드 #' + card.id, card.id));
  cardSelect.replaceChildren(...options);
  if (cards.some((card) => String(card.id) === String(preferred))) cardSelect.value = preferred;
  await showCard();
}

async function loadAdmin() {
  const allCards = await request('/api/admin/cards');
  renderRows('admin-cards', allCards.map((card) => ['#' + card.id, money.format(card.balance)]), 2, '발급된 카드가 없습니다.');
  const logs = await request('/api/admin/audit-logs');
  renderRows('admin-logs', logs.map((log) => [
    log.actor, log.action, log.target, date.format(new Date(log.createdAt)),
  ]), 4, '감사 기록이 없습니다.');
}

function showGuest() {
  historyRequest++;
  pendingTransaction = undefined;
  for (const id of ['wallet', 'session', 'admin']) byId(id).hidden = true;
  for (const id of ['history-rows', 'admin-cards', 'admin-logs', 'card']) byId(id).replaceChildren();
  byId('account').textContent = '';
  transaction.reset();
  byId('guest').hidden = false;
}

async function loadSession() {
  let account;
  try {
    account = await request('/api/auth/me');
  } catch (error) {
    if (error.status !== 401) throw error;
    showGuest();
    return;
  }
  administrator = account.role === 'ROLE_ADMIN';
  byId('account').textContent = account.username + (administrator ? ' · 관리자' : '');
  byId('session').hidden = false;
  byId('guest').hidden = true;
  byId('wallet').hidden = false;
  byId('admin').hidden = !administrator;
  let sandbox = false;
  try {
    sandbox = (await request('/api/sandbox')).enabled;
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  byId('sandbox-label').hidden = !sandbox;
  byId('top-up').hidden = !sandbox;
  byId('top-up').disabled = !sandbox;
  await loadCards();
  if (administrator) await loadAdmin();
}

byId('login').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.target;
    await request('/api/auth/login', { method: 'POST', body: new URLSearchParams(new FormData(form)) });
    form.reset();
    await loadSession();
  });
});

byId('register').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    if (new TextEncoder().encode(data.password).length > 72) {
      notice('비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.', true);
      return;
    }
    await post('/api/auth/register', data);
    byId('login').elements.username.value = data.username;
    form.reset();
    byId('login').elements.password.focus();
    notice('가입했습니다. 로그인해 주세요.');
  });
});

byId('logout').addEventListener('click', () => run(async () => {
  await request('/api/auth/logout', { method: 'POST' });
  showGuest();
  notice('로그아웃했습니다.');
}));

byId('issue').addEventListener('click', () => run(async () => {
  const card = await post('/api/cards');
  await loadCards(card.id);
  notice('카드를 발급했습니다.');
}));

cardSelect.addEventListener('change', () => run(showCard));
byId('filter').addEventListener('change', renderHistory);
byId('sort').addEventListener('change', renderHistory);
byId('admin-refresh').addEventListener('click', () => run(loadAdmin));

transaction.addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const card = cardSelect.value;
    const topUp = transaction.elements.action.value === 'top-up';
    const amount = transaction.elements.amount.valueAsNumber;
    const path = topUp ? '/api/sandbox/cards/' + card + '/top-up' : '/api/cards/' + card + '/pay';
    if (pendingTransaction?.path !== path || pendingTransaction.amount !== amount) {
      pendingTransaction = { path, amount, key: crypto.randomUUID() };
    }
    try {
      await post(path, { amount }, pendingTransaction.key);
    } catch (error) {
      if (error.status >= 400 && error.status < 500) pendingTransaction = undefined;
      throw error;
    }
    pendingTransaction = undefined;
    transaction.elements.amount.value = '';
    await loadCards(card);
    notice(topUp ? '연습용 잔액을 충전했습니다.' : '연습 결제를 완료했습니다.');
  });
});

run(loadSession);
