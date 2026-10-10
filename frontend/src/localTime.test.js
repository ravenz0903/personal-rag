import test from 'node:test';
import assert from 'node:assert/strict';
import { wallTime, toInstant, addDays, weekDays, dateInZone } from './localTime.js';
test('workspace zone differs from the machine zone', () => { assert.equal(toInstant('2026-10-11T09:30', 'Asia/Kolkata'), '2026-10-11T04:00:00.000Z'); assert.equal(wallTime('2026-10-11T04:00:00Z', 'Asia/Kolkata'), '2026-10-11T09:30'); });
test('spring DST gap is rejected instead of silently moving time', () => assert.throws(() => toInstant('2026-03-08T02:30', 'America/New_York'), /does not exist/));
test('autumn DST overlap chooses the earlier occurrence', () => assert.equal(toInstant('2026-11-01T01:30', 'America/New_York'), '2026-11-01T05:30:00.000Z'));
test('all-day keys cross leap-day and month boundaries without timezone conversion', () => { assert.equal(addDays('2028-02-28', 2), '2028-03-01'); assert.equal(addDays('2026-12-31', 1), '2027-01-01'); });
test('both week starts are honored', () => { assert.equal(weekDays('2026-11-01', 0)[0], '2026-11-01'); assert.equal(weekDays('2026-11-01', 1)[0], '2026-10-26'); });
test('a UTC deadline can belong to the previous local day', () => assert.equal(dateInZone('2026-10-11T02:00:00Z', 'America/New_York'), '2026-10-10'));
