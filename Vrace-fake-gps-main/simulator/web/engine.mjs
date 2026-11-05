// All durations use a monotonic clock. Wall time is only used for record dates.
const copy = value => JSON.parse(JSON.stringify(value));
const nonnegative = value => Number.isFinite(value) && value >= 0;
const validSpeed = value => nonnegative(value) && value <= 30;
const validGoal = value => value === null || (Number.isFinite(value) && value > 0 && value <= 100);
const freshState = () => ({ version: 1, settings: { speedKmh: 10, goalKm: 5 }, active: null, records: [] });

function validSession(session, active) {
  return session && typeof session.id === 'string' && session.id.length > 0 &&
    session.simulated === true && session.source === 'simulation' &&
    nonnegative(session.distanceMeters) && nonnegative(session.activeMs) &&
    nonnegative(session.startedAt) && validSpeed(session.speedKmh) &&
    validSpeed(session.maxSpeedKmh) && validGoal(session.goalKm) &&
    (active ? ['running', 'paused'].includes(session.status) &&
      nonnegative(session.anchorMs) && typeof session.clockDomain === 'string' :
      session.status === 'finished' && nonnegative(session.endedAt) &&
      typeof session.goalCompleted === 'boolean');
}

function parseState(raw) {
  if (!raw) return freshState();
  const state = JSON.parse(raw);
  if (state.version !== 1 || !validSpeed(state.settings?.speedKmh) ||
      !validGoal(state.settings?.goalKm) || !Array.isArray(state.records) ||
      !state.records.every(record => validSession(record, false)) ||
      (state.active !== null && !validSession(state.active, true))) {
    throw new Error('Dữ liệu lưu không hợp lệ. Dữ liệu gốc vẫn được giữ nguyên.');
  }
  const ids = state.records.map(record => record.id);
  if (new Set(ids).size !== ids.length || (state.active && ids.includes(state.active.id))) {
    throw new Error('Dữ liệu có mã phiên trùng nhau. Dữ liệu gốc vẫn được giữ nguyên.');
  }
  return state;
}

export class SessionEngine {
  constructor({ store, clock, id = () => globalThis.crypto.randomUUID() }) {
    this.store = store;
    this.clock = clock;
    this.id = id;
    this.state = parseState(store.read());
    if (this.state.active) this.tick();
  }

  snapshot() { return copy(this.state); }

  transact(change) {
    const next = copy(this.state);
    const result = change(next);
    // Commit storage before replacing memory: failed saves can safely be retried.
    if (this.store.write(JSON.stringify(next)) === false) {
      throw new Error('Không thể lưu dữ liệu trên điện thoại. Hãy thử lại.');
    }
    this.state = next;
    return result;
  }

  archive(state, endedAt, goalCompleted = false) {
    const session = state.active;
    if (!session) return null;
    const { anchorMs, clockDomain, ...record } = session;
    record.status = 'finished';
    record.endedAt = endedAt;
    record.goalCompleted = goalCompleted;
    state.records.unshift(record);
    state.active = null;
    return record;
  }

  integrate(state) {
    const session = state.active;
    if (!session) return null;
    const now = this.clock.now();
    const domain = this.clock.domain();
    if (session.clockDomain !== domain || now < session.anchorMs) {
      session.status = 'paused';
      session.clockDomain = domain;
      session.anchorMs = now;
      return null;
    }
    if (session.status !== 'running') return null;
    const elapsed = now - session.anchorMs;
    let usedMs = elapsed;
    const metersPerMs = session.speedKmh / 3600;
    if (session.goalKm !== null && metersPerMs > 0) {
      usedMs = Math.min(elapsed, Math.max(0, session.goalKm * 1000 - session.distanceMeters) / metersPerMs);
    }
    session.distanceMeters += usedMs * metersPerMs;
    session.activeMs += usedMs;
    session.anchorMs = now;
    if (session.goalKm !== null && session.distanceMeters >= session.goalKm * 1000 - 1e-7) {
      session.distanceMeters = session.goalKm * 1000;
      return this.archive(state, Math.max(0, this.clock.wallNow() - (elapsed - usedMs)), true);
    }
    return null;
  }

