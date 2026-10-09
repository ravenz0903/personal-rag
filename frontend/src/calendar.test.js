import test from 'node:test';
import assert from 'node:assert/strict';
import { dayKey, monthDays, moveMonth, calendarEvents, filterCalendarEvents } from './calendar.js';

test('calendar weeks align on Sunday and include all dates across year and leap boundaries', () => {
  const february = monthDays(new Date(2024, 1, 1, 12));
  assert.equal(february[0].getDay(), 0);
  assert.equal(february.at(-1).getDay(), 6);
  assert.ok(february.some(date => dayKey(date) === '2024-02-29'));
  assert.equal(february.length % 7, 0);
  assert.equal(dayKey(moveMonth(new Date(2026, 11, 31), 1)), '2027-01-01');
  assert.equal(dayKey(moveMonth(new Date(2026, 0, 31), -1)), '2025-12-01');
});
test('deadlines use the local calendar day, retain completion, and omit undated or invalid records', () => {
  const date = new Date(2026, 9, 10, 23, 30);
  const events = calendarEvents([
    { id: 'a', title: 'Local deadline', domain: 'PERSONAL', dueDate: date.toISOString(), status: 'COMPLETED' },
    { id: 'b', title: 'Undated' }, { id: 'c', title: 'Bad date', dueDate: 'bad' },
  ], [{ code: 'CS101', assessments: [{ id: 'd', title: 'Exam', dueDate: date.toISOString(), earnedScore: 0 }] }]);
  assert.equal(events.length, 2);
  assert.ok(events.every(event => event.date === '2026-10-10'));
  assert.ok(events.every(event => event.completed));
  assert.equal(events.find(event => event.kind === 'assessment').course, 'CS101');
});
test('changing areas excludes Personal immediately, and source/completion filters are independent', () => {
  const events = [
    { id: 'private', domain: 'PERSONAL', kind: 'task' },
    { id: 'career', domain: 'CAREER', kind: 'task', completed: true },
    { id: 'exam', domain: 'ACADEMICS', kind: 'assessment' },
  ];
  const sources = { tasks: true, assessments: true, completed: true };
  assert.deepEqual(filterCalendarEvents(events, 'ALL', sources).map(e => e.id), ['career', 'exam']);
  assert.deepEqual(filterCalendarEvents(events, 'PERSONAL', sources).map(e => e.id), ['private']);
  assert.deepEqual(filterCalendarEvents(events, 'ALL', { ...sources, completed: false }).map(e => e.id), ['exam']);
  assert.deepEqual(filterCalendarEvents(events, 'ALL', { ...sources, assessments: false }).map(e => e.id), ['career']);
});
