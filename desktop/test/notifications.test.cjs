const test = require('node:test'), assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createNotifier } = require('../notifications.cjs');

function fixture() {
  class Notification extends EventEmitter {
    static isSupported() { return true; }
    static handleActivation(callback) { this.activate = callback; }
    constructor(options) { super(); this.options = options; Notification.last = this; }
    show() { this.emit('show'); }
    close() { this.emit('close', { reason: 'applicationHidden' }); }
  }
  const notifications = new Set(), opened = [], messages = [];
  const notify = createNotifier({ Notification, notifications, platform: 'win32', icon: () => 'icon', iconPath: 'C:/Folio/folio.png', showWindow: () => opened.push('window'), report: (...args) => messages.push(args) });
  return { Notification, notifications, opened, messages, notify };
}
const flushed = () => new Promise(resolve => setImmediate(resolve));

test('timed-out banner stays clickable in Notification Centre and opens its original record once', async () => {
  const f = fixture();
  await f.notify('Private reminder', () => f.opened.push('task-1'));
  const notification = f.Notification.last;
  notification.emit('close', { reason: 'timedOut' });
  assert.equal(f.notifications.has(notification), true);
  f.Notification.activate({ type: 'click', arguments: `type=click&tag=${notification.options.id}` });
  notification.emit('click'); // Electron can deliver both activation paths.
  await flushed();
  assert.equal(f.opened.filter(value => value === 'task-1').length, 1);
  assert.equal(f.opened[0], 'window');
  assert.equal(f.opened.at(-1), 'window');
  assert.equal(f.notifications.size, 0);
});

test('multiple reminders use their own activation targets and unknown activations do nothing', async () => {
  const f = fixture();
  await f.notify('First', () => f.opened.push('first'));
  const first = f.Notification.last;
  await f.notify('Second', () => f.opened.push('second'));
  const second = f.Notification.last;
  f.Notification.activate({ type: 'click', arguments: 'type=click&tag=unknown' });
  assert.deepEqual(f.opened, []);
  first.emit('close', { reason: 'timedOut' });
  second.emit('close', { reason: 'timedOut' });
  f.Notification.activate({ type: 'click', arguments: `type=click&tag=${first.options.id}` });
  await flushed();
  assert.ok(f.opened.includes('first'));
  assert.ok(!f.opened.includes('second'));
  assert.equal(f.notifications.size, 1);
});

test('explicitly dismissed notifications cannot activate and normal banner click still works', async () => {
  const f = fixture();
  for (const reason of ['userCanceled', 'applicationHidden']) {
    await f.notify('Dismissed', () => f.opened.push('dismissed'));
    const n = f.Notification.last;
    n.emit('close', { reason });
    f.Notification.activate({ type: 'click', arguments: `type=click&tag=${n.options.id}` });
  }
  await flushed();
  assert.deepEqual(f.opened, []);
  assert.equal(f.notifications.size, 0);
  await f.notify('Visible', () => f.opened.push('visible'));
  f.Notification.last.emit('click');
  await flushed();
  assert.ok(f.opened.includes('visible'));
});

test('Windows XML escapes content and record-open failures are handled', async () => {
  const f = fixture();
  await f.notify('A & B < "C"', () => { throw Error('Record unavailable'); });
  const n = f.Notification.last;
  assert.match(n.options.toastXml, /A &amp; B &lt; &quot;C&quot;/);
  assert.match(n.options.toastXml, new RegExp(`launch="type=click&amp;tag=${n.options.id}"`));
  n.emit('click');
  await flushed();
  assert.ok(f.messages.some(args => args.includes('Record unavailable')));
});

test('workspace restoration clears timed-out notification targets', async () => {
  const f = fixture();
  await f.notify('Old workspace', () => f.opened.push('old-record'));
  const n = f.Notification.last;
  n.emit('close', { reason: 'timedOut' });
  f.notify.clear();
  f.Notification.activate({ type: 'click', arguments: `type=click&tag=${n.options.id}` });
  n.emit('click');
  await flushed();
  assert.equal(f.notifications.size, 0);
  assert.deepEqual(f.opened, []);
});
