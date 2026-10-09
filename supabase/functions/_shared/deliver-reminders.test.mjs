import test from 'node:test';
import assert from 'node:assert/strict';
import { deliverReminders, trustedPushEndpoint } from './deliver-reminders.mjs';

const row = { task_id: 'task', subscription_id: 'device', remind_at: 'time', endpoint: 'https://fcm.googleapis.com/send/example' };
test('ignores cancelled or rescheduled tasks; removes unsafe endpoints', async () => {
  let sent = 0, removed = 0;
  await deliverReminders([row, { ...row, endpoint: 'https://localhost/private' }], {
    stillEligible: async () => false, send: async () => { sent++; }, removeSubscription: async () => { removed++; }
  });
  assert.equal(sent, 0); assert.equal(removed, 1);
  assert.equal(trustedPushEndpoint('https://fcm.googleapis.com.evil.test/send'), false);
});
test('marks successful delivery, drops expired subscriptions, retries transient failures', async () => {
  const sent = [], removed = [];
  for (const status of [201, 410, 503]) await deliverReminders([row], {
    stillEligible: async () => true, send: async () => status,
    markSent: async () => sent.push(status), removeSubscription: async () => removed.push(status)
  });
  assert.deepEqual(sent, [201]); assert.deepEqual(removed, [410]);
});
test('one unavailable device does not block other devices', async () => {
  const completed = [];
  await deliverReminders([row, { ...row, subscription_id: 'other' }], {
    stillEligible: async () => true,
    send: async entry => { if (entry.subscription_id === 'device') throw new Error('offline'); return 201; },
    markSent: async entry => completed.push(entry.subscription_id)
  });
  assert.deepEqual(completed, ['other']);
});
