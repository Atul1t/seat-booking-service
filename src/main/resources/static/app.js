/*
 * Seat Booking — client.
 *
 * Deliberately plain: no framework, no build step, one file. The interesting parts are
 * the hold countdown and the error handling, because those are where the server's
 * concurrency model becomes visible to a human.
 */
'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  token: localStorage.getItem('token'),
  user: null,
  show: null,        // ShowSummaryView of the show being viewed
  seatMap: null,     // SeatMapView
  selected: new Set(),
  hold: null,        // HoldView, while checking out
  holdStartedAt: 0,  // when that hold's reply arrived, for the countdown ring
  ticker: null,      // countdown interval id
};

/* ── api ──────────────────────────────────────────────────────────────── */

async function api(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (state.token) headers['Authorization'] = `Bearer ${state.token}`;

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 204) return null;

  const text = await res.text();
  const data = text ? JSON.parse(text) : null;

  if (!res.ok) {
    // A 401 means the token is missing, expired or bogus. There is no refresh flow
    // here, so the honest response is to drop it and ask the user to sign in again.
    if (res.status === 401) signOut();
    const err = new Error((data && data.message) || res.statusText);
    err.status = res.status;
    err.body = data;
    throw err;
  }
  return data;
}

/* ── screens ──────────────────────────────────────────────────────────── */

const SCREENS = ['auth', 'shows', 'seating', 'checkout', 'ticket', 'bookings'];

const initials = (name) => name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase();

function showScreen(name) {
  SCREENS.forEach((s) => { $(`screen-${s}`).hidden = s !== name; });
  $('topnav').hidden = !state.user;
  if (state.user) {
    $('avatar').textContent = initials(state.user.displayName);
    $('avatar').title = state.user.displayName;
  }
  // Leaving checkout must always stop the timer, or it keeps firing invisibly.
  if (name !== 'checkout') stopCountdown();
}

let toastTimer = null;
function toast(message, bad = false) {
  const el = $('toast');
  el.textContent = message;
  el.classList.toggle('bad', bad);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 4000);
}

/* ── auth ─────────────────────────────────────────────────────────────── */

document.querySelectorAll('.tab').forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
    $('loginForm').hidden = tab.dataset.tab !== 'login';
    $('registerForm').hidden = tab.dataset.tab !== 'register';
  };
});

$('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    const auth = await api('/api/auth/login', {
      method: 'POST',
      body: { email: f.get('email'), password: f.get('password') },
    });
    acceptToken(auth);
    await openShows();
  } catch (err) {
    toast(err.status === 401 ? 'Email or password is incorrect' : err.message, true);
  }
};

$('registerForm').onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const email = f.get('email');
  const password = f.get('password');
  try {
    await api('/api/auth/register', {
      method: 'POST',
      body: { email, password, displayName: f.get('displayName') },
    });
    const auth = await api('/api/auth/login', { method: 'POST', body: { email, password } });
    acceptToken(auth);
    await openShows();
  } catch (err) {
    toast(err.status === 409 ? 'That email already has an account' : err.message, true);
  }
};

function acceptToken(auth) {
  state.token = auth.token;
  state.user = auth.user;
  // Note: localStorage is readable by any script on this origin, so an XSS bug would
  // leak this token. The production answer is a short-lived in-memory access token
  // plus an httpOnly refresh cookie. Kept simple here, and documented rather than hidden.
  localStorage.setItem('token', auth.token);
}

function signOut() {
  localStorage.removeItem('token');
  state.token = null;
  state.user = null;
  state.hold = null;
  stopCountdown();
  showScreen('auth');
}

$('navLogout').onclick = signOut;
$('navShows').onclick = () => openShows();
$('navBookings').onclick = () => openBookings();
$('brand').onclick = () => { if (state.user) openShows(); };

/* ── shows ────────────────────────────────────────────────────────────── */

const when = (iso) => new Date(iso).toLocaleString(undefined, {
  weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
});

