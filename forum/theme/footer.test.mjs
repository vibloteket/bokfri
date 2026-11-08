import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const footer = readFileSync(new URL('./footer.html', import.meta.url), 'utf8');
const script = footer.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'footer must contain its analytics script');

// Isolated browser model: no network, accounts, real tokens or production analytics hits.
function browser(path = '/') {
  const timers = new Map();
  const listeners = {};
  const hits = [];
  const location = {};
  let serial = 0;
  let loadedScript;
  const setLocation = (path) => {
    const url = new URL(path, 'https://forum.bokfri.viblo.se');
    Object.assign(location, { pathname: url.pathname, search: url.search, hash: url.hash });
  };
  setLocation(path);
  const history = {};
  for (const method of ['pushState', 'replaceState']) {
    history[method] = function (_state, _title, url) {
      assert.equal(this, history, 'History API receiver must be preserved');
      if (url !== undefined) setLocation(url);
      return 'native-result';
    };
  }
  const window = { location, history, addEventListener: (name, fn) => { listeners[name] = fn; } };
  const document = {
    visibilityState: 'visible',
    referrer: 'https://example.org/reset/PRIVATE?token=PRIVATE',
    addEventListener: (name, fn) => { listeners[name] = fn; },
    head: { appendChild: (element) => { loadedScript = element; } },
    createElement: () => ({ attributes: {}, setAttribute(name, value) { this.attributes[name] = value; } }),
  };
  const context = vm.createContext({
    window, document, URL,
    setTimeout: (fn) => { timers.set(++serial, fn); return serial; },
    clearTimeout: (id) => timers.delete(id),
  });
  const run = () => new vm.Script(script).runInContext(context);
  const flush = () => {
    const pending = [...timers.values()];
    timers.clear();
    pending.forEach((fn) => fn());
  };
  run();
  function load() {
    // Model the upstream get_data behavior, including its separate raw query field.
    window.goatcounter.get_data = (vars) => ({
      p: vars.path, t: vars.title, q: location.search, r: document.referrer,
    });
    window.goatcounter.count = (vars) => hits.push(window.goatcounter.get_data(vars));
    loadedScript.onload();
    flush();
  }
  function navigate(path, method = 'pushState') {
    assert.equal(history[method]({}, '', path), 'native-result');
    flush();
  }
  return { window, document, listeners, hits, load, navigate, run, flush, setLocation, loadedScript };
}

test('header and visible footer retain website destinations', () => {
  const header = readFileSync(new URL('./header.html', import.meta.url), 'utf8');
  const destinations = [...header.matchAll(/href="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(destinations, [
    'https://bokfri.viblo.se/', 'https://bokfri.viblo.se/',
    'https://bokfri.viblo.se/download/', 'https://bokfri.viblo.se/help/',
    'https://forum.bokfri.viblo.se/',
  ]);
  assert.match(footer, /class="bokfri-site-footer"/);
  assert.match(footer, /https:\/\/www.gnu.org\/licenses\/gpl-3.0.html/);
});

test('async loading disables automatic counting and counts the initial visit once', () => {
  const b = browser();
  assert.equal(b.window.goatcounter.no_onload, true);
  assert.equal(b.window.goatcounter.no_events, true);
  assert.equal(b.loadedScript.async, true);
  assert.equal(b.loadedScript.src, 'https://gc.zgo.at/count.js');
  assert.equal(b.loadedScript.attributes['data-goatcounter'], 'https://bokfri.goatcounter.com/count');
  b.navigate('/tags');
  assert.equal(b.hits.length, 0, 'do not count before the external library has loaded');
  b.load();
  assert.deepEqual(b.hits.map((h) => h.p), ['/forum/tags']);
  b.run(); b.flush();
  assert.equal(b.hits.length, 1, 'loading the footer twice must not create another tracker');
});

test('SPA changes, post-number grouping, replaceState and back/forward deduplicate', () => {
  const b = browser(); b.load();
  b.navigate('/?q=PRIVATE');
  b.navigate('/d/123-public-title');
  b.navigate('/d/123-public-title/2', 'replaceState');
  b.navigate('/d/123-public-title/last#PRIVATE');
  b.navigate('/d/456-other');
  b.navigate('/tags');
  b.setLocation('/d/456-other'); b.listeners.popstate(); b.flush();
  b.listeners.popstate(); b.flush();
  assert.deepEqual(b.hits.map((h) => h.p), [
    '/forum/', '/forum/d/123', '/forum/d/456', '/forum/tags', '/forum/d/456',
  ]);
});

test('private/unknown routes and query/hash/referrer details never enter payloads', () => {
  const b = browser('/?token=PRIVATE#PRIVATE'); b.load();
  for (const path of ['/admin', '/admin/users', '/settings', '/notifications', '/u/testuser',
    '/confirm/PRIVATE', '/reset/PRIVATE', '/unknown']) b.navigate(path);
  assert.equal(b.hits.length, 1);
  b.navigate('/t/allmant?token=PRIVATE#PRIVATE');
  for (const hit of b.hits) {
    assert.equal(hit.q, '');
    assert.equal(hit.r, 'https://example.org');
    assert.equal(hit.t, 'Bokfri forum');
    assert.ok(!JSON.stringify(hit).includes('PRIVATE'));
  }
  b.document.referrer = 'not-a-url';
  b.navigate('/following');
  assert.equal(b.hits.at(-1).r, '');
});

test('hidden pages wait for visibility; a missing upstream API fails closed', () => {
  const b = browser('/confirm/PRIVATE'); b.load();
  assert.equal(b.hits.length, 0);
  b.document.visibilityState = 'hidden'; b.navigate('/tags');
  assert.equal(b.hits.length, 0);
  b.document.visibilityState = 'visible'; b.listeners.visibilitychange(); b.flush();
  assert.equal(b.hits.length, 1);
  const missing = browser();
  missing.loadedScript.onload(); missing.flush();
  assert.equal(missing.hits.length, 0);
});
