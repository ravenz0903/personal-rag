import React, { useMemo } from 'react';
import { ChevronLeft, ChevronRight, Plus, Check, CalendarDays, CheckSquare, GraduationCap } from 'lucide-react';
import { dayKey, localDay, monthDays, moveMonth, calendarEvents, filterCalendarEvents } from './calendar.js';

const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const monthLabel = month => month.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
const dateLabel = date => date.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });

export function CalendarSidebar({ month, setMonth, selected, selectDay, sources, setSources }) {
  return <div className="calendar-sidebar">
    <div className="mini-month-heading"><strong>{monthLabel(month)}</strong><div><button className="icon-button" aria-label="Mini calendar previous month" onClick={() => setMonth(moveMonth(month, -1))}><ChevronLeft size={14}/></button><button className="icon-button" aria-label="Mini calendar next month" onClick={() => setMonth(moveMonth(month, 1))}><ChevronRight size={14}/></button></div></div>
    <div className="mini-weekdays">{weekdays.map(day => <span key={day}>{day.slice(0, 2)}</span>)}</div>
    <div className="mini-days">{monthDays(month).map(date => { const key = dayKey(date); return <button key={key} aria-label={`Select ${dateLabel(date)}`} aria-pressed={selected === key} className={`${date.getMonth() !== month.getMonth() ? 'outside' : ''} ${key === dayKey(new Date()) ? 'today' : ''} ${selected === key ? 'selected' : ''}`} onClick={() => selectDay(key)}>{date.getDate()}</button>; })}</div>
    <span className="nav-label">MY CALENDARS</span>
    <label className="calendar-source"><input type="checkbox" checked={sources.tasks} onChange={e => setSources({ ...sources, tasks: e.target.checked })}/><CheckSquare size={14}/><span>Task deadlines</span></label>
    <label className="calendar-source assessments"><input type="checkbox" checked={sources.assessments} onChange={e => setSources({ ...sources, assessments: e.target.checked })}/><GraduationCap size={15}/><span>Assessments</span></label>
    <label className="calendar-source subdued"><input type="checkbox" checked={sources.completed} onChange={e => setSources({ ...sources, completed: e.target.checked })}/><span>Show completed</span></label>
    <p className="calendar-sidebar-hint">Deadlines appear automatically.<br/>Choose Personal to see private tasks.</p>
  </div>;
}

export default function Calendar({ month, setMonth, selected, selectDay, sources, setSources, domain, tasks, courses, loading, busy, onCreate, onToggle, onScore }) {
  const events = useMemo(() => filterCalendarEvents(calendarEvents(tasks, courses), domain, sources), [tasks, courses, domain, sources]);
  const days = monthDays(month);
  const agenda = events.filter(event => event.date === selected);
  function today() { const date = new Date(); setMonth(new Date(date.getFullYear(), date.getMonth(), 1, 12)); selectDay(dayKey(date)); }
  return <section className="calendar-view" aria-label="Workspace calendar">
    <div className="calendar-header"><div><h1>{monthLabel(month)}</h1><p>Your deadlines, in one place.</p></div><div className="calendar-controls"><button className="secondary" onClick={today}>Today</button><button className="icon-button" aria-label="Previous month" onClick={() => setMonth(moveMonth(month, -1))}><ChevronLeft size={18}/></button><button className="icon-button" aria-label="Next month" onClick={() => setMonth(moveMonth(month, 1))}><ChevronRight size={18}/></button><span className="calendar-view-label">Month</span><button className="primary" onClick={() => onCreate(selected)}><Plus size={15}/>New task</button></div></div>
    <div className="calendar-mobile-filters"><label><input type="checkbox" checked={sources.tasks} onChange={e => setSources({ ...sources, tasks: e.target.checked })}/>Tasks</label><label><input type="checkbox" checked={sources.assessments} onChange={e => setSources({ ...sources, assessments: e.target.checked })}/>Assessments</label><label><input type="checkbox" checked={sources.completed} onChange={e => setSources({ ...sources, completed: e.target.checked })}/>Completed</label></div>
    {loading && <p className="hint" role="status">Loading deadlines…</p>}
    <div className="calendar-scroll"><div className="month-calendar"><div className="calendar-weekdays">{weekdays.map(day => <span key={day}>{day}</span>)}</div><div className="calendar-grid" style={{ gridTemplateRows: `repeat(${days.length / 7}, minmax(110px, 1fr))` }}>{days.map(date => {
      const key = dayKey(date), dayEvents = events.filter(event => event.date === key);
      return <div key={key} className={`calendar-day ${date.getMonth() !== month.getMonth() ? 'outside' : ''} ${key === selected ? 'selected' : ''} ${key === dayKey(new Date()) ? 'today' : ''}`}>
        <div className="calendar-day-top"><button className="calendar-day-number" aria-label={`View ${dateLabel(date)}`} aria-pressed={key === selected} onClick={() => selectDay(key)}>{date.getDate() === 1 ? date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : date.getDate()}</button><button className="calendar-add" aria-label={`Add task on ${dateLabel(date)}`} onClick={() => onCreate(key)}><Plus size={13}/></button></div>
        <div className="calendar-events">{dayEvents.slice(0, 3).map(event => <button key={event.id} className={`calendar-event ${event.domain.toLowerCase()} ${event.kind} ${event.completed ? 'completed' : ''}`} title={event.title} aria-label={`${event.title}, ${event.kind}, ${dateLabel(date)}`} onClick={() => selectDay(key)}>{event.completed && <Check size={10}/>}<span>{event.title}</span></button>)}{dayEvents.length > 3 && <button className="calendar-more" onClick={() => selectDay(key)}>+{dayEvents.length - 3} more</button>}</div>
      </div>;
    })}</div></div></div>
    <div className="calendar-agenda"><div className="agenda-heading"><div><span className="eyebrow">SELECTED DAY</span><h2>{localDay(selected).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}</h2></div><button className="text-button" onClick={() => onCreate(selected)}><Plus size={14}/>Add task</button></div>{agenda.length ? agenda.map(event => <div className="agenda-event" key={event.id}><span className={`agenda-dot ${event.kind}`}/><div><strong>{event.title}</strong><p>{event.course || event.domain.toLowerCase()} · {localDay(event.date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} · {new Date(event.dueDate).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}{event.completed ? ' · completed' : ''}</p></div><button className="secondary" disabled={busy} onClick={() => event.kind === 'task' ? onToggle(event.record) : onScore(event.record)}>{event.kind === 'task' ? event.completed ? 'Reopen' : 'Mark complete' : 'Record score'}</button></div>) : <div className="agenda-empty"><CalendarDays size={16}/><span>No deadlines for this day. Add a task when you need one.</span></div>}</div>
    <p className="calendar-note">Dates use your device's timezone. Tasks without a deadline stay in the task list.</p>
  </section>;
}
