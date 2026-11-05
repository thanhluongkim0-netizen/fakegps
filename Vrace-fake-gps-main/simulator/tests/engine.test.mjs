import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SessionEngine, averagePace, currentPace, formatPace, formatDuration, achievements } from '../web/engine.mjs';

function fixture() {
  let time = 1000, domain = 'boot-1', serial = 0, raw = '', fail = false;
  const clock = { now: () => time, domain: () => domain, wallNow: () => 1700000000000 + time };
  const store = { read: () => raw, write: value => { if (fail) return false; raw = value; return true; } };
  const build = () => new SessionEngine({ clock, store, id: () => `session-${++serial}` });
  return { engine: build(), build, advance: ms => { time += ms; }, reboot: () => { time = 10; domain = 'boot-2'; },
    backwards: () => { time = 0; }, fail: value => { fail = value; }, corrupt: value => { raw = value; }, raw: () => raw };
}
const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-6, `${actual} != ${expected}`);

test('constant speed integrates actual elapsed time, independent of timer ticks', () => {
  const f = fixture(); f.engine.start(10, null); f.advance(3600000); f.engine.tick();
  const s = f.engine.snapshot().active;
  close(s.distanceMeters, 10000); assert.equal(s.activeMs, 3600000); assert.equal(formatPace(averagePace(s)), '6:00');
});
test('pause and resume exclude paused distance and active time', () => {
  const f = fixture(); f.engine.start(12, null); f.advance(300000); f.engine.pause();
  f.advance(900000); f.engine.tick(); f.engine.resume(); f.advance(300000); const s = f.engine.finish();
  close(s.distanceMeters, 2000); assert.equal(s.activeMs, 600000);
});
test('changing speed settles the old speed first', () => {
  const f = fixture(); f.engine.start(6, null); f.advance(600000); f.engine.setSpeed(12); f.advance(600000);
  const s = f.engine.finish(); close(s.distanceMeters, 3000); assert.equal(formatPace(averagePace(s)), '6:40');
});
test('zero speed has zero distance and an explicit undefined pace', () => {
  const f = fixture(); f.engine.start(0, null); f.advance(60000); const s = f.engine.finish();
  assert.equal(s.distanceMeters, 0); assert.equal(s.activeMs, 60000);
  assert.equal(formatPace(currentPace(0)), '—'); assert.equal(formatPace(averagePace(s)), '—');
});
test('running session survives a process restart on the same boot', () => {
  const f = fixture(); f.engine.start(12, null); f.advance(300000); f.engine.tick(); f.advance(300000);
  const restored = f.build(); close(restored.snapshot().active.distanceMeters, 2000);
  restored.tick(); close(restored.snapshot().active.distanceMeters, 2000);
});
test('paused session survives restart without adding downtime', () => {
  const f = fixture(); f.engine.start(12, null); f.advance(300000); f.engine.pause(); f.advance(86400000);
  const s = f.build().snapshot().active; close(s.distanceMeters, 1000); assert.equal(s.activeMs, 300000);
});
test('phone reboot restores paused and excludes time on a different clock', () => {
  const f = fixture(); f.engine.start(12, null); f.advance(300000); f.engine.tick(); f.reboot();
  const s = f.build().snapshot().active; assert.equal(s.status, 'paused'); close(s.distanceMeters, 1000);
});
test('backwards clock pauses safely', () => {
  const f = fixture(); f.engine.start(12, null); f.backwards(); f.engine.tick();
  assert.equal(f.engine.snapshot().active.status, 'paused'); assert.equal(f.engine.snapshot().active.distanceMeters, 0);
});
test('goal completion clamps both distance and time and saves exactly once', () => {
  const f = fixture(); f.engine.start(12, 1); f.advance(900000); const s = f.engine.tick();
  assert.equal(s.distanceMeters, 1000); close(s.activeMs, 300000); assert.equal(s.goalCompleted, true);
  f.engine.tick(); f.engine.finish(); assert.equal(f.engine.snapshot().records.length, 1); assert.equal(f.build().snapshot().records.length, 1);
});
test('finishing twice cannot save duplicate records', () => {
  const f = fixture(); f.engine.start(10, null); f.advance(1000); f.engine.finish();
  assert.equal(f.engine.finish(), null); assert.equal(f.engine.snapshot().records.length, 1);
});
test('failed save rolls memory back and a retry saves once', () => {
  const f = fixture(); f.engine.start(12, null); f.advance(300000); f.fail(true);
  assert.throws(() => f.engine.finish(), /Không thể lưu/); assert.equal(f.engine.snapshot().records.length, 0);
  f.fail(false); close(f.engine.finish().distanceMeters, 1000); assert.equal(f.engine.snapshot().records.length, 1);
});
test('invalid persistence is rejected without overwriting original data', () => {
  const f = fixture(); f.corrupt('{broken'); assert.throws(f.build); assert.equal(f.raw(), '{broken');
  f.corrupt(JSON.stringify({ version: 999 })); assert.throws(f.build, /Dữ liệu lưu/);
});
test('invalid inputs and a second start leave state intact', () => {
  const f = fixture(); for (const speed of [-1, Infinity, NaN, 31, '10']) assert.throws(() => f.engine.start(speed, null));
  assert.throws(() => f.engine.start(10, 0)); f.engine.start(10, null); assert.throws(() => f.engine.start());
  assert.throws(() => f.engine.setGoal(5)); assert.equal(f.engine.snapshot().active.speedKmh, 10);
});
test('history remains local and marked as simulation after restart', () => {
  const f = fixture(); f.engine.start(10, null); f.advance(1800000); f.engine.finish();
  const records = f.build().snapshot().records; assert.equal(records[0].simulated, true); assert.equal(records[0].source, 'simulation');
  const stats = achievements(records); assert.equal(stats.badges[1].unlocked, true); close(stats.totalMeters, 5000);
});
test('formatting carries pace seconds correctly and supports long sessions', () => {
  assert.equal(formatPace(359.7), '6:00'); assert.equal(formatPace(Infinity), '—');
  assert.equal(formatDuration(3661000), '01:01:01');
});