async function openShows() {
  showScreen('shows');
  const list = $('showList');

  // Skeletons rather than a blank page: the layout should not jump when data lands.
  list.innerHTML = '<div class="skeleton"></div>'.repeat(4);

  const shows = await api('/api/shows');
  list.innerHTML = '';

  if (!shows.length) {
    list.innerHTML = '<p class="empty">No shows scheduled.</p>';
    return;
  }

  shows.forEach((show) => {
    const soldOut = show.availableCount === 0;
    const taken = show.totalSeats - show.availableCount;

    const card = document.createElement('button');
    card.className = `showcard${soldOut ? ' soldout' : ''}`;
    card.innerHTML = `
      <span class="title"></span>
      <span class="muted when"></span>
      <span class="bar"><span></span></span>
      <span class="avail"></span>`;
    card.querySelector('.title').textContent = show.title;
    card.querySelector('.when').textContent = when(show.startsAt);
    card.querySelector('.bar > span').style.width =
      `${show.totalSeats ? (taken / show.totalSeats) * 100 : 0}%`;
    card.querySelector('.avail').textContent = soldOut
      ? 'Sold out'
      : `${show.availableCount} of ${show.totalSeats} seats free`;
    card.onclick = () => openSeating(show);
    list.appendChild(card);
  });
}

/* ── seat map ─────────────────────────────────────────────────────────── */

async function openSeating(show) {
  state.show = show;
  state.selected.clear();
  showScreen('seating');
  $('seatingTitle').textContent = show.title;
  $('seatingWhen').textContent = when(show.startsAt);
  await refreshSeatMap();
}

async function refreshSeatMap() {
  state.seatMap = await api(`/api/shows/${state.show.id}/seats`);
  renderSeatMap();
}

function renderSeatMap() {
  const map = $('seatMap');
  map.innerHTML = '';

  const rows = new Map();
  state.seatMap.seats.forEach((seat) => {
    if (!rows.has(seat.row)) rows.set(seat.row, []);
    rows.get(seat.row).push(seat);
  });

  rows.forEach((seats, rowLabel) => {
    const row = document.createElement('div');
    row.className = 'seatrow';
    row.appendChild(rowLabelEl(rowLabel));

    // An aisle down the middle, and a gentle arc so the rows read as an auditorium
    // rather than a spreadsheet. Purely visual: the seat ids are unchanged.
    const mid = seats.length >= 8 ? Math.floor(seats.length / 2) : -1;
    const centre = (seats.length - 1) / 2;

    seats.forEach((seat, i) => {
      if (i === mid) {
        const aisle = document.createElement('span');
        aisle.className = 'aisle';
        row.appendChild(aisle);
      }

      const taken = seat.status !== 'AVAILABLE';
      const btn = document.createElement('button');
      btn.className = `seat ${state.selected.has(seat.id) ? 'selected' : seat.status.toLowerCase()}`;
      btn.textContent = seat.number;
      btn.title = `${seat.label} — ${seat.status.toLowerCase()}`;
      btn.disabled = taken;
      btn.style.setProperty('--curve', (((i - centre) ** 2) * 0.16).toFixed(2));
      if (!taken) btn.onclick = () => toggleSeat(seat);
      row.appendChild(btn);
    });

    // Built with appendChild, never `innerHTML +=`. Appending to innerHTML re-parses
    // the element's whole subtree, discarding the existing children and every event
    // handler attached to them -- which silently unwires all the seat buttons above.
    row.appendChild(rowLabelEl(''));
    map.appendChild(row);
  });

  updateSelection();
}

function rowLabelEl(text) {
  const el = document.createElement('span');
  el.className = 'rowlabel';
  el.textContent = text;
  return el;
}

function toggleSeat(seat) {
  if (state.selected.has(seat.id)) state.selected.delete(seat.id);
  else if (state.selected.size >= 10) return toast('Ten seats at a time is the limit', true);
  else state.selected.add(seat.id);
  renderSeatMap();
}

function updateSelection() {
  const labels = state.seatMap.seats
    .filter((s) => state.selected.has(s.id))
    .map((s) => s.label);
  $('selectionSummary').textContent = labels.length
    ? `${labels.length} seat${labels.length > 1 ? 's' : ''}: ${labels.join(', ')}`
    : 'No seats selected';
  $('holdButton').disabled = labels.length === 0;
}

$('backToShows').onclick = () => openShows();

/* ── holding ──────────────────────────────────────────────────────────── */

$('holdButton').onclick = async () => {
  try {
    state.hold = await api(`/api/shows/${state.show.id}/holds`, {
      method: 'POST',
      body: { seatIds: [...state.selected] },
    });
    state.holdStartedAt = Date.now();
    openCheckout();
  } catch (err) {
    if (err.status === 409) {
      // Somebody else got there first. The seat map on screen is now stale, so the
      // only useful thing to do is refetch it and let the user choose again.
      const taken = (err.body && err.body.details) || [];
      toast(`Just taken: ${taken.join(', ') || 'those seats'}`, true);
      state.selected.clear();
      await refreshSeatMap();
    } else {
      toast(err.message, true);
    }
  }
};

