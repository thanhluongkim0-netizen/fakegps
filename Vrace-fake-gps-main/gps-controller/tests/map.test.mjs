import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createContext, runInContext } from 'node:vm';

// Run the shipped UI, including its event listeners and Android payload, and native Maps selection, without network access.
const html = readFileSync(new URL('../app/src/main/assets/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
function ui(config = {}) {
  const elements = new Map(), saved = [], commands = [], maps = [];
  const snapshot = { state: 'IDLE', ready: false };
  class Element {
    style = {}; value = '';
  }
  const get = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  const context = createContext({
    window: {
      Android: {
        config: () => JSON.stringify(config),
        snapshot: () => JSON.stringify(snapshot),
        saveConfig: value => saved.push(JSON.parse(value)),
        command: (action, value) => commands.push({ action, ...JSON.parse(value) }),
        settings() {}, currentLocation() {}, cancelLocation() {},
        openMap: value => maps.push(JSON.parse(value)),
      },
      addEventListener() {},
    },
    document: { getElementById: get, querySelectorAll: () => [] },
    setInterval() {}, setTimeout() {}, clearTimeout() {},
  });
  runInContext(script, context);
  const evaluate = expression => {
    const result = runInContext(expression, context);
    return result === undefined ? undefined : JSON.parse(JSON.stringify(result));
  };
  const select = selection => evaluate(`applyMapSelection(${JSON.stringify(JSON.stringify(selection))})`);
  return { evaluate, get, select, saved, commands, maps, snapshot };
}
function close(actual, expected, tolerance = 1e-9) {
  assert.ok(Math.abs(actual - expected) < tolerance, `${actual} differs from ${expected}`);
}
function closePoint(actual, expected) {
  close(actual[0], expected[0]);
  close(actual[1], expected[1]);
}

test('selected points survive reload and manually entered valid routes remain unchanged', () => {
  const app = ui({ latitude: 10.87552, longitude: 106.80575, zoom: 16 });
  app.select({points: [[10.87552,106.80575],[10.87652,106.80675]]});
  const config = app.saved.at(-1);
  const reloaded = ui(config);
  assert.deepEqual(reloaded.evaluate('points'), config.points);
  reloaded.get('coordinates').value = '40.7128,-74.006\n40.7148,-74.005';
  reloaded.get('apply').onclick();
  assert.deepEqual(reloaded.evaluate('points'), [[40.7128, -74.006], [40.7148, -74.005]]);
  reloaded.get('hold').onclick();
  assert.deepEqual(reloaded.commands.at(-1).points, [[40.7128, -74.006], [40.7148, -74.005]]);
});

test('one coordinate with spaces sets A and the center, then native Maps selection supplies B', () => {
  const app = ui({ points: [[10, 100], [10.01, 100]] });
  app.get('coordinates').value = ' 10.8784,  106.7972 \n';
  app.get('apply').onclick();
  assert.deepEqual(app.evaluate('points'), [[10.8784, 106.7972]]);
  closePoint(app.evaluate('[lat,lon]'), [10.8784, 106.7972]);
  assert.deepEqual(app.saved.at(-1).points, [[10.8784, 106.7972]]);
  app.get('hold').onclick();
  assert.equal(app.commands.length, 0, 'one point must not start a movement session');
  assert.match(app.get('toast').textContent, /Cần cả A và B/);
  app.select({points: [[10.8784,106.7972],[10.88,106.7972]]});
  app.get('hold').onclick();
  assert.equal(app.commands.at(-1).points.length, 2);
  assert.deepEqual(app.commands.at(-1).points[0], [10.8784, 106.7972]);
});

test('coordinate entry rejects empty components and invalid numbers without replacing the route', () => {
  const app = ui({ points: [[10.8784, 106.7972], [10.88, 106.7972]] });
  const original = app.evaluate('points');
  for (const invalid of ['', ',106.7972', '10.8784,', '10.8784,,106.7972', '10.8784,106.7972,0',
    'NaN,106.7972', '10.8784,Infinity', '86,106.7972', '10.8784,181', '0x10,106.7972']) {
    app.get('coordinates').value = invalid;
    app.get('apply').onclick();
    assert.deepEqual(app.evaluate('points'), original, invalid);
    assert.match(app.get('toast').textContent, /hợp lệ/);
  }
  assert.deepEqual(app.evaluate("parseCoordinates('0, 0\\n\\n-10.5 -106.5\\r\\n')"), [[0, 0], [-10.5, -106.5]]);
});

test('a pending current-position request displays progress, completes or unlocks for retry', () => {
  const app = ui();
  app.evaluate("locationStatus(true,'Đang tìm vị trí'); refresh()");
  assert.equal(app.get('center').disabled, true);
  assert.equal(app.get('locationHint').textContent, 'Đang tìm vị trí');
  app.evaluate('currentPositionFound(10.8784,106.7972)');
  closePoint(app.evaluate('[lat,lon]'), [10.8784, 106.7972]);
  assert.equal(app.get('center').disabled, false);
  app.evaluate("locationStatus(true,'Đang tìm');locationStatus(false,'Chưa định vị được');refresh()");
  assert.equal(app.get('center').disabled, false);
  assert.equal(app.get('locationHint').textContent, 'Chưa định vị được');
});

test('manual coordinates cancel a pending lookup and a late result cannot move the chosen map', () => {
  const app = ui();
  app.evaluate("locationStatus(true,'Đang tìm vị trí')");
  app.get('coordinates').value = '10.8784,106.7972';
  app.get('apply').onclick();
  app.evaluate('currentPositionFound(40,-74)');
  closePoint(app.evaluate('[lat,lon]'), [10.8784, 106.7972]);
  assert.equal(app.get('center').disabled, false);
  app.snapshot.state = 'HOLDING';
  app.evaluate('refresh()');
  assert.equal(app.get('center').disabled, true);
  app.get('coordinates').value = '40,-74';
  app.get('apply').onclick();
  assert.deepEqual(app.evaluate('points'), [[10.8784, 106.7972]]);
});

test('opens native Google Maps with saved camera and route; contains no OSM tile requests', () => {
  assert.doesNotMatch(html, /openstreetmap|tileLayer|function unworld|new Image/);
  const app=ui({points:[[10.8784,106.7972]],latitude:10.8784,longitude:106.7972,zoom:16.5});
  app.get('openMap').onclick();
  assert.deepEqual(app.maps.at(-1),{points:[[10.8784,106.7972]],latitude:10.8784,longitude:106.7972,zoom:16.5});
});
test('native Google Maps preserves repeated Vietnam route and fractional zoom in GPS payload', () => {
  const app=ui(), points=[[10.8784,106.7972],[10.8804,106.7992],[10.8784,106.7972],[10.8804,106.7992]];
  app.select({points,latitude:points[0][0],longitude:points[0][1],zoom:17.5});
  assert.deepEqual(app.evaluate('points'),points);
  assert.deepEqual(app.saved.at(-1).points,points);
  app.get('hold').onclick();
  assert.deepEqual(app.commands.at(-1).points,points);
  assert.equal(app.evaluate('zoom'),17.5);
});
test('native selection rejects malformed or oversized routes without replacing current points', () => {
  const points=[[10.8784,106.7972],[10.8804,106.7992]], app=ui({points});
  for(const invalid of [null,[[86,100]],[[10,181]],[[10]],[[10,100,0]],Array(301).fill(points[0])]) {
    app.select({points:invalid,latitude:40,longitude:-74});
    assert.deepEqual(app.evaluate('points'),points);
  }
  app.evaluate("applyMapSelection('bad JSON')");
  assert.deepEqual(app.evaluate('points'),points);
});
test('returning from Maps while playing updates camera without changing the active route', () => {
  const points=[[10.8784,106.7972],[10.8804,106.7992]], app=ui({points});
  app.snapshot.state='HOLDING';
  app.select({points:[[40,-74],[40.01,-74]],latitude:40,longitude:-74,zoom:14});
  assert.deepEqual(app.evaluate('points'),points);
  assert.deepEqual(app.evaluate('[lat,lon]'),[40,-74]);
  assert.equal(app.get('openMap').textContent,'Xem tuyến trên Google Maps');
});
test('opening Maps cancels a pending location search and ignores its late response', () => {
  const app=ui({latitude:10.8784,longitude:106.7972});
  app.evaluate("locationStatus(true,'Đang tìm')");
  app.get('openMap').onclick(); app.evaluate('currentPositionFound(40,-74)');
  assert.deepEqual(app.evaluate('[lat,lon]'),[10.8784,106.7972]);
  assert.equal(app.get('center').disabled,false);
});
