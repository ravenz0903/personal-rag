import React, { useEffect, useRef, useState } from 'react';
import { Search, X, ChevronRight } from 'lucide-react';
import { api } from './api';

const areas = { ALL: 'Academics + Career', ACADEMICS: 'Academics', CAREER: 'Career', PERSONAL: 'Personal' };

export default function TaskSearch({ onClose, onSelect }) {
  const [query, setQuery] = useState(''), [area, setArea] = useState('ALL');
  const [tasks, setTasks] = useState([]), [loading, setLoading] = useState(true), [error, setError] = useState('');
  const dialog = useRef(null);
  const [opener] = useState(() => document.activeElement);
  useEffect(() => {
    return () => {
      if (opener?.isConnected && document.activeElement === document.body) {
        const rect = opener.getBoundingClientRect();
        if (rect.right > 0 && rect.left < window.innerWidth) opener.focus();
        else document.querySelector('[aria-label="Open navigation"]')?.focus();
      }
    };
  }, [opener]);
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    const scopes = area === 'ALL' ? ['ACADEMICS', 'CAREER'] : [area];
    Promise.all(scopes.map(scope => api('/api/tracker/tasks?domain=' + scope)))
      .then(results => { if (active) setTasks(results.flat()); })
      .catch(e => { if (active) { setTasks([]); setError(e.message); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [area]);
  const matches = tasks.filter(task => (area === 'ALL' ? task.domain !== 'PERSONAL' : task.domain === area) &&
    (task.title + ' ' + (task.description || '')).toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a, b) => Number(['COMPLETED', 'CANCELLED'].includes(a.status)) - Number(['COMPLETED', 'CANCELLED'].includes(b.status)) || a.title.localeCompare(b.title));
  function keyboard(event) {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); }
    if (event.key === 'Tab') {
      const controls = [...dialog.current.querySelectorAll('button:not(:disabled), input, select')];
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  }
  return <div className="modal-overlay task-search-overlay" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div ref={dialog} className="task-search-dialog" role="dialog" aria-modal="true" aria-labelledby="search-title" onKeyDown={keyboard}>
      <div className="task-search-heading"><h2 id="search-title">Find a task</h2><button className="icon-button" aria-label="Close task search" onClick={onClose}><X size={18}/></button></div>
      <label className="task-search-input"><Search size={18}/><input autoFocus aria-label="Search task titles and descriptions" value={query} onChange={event => setQuery(event.target.value)} placeholder="Search tasks…"/></label>
      <div className="task-search-meta"><select aria-label="Search task area" value={area} onChange={event => { setTasks([]); setArea(event.target.value); }}>{Object.entries(areas).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select><span aria-live="polite">{loading ? 'Searching…' : `${matches.length} ${matches.length === 1 ? 'task' : 'tasks'}`}</span></div>
      <div className="task-search-results">
        {error ? <p role="alert" className="message-error">{error}</p> : loading ? <p className="hint">Loading tasks…</p> : matches.length ? matches.slice(0, 50).map(task => <button className="task-search-result" key={task.id} onClick={() => onSelect(task)}><div><strong>{task.title}</strong><span>{areas[task.domain]} · {task.status.replaceAll('_', ' ').toLowerCase()}{task.dueDate && ` · ${new Date(task.dueDate).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`}</span></div><ChevronRight size={16}/></button>) : <p className="hint">{query ? 'No matching tasks. Try another name or area.' : 'No tasks in this area yet.'}</p>}
      </div>
      <p className="task-search-footnote">Open a result to edit it. Personal tasks appear only when you choose Personal.{matches.length > 50 && ' Showing the first 50 matches; type more to narrow the search.'}</p>
    </div>
  </div>;
}