function openCheckout() {
  showScreen('checkout');
  $('checkoutShow').textContent = state.show.title;
  $('checkoutSeats').textContent = state.hold.seats.join(', ');
  startCountdown(state.hold.expiresAt, state.holdStartedAt);
}

/* The countdown is the server's hold window made visible. It is derived from the
 * expiresAt the server returned, not from a client-side timer started at zero, so a
 * slow network or a backgrounded tab cannot make it drift optimistically. */
const RING = 2 * Math.PI * 52;   // matches r="52" on the progress circle

function startCountdown(expiresAtIso, startedAt) {
  stopCountdown();
  const expiresAt = new Date(expiresAtIso).getTime();
  // The ring needs a starting point as well as an end. The server sends only
  // expiresAt, so the window is measured from the moment its reply arrived --
  // off by the round trip, which is a fraction of a second.
  const total = Math.max(1000, expiresAt - startedAt);

  const ring = $('ringProgress');
  const timer = document.querySelector('.timer');
  ring.style.strokeDasharray = String(RING);

  const tick = () => {
    const left = Math.max(0, expiresAt - Date.now());
    const seconds = Math.ceil(left / 1000);

    $('countdown').textContent =
      `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    ring.style.strokeDashoffset = String(RING * (1 - Math.min(1, left / total)));
    timer.classList.toggle('urgent', left <= 30000);

    if (left === 0) {
      stopCountdown();
      toast('Your hold expired — those seats are back on sale', true);
      state.hold = null;
      state.selected.clear();
      openSeating(state.show);
    }
  };

  tick();
  state.ticker = setInterval(tick, 200);
}

function stopCountdown() {
  if (state.ticker) clearInterval(state.ticker);
  state.ticker = null;
}

$('cancelHold').onclick = async () => {
  const hold = state.hold;
  state.hold = null;
  stopCountdown();
  try {
    await api(`/api/holds/${hold.holdId}`, { method: 'DELETE' });
  } catch (err) {
    // A hold that already expired is not an error worth showing anyone.
    if (err.status !== 409 && err.status !== 404) toast(err.message, true);
  }
  state.selected.clear();
  await openSeating(state.show);
};

$('confirmHold').onclick = async () => {
  try {
    const booking = await api(`/api/holds/${state.hold.holdId}/confirm`, { method: 'POST' });
    stopCountdown();
    showTicket(booking);
  } catch (err) {
    if (err.status === 410) {
      toast('That hold timed out — please pick your seats again', true);
      state.hold = null;
      state.selected.clear();
      await openSeating(state.show);
    } else {
      toast(err.message, true);
    }
  }
};

/* ── ticket & bookings ────────────────────────────────────────────────── */

function showTicket(booking) {
  state.hold = null;
  state.selected.clear();
  showScreen('ticket');
  $('ticketRef').textContent = booking.reference;
  $('ticketShow').textContent = state.show.title;
  $('ticketSeats').textContent = booking.seats.join(', ');
  $('ticketName').textContent = booking.customerName;
}

$('ticketDone').onclick = () => openShows();

async function openBookings() {
  showScreen('bookings');
  const bookings = await api('/api/bookings');
  const list = $('bookingList');
  list.innerHTML = '';

  if (!bookings.length) {
    list.innerHTML = '<p class="empty">Nothing booked yet.</p>';
    return;
  }

  bookings.forEach((b) => {
    const row = document.createElement('div');
    row.className = 'booking';
    row.innerHTML = '<div><div class="seats"></div><div class="ref"></div></div><div class="muted when"></div>';
    row.querySelector('.seats').textContent = `Seats ${b.seats.join(', ')}`;
    row.querySelector('.ref').textContent = b.reference;
    row.querySelector('.when').textContent = when(b.confirmedAt);
    list.appendChild(row);
  });
}

/* ── boot ─────────────────────────────────────────────────────────────── */

(async function boot() {
  if (!state.token) return showScreen('auth');
  try {
    state.user = await api('/api/auth/me');
    await openShows();
  } catch {
    signOut();   // stale or expired token
  }
})();
