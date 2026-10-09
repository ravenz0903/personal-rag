export const dayKey = value => {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};
export const localDay = key => new Date(`${key}T12:00:00`);
export function monthDays(month) {
  const first = new Date(month.getFullYear(), month.getMonth(), 1, 12);
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const count = Math.ceil((first.getDay() + days) / 7) * 7;
  return Array.from({ length: count }, (_, index) => new Date(first.getFullYear(), first.getMonth(), 1 - first.getDay() + index, 12));
}
export const moveMonth = (month, offset) => new Date(month.getFullYear(), month.getMonth() + offset, 1, 12);
export function filterCalendarEvents(events, scope, sources) {
  return events.filter(event => (scope === 'ALL' ? event.domain !== 'PERSONAL' : event.domain === scope) &&
    sources[event.kind === 'task' ? 'tasks' : 'assessments'] && (sources.completed || !event.completed));
}
export function calendarEvents(tasks, courses) {
  const taskEvents = tasks.filter(task => task.dueDate && dayKey(task.dueDate)).map(task => ({
    id: `task-${task.id}`, kind: 'task', title: task.title, domain: task.domain,
    date: dayKey(task.dueDate), dueDate: task.dueDate, completed: task.status === 'COMPLETED', record: task,
  }));
  const assessments = courses.flatMap(course => course.assessments.filter(a => a.dueDate && dayKey(a.dueDate)).map(a => ({
    id: `assessment-${a.id}`, kind: 'assessment', title: a.title, domain: 'ACADEMICS',
    date: dayKey(a.dueDate), dueDate: a.dueDate, completed: a.earnedScore != null, course: course.code, record: a,
  })));
  return [...taskEvents, ...assessments].sort((a, b) => new Date(a.dueDate) - new Date(b.dueDate) || a.title.localeCompare(b.title));
}