  tick() {
    if (!this.state.active) return null;
    return this.transact(state => this.integrate(state));
  }

  start(speedKmh = this.state.settings.speedKmh, goalKm = this.state.settings.goalKm) {
    if (!validSpeed(speedKmh) || !validGoal(goalKm)) throw new Error('Tốc độ hoặc mục tiêu không hợp lệ.');
    if (this.state.active) throw new Error('Hãy kết thúc phiên hiện tại trước.');
    return this.transact(state => {
      state.settings = { speedKmh, goalKm };
      state.active = {
        id: this.id(), simulated: true, source: 'simulation', status: 'running',
        speedKmh, maxSpeedKmh: speedKmh, goalKm, distanceMeters: 0, activeMs: 0,
        startedAt: this.clock.wallNow(), anchorMs: this.clock.now(), clockDomain: this.clock.domain()
      };
    });
  }

  pause() {
    if (!this.state.active || this.state.active.status !== 'running') return null;
    return this.transact(state => {
      const completed = this.integrate(state);
      if (state.active) state.active.status = 'paused';
      return completed;
    });
  }

  resume() {
    if (!this.state.active || this.state.active.status !== 'paused') return;
    this.transact(state => {
      state.active.status = 'running';
      state.active.anchorMs = this.clock.now();
      state.active.clockDomain = this.clock.domain();
    });
  }

  setSpeed(speedKmh) {
    if (!validSpeed(speedKmh)) throw new Error('Tốc độ phải nằm trong khoảng 0–30 km/h.');
    return this.transact(state => {
      const completed = this.integrate(state);
      state.settings.speedKmh = speedKmh;
      if (state.active) {
        state.active.speedKmh = speedKmh;
        state.active.maxSpeedKmh = Math.max(state.active.maxSpeedKmh, speedKmh);
      }
      return completed;
    });
  }

  setGoal(goalKm) {
    if (!validGoal(goalKm)) throw new Error('Mục tiêu không hợp lệ.');
    if (this.state.active) throw new Error('Chọn mục tiêu trước khi bắt đầu.');
    this.transact(state => { state.settings.goalKm = goalKm; });
  }

  finish() {
    if (!this.state.active) return null;
    return this.transact(state => this.integrate(state) || this.archive(state, this.clock.wallNow()));
  }
}

export function formatDuration(ms) {
  const seconds = Math.floor(Math.max(0, ms) / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

export function formatPace(secondsPerKm) {
  if (!Number.isFinite(secondsPerKm) || secondsPerKm <= 0) return '—';
  const seconds = Math.round(secondsPerKm);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export const averagePace = session => session?.distanceMeters > 0 && session.activeMs > 0
  ? session.activeMs / session.distanceMeters : null;
export const currentPace = speedKmh => speedKmh > 0 ? 3600 / speedKmh : null;

export function achievements(records) {
  const totalMeters = records.reduce((sum, record) => sum + record.distanceMeters, 0);
  const longestMeters = Math.max(0, ...records.map(record => record.distanceMeters));
  const paces = records.filter(record => record.distanceMeters >= 1000).map(averagePace).filter(pace => pace > 0);
  return {
    totalMeters, longestMeters, bestPace: paces.length ? Math.min(...paces) : null,
    badges: [
      { title: 'Phiên đầu tiên', description: 'Lưu một phiên mô phỏng', unlocked: records.length > 0 },
      { title: 'Cột mốc 5 km', description: 'Một phiên mô phỏng đạt 5 km', unlocked: longestMeters >= 5000 },
      { title: 'Tích lũy 25 km', description: 'Tổng quãng đường mô phỏng', unlocked: totalMeters >= 25000 }
    ]
  };
}
