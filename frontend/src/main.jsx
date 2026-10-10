import React, { useEffect, useRef, useState, lazy, Suspense } from 'react';
import { createRoot } from 'react-dom/client';
import { BookOpen, BriefcaseBusiness, Check, CheckCheck, ChevronDown, ChevronRight, Circle, FileText, Flame, FolderOpen, Home, LayoutList, LockKeyhole, LogOut, Menu, MoreHorizontal, Plus, Search, Settings, Sparkles, Upload, X, ArrowUpRight, ArrowUp, Undo2, RefreshCw, Download, Sun, PanelLeftClose, CalendarDays } from 'lucide-react';
import { configure, api, post, streamChat } from './api';
import Calendar, { CalendarSidebar } from './Calendar.jsx';
import ReminderSettings, { disableDeviceReminders } from './ReminderSettings.jsx';
import TaskSearch from './TaskSearch.jsx';
import TaskWorkspace from './TaskWorkspace';
import CalendarWorkspace from './CalendarWorkspace';
const NotesWorkspace = lazy(() => import('./NotesWorkspace'));
import WorkspaceSearch from './WorkspaceSearch';
import RecordManager from './RecordManager';
import ProgressWorkspace from './ProgressWorkspace';
import TodayAgenda from './TodayAgenda';
import { wallTime, toInstant, put } from './localTime';
import './local.css';
import DesktopSettings from './DesktopSettings.jsx';
import { dayKey, localDay } from './calendar';
import './style.css';
import './dark.css';

const pages = [ ['Today', Home], ['Tasks', CheckCheck], ['Calendar', CalendarDays], ['Academics', BookOpen], ['Personal', Flame], ['Career', BriefcaseBusiness], ['Notes', FileText], ['Progress', LayoutList], ['Documents', FolderOpen], ['Assistant', Sparkles] ];
const domains = ['ALL', 'ACADEMICS', 'PERSONAL', 'CAREER'];
const domainNames = { ALL: 'Academics + Career', ACADEMICS: 'Academics', PERSONAL: 'Personal', CAREER: 'Career' };
const statuses = ['WISHLIST', 'APPLIED', 'OA_RECEIVED', 'INTERVIEW_SCHEDULED', 'OFFER', 'REJECTED', 'WITHDRAWN'];
const pretty = text => text?.replaceAll('_', ' ').toLowerCase().replace(/^./, c => c.toUpperCase());
const eventLabel = event => { try { const record = JSON.parse(event.payloadAfter || event.payloadBefore || '{}'); return record.title || record.name || record.company || ''; } catch { return ''; } };
const localInput = value => value ? dayKey(new Date(value)) + 'T' + new Date(value).toTimeString().slice(0, 5) : '';

function App() {
  const [boot, setBoot] = useState(null), [session, setSession] = useState(null), [bootError, setBootError] = useState('');
  const [page, setPage] = useState(() => new URLSearchParams(location.search).get('view') === 'tasks' ? 'Tasks' : 'Today'), [domain, setDomain] = useState('ALL'), [menu, setMenu] = useState(false);
  const [tasksRecords, setTasks] = useState([]), [habitsRecords, setHabits] = useState([]), [careerRecords, setCareer] = useState([]), [coursesRecords, setCourses] = useState([]), [documentsRecords, setDocuments] = useState([]);
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [modal, setModal] = useState(null), [card, setCard] = useState(null), [busy, setBusy] = useState(false), [filter, setFilter] = useState('');
  const [messages, setMessages] = useState([]), [question, setQuestion] = useState(''), [asking, setAsking] = useState(false);
  const [aiDomain, setAiDomain] = useState('ACADEMICS'), [historyRecords, setHistory] = useState([]);
  const [assistantMode, setAssistantMode] = useState('notes');
  const [searchOpen, setSearchOpen] = useState(false);
  const [profile, setProfile] = useState({ displayName: 'Your workspace', timezone: 'Asia/Kolkata', weekStart: 0, remindersEnabled: true }), [selectedRecord, setSelectedRecord] = useState(null), [workspaceKey, setWorkspaceKey] = useState('local');
  const localInput = value => wallTime(value, profile.timezone);
  const dateText = value => value ? new Date(value).toLocaleDateString(undefined, { timeZone: profile.timezone, month: 'short', day: 'numeric' }) : 'No deadline';
  const abort = useRef(null), fileInput = useRef(null), chatBottom = useRef(null), loadVersion = useRef(0);
  const [calendarMonth, setCalendarMonth] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1, 12));
  const [calendarDate, setCalendarDate] = useState(() => dayKey(new Date()));
  const [calendarSources, setCalendarSources] = useState({ tasks: true, assessments: true, completed: false });
  function changeCalendarMonth(date) { setCalendarMonth(date); setCalendarDate(dayKey(date)); }
  function selectCalendarDay(key) { setCalendarDate(key); const date = localDay(key); setCalendarMonth(new Date(date.getFullYear(), date.getMonth(), 1, 12)); }
  const includesArea = area => domain === 'ALL' ? ['ACADEMICS','CAREER'].includes(area) : domain === area;
  const tasks = tasksRecords.filter(t => includesArea(t.domain)), habits = domain === 'PERSONAL' ? habitsRecords : [], career = includesArea('CAREER') ? careerRecords : [], courses = includesArea('ACADEMICS') ? coursesRecords : [], documents = documentsRecords.filter(d => includesArea(d.domain)), history = historyRecords.filter(h => h.domain === (domain === 'ALL' ? 'ACADEMICS' : domain));
  const canEnter = boot && (!boot.config.authEnabled || session);
  useEffect(() => {
    function shortcut(event) {
      if (canEnter && !modal && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault(); setMenu(false); setSearchOpen(open => !open);
      }
    }
    window.addEventListener('keydown', shortcut);
    return () => window.removeEventListener('keydown', shortcut);
  }, [canEnter, modal]);

  async function start() {
    setBootError('');
    try {
      const ready = await configure();
      if (ready.config.desktopMode) { let prefs = await api('/api/local/settings'); if (!prefs.initialized) prefs = await put(api, '/api/local/settings', { ...prefs, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Kolkata' }); setProfile(prefs); window.folioDesktop?.settings().then(s => setWorkspaceKey(s.dataPath)); }
      setBoot(ready);
      if (ready.client) { const { data } = await ready.client.auth.getSession(); setSession(data.session); }
    } catch (e) { setBootError(e.message); }
  }
  useEffect(() => { start(); }, []);
  useEffect(() => {
    if (!boot?.client) return;
    const { data } = boot.client.auth.onAuthStateChange((_event, current) => setSession(current));
    return () => data.subscription.unsubscribe();
  }, [boot]);
  useEffect(() => { if (canEnter) refresh(); }, [canEnter, domain, page]);
  useEffect(() => { chatBottom.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, [messages]);
  useEffect(() => () => abort.current?.abort(), []);
  useEffect(() => {
    const key = e => {
      if (e.key === 'Escape' && !busy) { discard(); setMenu(false); }
      if (e.key === 'Tab' && modal) {
        const elements = [...document.querySelectorAll('.modal-card button:not(:disabled), .modal-card input:not([type="hidden"]), .modal-card select, .modal-card textarea')];
        if (!elements.length) return;
        const first = elements[0], last = elements.at(-1);
        if (e.shiftKey && (document.activeElement === first || !elements.includes(document.activeElement))) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && (document.activeElement === last || !elements.includes(document.activeElement))) { e.preventDefault(); first.focus(); }
      }
    };
    window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key);
  }, [card, modal, busy]);
  useEffect(() => {
    if (!notice) return; const timer = setTimeout(() => setNotice(''), 5000); return () => clearTimeout(timer);
  }, [notice]);

  async function refresh() {
    const version = ++loadVersion.current;
    setLoading(true); setError('');
    const selected = domain === 'ALL' ? ['ACADEMICS', 'CAREER'] : [domain];
    try {
      const [t, h, c, a, d] = await Promise.all([
        boot?.config.desktopMode ? api(`/api/local/tasks?domain=${domain}`) : Promise.all(selected.map(scope => api(`/api/tracker/tasks?domain=${scope}`))).then(groups => groups.flat()),
        selected.includes('PERSONAL') ? api('/api/tracker/habits?domain=PERSONAL') : Promise.resolve([]),
        selected.includes('CAREER') ? api('/api/tracker/career?domain=CAREER') : Promise.resolve([]),
        selected.includes('ACADEMICS') ? api('/api/workspace/courses') : Promise.resolve([]),
        api(`/api/ingest/documents${domain === 'ALL' ? '' : `?domain=${domain}`}`),
      ]);
      if (version !== loadVersion.current) return;
      setTasks(t); setHabits(h); setCareer(c); setCourses(a); setDocuments(d);
      if (page === 'Settings') setHistory(await api(`/api/workspace/events?domain=${domain === 'ALL' ? 'ACADEMICS' : domain}`));
    } catch (e) { if (version === loadVersion.current) setError(e.message); }
    finally { if (version === loadVersion.current) setLoading(false); }
  }
  function navigate(next) {
    setPage(next); setMenu(false); setFilter(''); setSelectedRecord(null);
    if (next === 'Academics') setDomain('ACADEMICS');
    else if (next === 'Personal') setDomain('PERSONAL');
    else if (next === 'Career') setDomain('CAREER');
    else if (['Today', 'Tasks', 'Calendar'].includes(next)) setDomain('ALL');
  }
  async function action(fn, message) {
    setBusy(true); setError('');
    try { await fn(); if (message) setNotice(message); await refresh(); return true; }
    catch (e) { setError(e.message); return false; }
    finally { setBusy(false); }
  }
  async function openRecord(item) {
    setSearchOpen(false); setSelectedRecord(item); setDomain(item.domain);
    try { if (item.kind === 'task') { const task = await api(`/api/local/open/task/${item.id}`); setPage('Tasks'); setModal({ type: 'TASK_UPDATE', task }); } else if (item.kind === 'event') setPage('Calendar'); else if (item.kind === 'note') setPage('Notes'); else if (item.kind === 'course') setPage('Academics'); else if (item.kind === 'application') setPage('Career'); else if (item.kind === 'document') { setPage('Documents'); const docs = await api(`/api/ingest/documents?domain=${item.domain}`); const doc = docs.find(d => d.id === item.id); if (doc) await download(doc); } } catch(e) { setError(e.message); }
  }
  useEffect(() => { if (!boot?.config.desktopMode) return; const params = new URLSearchParams(location.search), id = params.get('record'), kind = params.get('kind'); if (id && ['task','event'].includes(kind)) api(`/api/local/open/${kind}/${id}`).then(record => openRecord({ ...record, kind })).catch(e => setError(e.message)); window.history.replaceState(null, '', '/'); }, [boot]);
  async function discard() {
    const token = card?.confirmationToken; setModal(null); setCard(null);
    if (token) await api(`/api/capture/cancel/${token}`, { method: 'DELETE' }).catch(() => {});
  }
  async function submitForm(e) {
    e.preventDefault(); const values = Object.fromEntries(new FormData(e.currentTarget)); setBusy(true); setError('');
    try {
      if (modal.type === 'COURSE') { await post('/api/workspace/courses', { ...values, targetGrade: Number(values.targetGrade) }); setModal(null); await refresh(); return; }
      if (modal.type === 'CATEGORY') { await post(`/api/workspace/courses/${modal.course.id}/categories`, { ...values, weight: Number(values.weight), dropLowestN: Number(values.dropLowestN) }); setModal(null); await refresh(); return; }
      if (modal.type === 'SCORE') { await post(`/api/workspace/assessments/${modal.assessment.id}/score`, { score: Number(values.score) }); setModal(null); await refresh(); return; }
      if (modal.type === 'CAPTURE') { setCard(await post('/api/capture/preview', { input: values.input, domain: values.domain })); return; }
      const payload = { ...values }; delete payload.domain;
      if (payload.dueDate) payload.dueDate = boot.config.desktopMode ? toInstant(payload.dueDate, profile.timezone) : new Date(payload.dueDate).toISOString(); else delete payload.dueDate;
      if (payload.remindAt) payload.remindAt = boot.config.desktopMode ? toInstant(payload.remindAt, profile.timezone) : new Date(payload.remindAt).toISOString(); else delete payload.remindAt;
      if (modal.type === 'TASK_UPDATE') { payload.taskId = modal.task.id; if (boot.config.desktopMode) payload.expectedVersion = modal.task.version; }
      if (payload.tags !== undefined) payload.tags = payload.tags.split(',').map(tag => tag.trim()).filter(Boolean);
      if (payload.maxScore) payload.maxScore = Number(payload.maxScore);
      if (modal.type === 'HABIT') payload.timezone = boot.config.desktopMode ? profile.timezone : Intl.DateTimeFormat().resolvedOptions().timeZone;
      setCard(await post('/api/capture/structured', { domain: values.domain || domain, entityType: modal.type, payload }));
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  async function commit() {
    if (await action(() => post(`/api/capture/commit/${card.confirmationToken}`), 'Record saved to your workspace.')) { setCard(null); setModal(null); }
  }
  async function upload(e) {
    const file = e.target.files[0]; e.target.value = ''; if (!file) return;
    if (file.size > 5 * 1024 * 1024) { setError('Choose a file smaller than 5 MB.'); return; }
    if (domain === 'ALL') { setError('Choose Academics, Personal, or Career before uploading.'); return; }
    await action(async () => {
      let result;
      if (/\.(md|markdown)$/i.test(file.name)) result = await post('/api/ingest/markdown', { path: file.name, content: await file.text(), domain });
      else if (/\.pdf$/i.test(file.name)) { const body = new FormData(); body.append('file', file); body.append('domain', domain); result = await api('/api/ingest/upload', { method: 'POST', body }); }
      else throw new Error('Choose a PDF or Markdown file.');
      setNotice(result.status === 'SKIPPED' ? 'This document is already up to date.' : boot.config.aiEnabled === false || domain === 'PERSONAL' ? 'Document stored locally. No content was sent to AI.' : 'Document added to your searchable library.');
    });
  }
  async function download(doc) {
    await action(async () => {
      const blob = await api(`/api/ingest/documents/${doc.id}/download?domain=${doc.domain}`, { download: true });
      const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = doc.fileName; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }
  async function ask(e) {
    e.preventDefault(); const query = question.trim(); if (!query || asking) return;
    const selected = aiDomain; setQuestion(''); setAsking(true); setError('');
    const id = crypto.randomUUID(); setMessages(previous => [...previous, { role: 'user', text: query }, { id, role: 'assistant', text: '', citations: [], domain: selected }]);
    abort.current = new AbortController();
    try {
      if (assistantMode === 'actions') {
        const draft = await post('/api/assistant/actions', { input: query, domain: selected });
        setMessages(previous => previous.map(message => message.id === id ? { ...message, text: draft.summary + '. Review the draft before saving.' } : message));
        setCard(draft); setModal({ type: draft.entityType });
        return;
      }
      await streamChat(query, selected, (event, data) => {
        setMessages(previous => previous.map(message => message.id !== id ? message : event === 'citations' ? { ...message, citations: data } : ['chunk', 'message'].includes(event) ? { ...message, text: message.text + data } : message));
      }, abort.current.signal);
    } catch (e) { if (e.name !== 'AbortError') { setError(e.message); setMessages(previous => previous.map(message => message.id === id ? { ...message, failure: e.message } : message)); } }
    finally { setAsking(false); }
  }

  if (!boot || bootError) return <div className="gate"><div className="brand-symbol">f</div><h1>Your space, thoughtfully organized.</h1><p>{bootError || 'Starting your workspace…'}</p>{bootError && <button className="primary" onClick={start}>Try again</button>}</div>;
  if (!canEnter) return <Login client={boot.client} onSession={setSession} />;

  const pending = tasks.filter(task => !task.archived && !['COMPLETED', 'CANCELLED'].includes(task.status));
  const visibleTasks = (page === 'Today' ? pending : tasks).filter(task => task.title.toLowerCase().includes(filter.toLowerCase()));
  const openForm = type => { setError(''); setModal({ type }); };
  const pageIcon = pages.find(([name]) => name === page)?.[1] || Settings;
  const PageIcon = pageIcon;
  const descriptions = { Notes: 'A place for your thoughts. Saved on this computer.', Progress: 'Small steps, over time.', Calendar: 'Your deadlines, in one place.', Today: 'A little clarity for the day ahead.', Tasks: 'Make room for what matters. One task at a time.', Academics: 'Your courses, progress, and next good question.', Personal: 'Small rituals. Steady progress. A space just for you.', Career: 'Keep your next chapter moving.', Documents: 'Everything you want to remember, in one place.', Assistant: 'Think it through. Then put it into motion.', Settings: 'Your workspace, your boundaries.' };

  return <div className={`app-shell ${page === 'Calendar' ? 'calendar-mode' : ''}`}>
    {menu && <button className="sidebar-scrim" aria-label="Close menu" onClick={() => setMenu(false)} />}
    <aside className={`sidebar ${menu ? 'mobile-open' : ''}`}>
      <div className="workspace-brand"><span className="brand-symbol small">f</span><div><strong>Folio</strong><span>Personal workspace</span></div></div>
      <button className="search-launch" onClick={() => { setMenu(false); setSearchOpen(true); }}><Search size={15}/> {boot.config.desktopMode ? 'Search workspace' : 'Find a task'} <kbd>Ctrl K</kbd></button>
      <span className="nav-label">WORKSPACE</span>
      <nav>{pages.filter(([name]) => (name !== 'Assistant' || boot.config.aiEnabled !== false) && (boot.config.desktopMode || !['Notes','Progress'].includes(name))).map(([name, Icon]) => <button key={name} className={`nav-item ${page === name ? 'selected' : ''}`} onClick={() => navigate(name)}><Icon size={17}/>{name}{name === 'Personal' && <LockKeyhole className="nav-trailing" size={12}/>}</button>)}</nav>
      {page === 'Calendar' && !boot.config.desktopMode && <CalendarSidebar month={calendarMonth} setMonth={changeCalendarMonth} selected={calendarDate} selectDay={selectCalendarDay} sources={calendarSources} setSources={setCalendarSources}/>}
      <div className="sidebar-bottom"><button className={`nav-item ${page === 'Settings' ? 'selected' : ''}`} onClick={() => navigate('Settings')}><Settings size={17}/>Settings</button><div className="profile"><span className="avatar">{(session?.user?.email || 'You')[0].toUpperCase()}</span><div><strong>{profile.displayName}</strong><span>{boot.config.authEnabled ? 'Private · signed in' : boot.config.desktopMode ? 'On this computer' : 'Local preview'}</span></div>{boot.client && <button className="icon-button" aria-label="Sign out" onClick={async () => { abort.current?.abort(); setTasks([]); setHabits([]); setDocuments([]); setMessages([]); setCareer([]); setCourses([]); setHistory([]); loadVersion.current++; await disableDeviceReminders().catch(() => {}); await boot.client.auth.signOut(); }}><LogOut size={15}/></button>}</div></div>
    </aside>
    <main className="main-pane">
      <header className="topbar"><button className="icon-button mobile-menu" aria-label="Open navigation" onClick={() => setMenu(true)}><Menu size={19}/></button><div className="breadcrumb"><span>Your workspace</span><ChevronRight size={12}/><PageIcon size={14}/><strong>{page}</strong></div></header>
      <div className={`page-content ${page === 'Calendar' ? 'calendar-content' : ''}`}>
        {!boot.config.authEnabled && !boot.config.desktopMode && <div className="dev-banner">Local development preview</div>}
        <div className={`page-cover cover-${page.toLowerCase()}`}><div className="cover-line one"/><div className="cover-line two"/><div className="cover-orbit"/><span>MAKE SPACE FOR WHAT MATTERS</span></div>
        <div className="page-heading"><div className="page-emblem"><PageIcon size={28} strokeWidth={1.5}/></div><div className="title-row"><h1>{page === 'Today' ? 'A fresh page.' : page}</h1>{!['Settings', 'Assistant', 'Notes', 'Progress', ...(boot.config.desktopMode ? ['Calendar'] : [])].includes(page) && <button className="primary" onClick={() => openForm(page === 'Academics' ? 'COURSE' : page === 'Personal' ? 'HABIT' : page === 'Career' ? 'CAREER' : 'TASK')}><Plus size={16}/>{page === 'Academics' ? 'New course' : page === 'Personal' ? 'New habit' : page === 'Career' ? 'New application' : 'New task'}</button>}</div><p>{descriptions[page]}</p></div>
        {error && <div role="alert" className="alert"><span>{error}</span><button className="icon-button" aria-label="Dismiss error" onClick={() => setError('')}><X size={16}/></button></div>}
        {notice && <div role="status" className="notice"><Check size={16}/>{notice}</div>}

        {['Today', 'Tasks', 'Documents', 'Calendar', 'Notes', 'Progress'].includes(page) && <div className="view-toolbar"><div className="domain-tabs">{domains.map(scope => <button key={scope} className={domain === scope ? 'active' : ''} onClick={() => setDomain(scope)}>{scope === 'PERSONAL' && <LockKeyhole size={12}/>} {domainNames[scope]}</button>)}</div>{boot.config.aiEnabled !== false && !['Documents', 'Calendar'].includes(page) && <button className="text-button" onClick={() => openForm('CAPTURE')}><Sparkles size={14}/>Quick capture</button>}</div>}

        {page === 'Calendar' && !boot.config.desktopMode && <Calendar domain={domain} month={calendarMonth} setMonth={changeCalendarMonth} selected={calendarDate} selectDay={selectCalendarDay} sources={calendarSources} setSources={setCalendarSources} tasks={tasks} courses={courses} loading={loading} busy={busy} onCreate={date => { selectCalendarDay(date); setError(''); setModal({ type: 'TASK', dueDate: date + 'T09:00' }); }} onToggle={task => action(() => post('/api/tracker/tasks/' + task.id + '/toggle'), 'Task updated.')} onScore={assessment => { setError(''); setModal({ type: 'SCORE', assessment }); }}/>}

        {boot.config.desktopMode && page === 'Tasks' && <TaskWorkspace tasks={tasks} timezone={profile.timezone} busy={busy} onChange={(task, fields) => action(() => put(api, `/api/local/tasks/${task.id}`, { domain: task.domain, version: task.version, ...fields }), 'Task updated.')} onEdit={task => { setError(''); setModal({ type: 'TASK_UPDATE', task }); }} onCreate={parent => { setError(''); setModal({ type: 'TASK', parent }); }}/>}
        {boot.config.desktopMode && page === 'Calendar' && <CalendarWorkspace domain={domain} profile={profile} tasks={tasks} courses={courses} onRefresh={refresh} selectedId={selectedRecord?.kind === 'event' ? selectedRecord.id : null} onEditTask={task => { setError(''); setModal({ type: 'TASK_UPDATE', task }); }}/>}
        {boot.config.desktopMode && page === 'Notes' && <Suspense fallback={<p role="status">Opening notes…</p>}><NotesWorkspace domain={domain} workspaceKey={workspaceKey} selectedId={selectedRecord?.kind === 'note' ? selectedRecord.id : null}/></Suspense>}
        {boot.config.desktopMode && page === 'Progress' && <ProgressWorkspace domain={domain} courses={courses}/>}
        {boot.config.desktopMode && page === 'Today' && <TodayAgenda domain={domain} timezone={profile.timezone} tasks={tasks} courses={courses} habits={habits} busy={busy} onLog={id => action(() => post(`/api/tracker/habits/${id}/log`), 'Today is logged.')} onEdit={task => setModal({ type: 'TASK_UPDATE', task })} onCalendar={id => { setPage('Calendar'); setSelectedRecord(id ? { id, kind: 'event' } : null); }}/>}
        {boot.config.desktopMode && ['Academics', 'Personal', 'Career'].includes(page) && <RecordManager key={page} kind={page === 'Academics' ? 'course' : page === 'Personal' ? 'habit' : 'application'} onRefresh={refresh} selectedId={selectedRecord?.id}/>}
        {page === 'Today' && <><div className="welcome-callout"><Sun size={23}/><div><strong>{new Date().toLocaleDateString(undefined, { timeZone: profile.timezone, weekday: 'long', month: 'long', day: 'numeric' })}</strong><p>You don't have to do everything. Start with one thing that matters.</p></div></div><div className="stats-grid"><Stat label="Open tasks" value={pending.length} note="Room to make progress"/><Stat label="Courses" value={courses.length} note="Keep learning"/><Stat label="Applications" value={career.length} note="Your next chapter"/></div></>}

        {(page === 'Today' || page === 'Tasks' && !boot.config.desktopMode) && <section><SectionTitle icon={LayoutList} title={page === 'Today' ? 'On your mind' : 'All tasks'} count={visibleTasks.length}/><div className="table-toolbar"><span className="view-name"><LayoutList size={14}/>List view</span><label className="filter-input"><Search size={14}/><input id="task-search" value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter tasks…" aria-label="Filter tasks"/></label></div>{visibleTasks.length ? <div className="task-table"><div className="table-head"><span>Task</span><span>Area</span><span>Priority</span><span>Due</span></div>{visibleTasks.map(task => <div key={task.id} className={`task-row ${task.status === 'COMPLETED' ? 'completed' : ''}`}><div className="task-title"><button aria-label={`${task.status === 'COMPLETED' ? 'Reopen' : 'Complete'} ${task.title}`} className="task-check" disabled={busy} onClick={() => action(() => boot.config.desktopMode ? put(api, `/api/local/tasks/${task.id}`, { domain: task.domain, version: task.version, status: task.status === 'COMPLETED' ? 'PENDING' : 'COMPLETED' }) : post(`/api/tracker/tasks/${task.id}/toggle`), 'Task updated.')} >{task.status === 'COMPLETED' ? <Check size={13}/> : null}</button><button className="task-edit" aria-label={`Edit ${task.title}`} onClick={() => { setError(''); setModal({ type: 'TASK_UPDATE', task }); }}>{task.title}</button></div><span className={`tag ${task.domain.toLowerCase()}`}>{domainNames[task.domain]}</span><span className={`priority ${task.priority.toLowerCase()}`}>{pretty(task.priority)}</span><span className="due-date">{dateText(task.dueDate)}</span></div>)}</div> : <Empty icon={CheckCheck} title={loading ? 'Loading your tasks…' : 'A little breathing room.'} text="Add a task to give your next step a home." action={() => openForm('TASK')} label="Add your first task"/>}<button className="add-row" onClick={() => openForm('TASK')}><Plus size={15}/>New task</button></section>}

        {page === 'Academics' && <section><SectionTitle icon={BookOpen} title="Your courses" count={courses.length}/>{courses.length ? <div className="course-grid">{courses.map(course => <article className="course-card" key={course.id}><div className="course-top"><span className="tag academics">{course.code}</span><span>{course.term}</span></div><h3>{course.name}</h3><div className="grade-line"><strong>{course.grade.assessed ? `${course.grade.currentGrade}%` : '—'}</strong><span>current grade · target {course.targetGrade}%</span></div><div className="progress-track"><div style={{ width: `${Math.min(100, course.grade.currentGrade || 0)}%` }}/></div><div className="category-list">{course.categories.map(category => <span key={category.id}>{category.name}<b>{category.weight}%</b></span>)}</div><div className="course-actions"><button className="text-button" onClick={() => setModal({ type: 'CATEGORY', course })}><Plus size={13}/>Category</button><button className="text-button" disabled={!course.categories.length} onClick={() => setModal({ type: 'ASSESSMENT', course })}><Plus size={13}/>Assessment</button></div>{course.assessments.map(assessment => <div className="assessment-row" key={assessment.id}><span>{assessment.title}</span><button className="score-button" onClick={() => setModal({ type: 'SCORE', assessment })}>{assessment.earnedScore ?? 'Add score'}{assessment.earnedScore != null ? ` / ${assessment.maxScore}` : ''}</button></div>)}</article>)}</div> : <Empty icon={BookOpen} title="Your learning starts here." text="Add a course, set its grading categories, and track your progress." action={() => openForm('COURSE')} label="Add a course"/>}</section>}

        {page === 'Personal' && <section><div className="privacy-callout"><LockKeyhole size={18}/><div><strong>A space just for you.</strong><p>Personal records stay out of general views. Personal documents are stored without sending them to AI.</p></div></div><SectionTitle icon={Flame} title="Small rituals" count={habits.length}/>{habits.length ? <div className="habit-grid">{habits.map(({ habit, streak }) => <article className="habit-card" key={habit.id}><span className="habit-icon"><Flame size={22}/></span><h3>{habit.name}</h3><span className="muted">{pretty(habit.frequency)} · {habit.timezone}</span><div className="streak-number">{streak.currentStreak}<span>day streak</span></div><p className="muted">Best: {streak.longestStreak} days</p><button className={streak.completedToday ? 'secondary' : 'primary'} disabled={busy || streak.completedToday} onClick={() => action(() => post(`/api/tracker/habits/${habit.id}/log`), 'Today is logged. Keep going.')}>{streak.completedToday ? <Check size={15}/> : <Plus size={15}/>} {streak.completedToday ? 'Done for today' : 'Log today'}</button></article>)}</div> : <Empty icon={Flame} title="Small steps add up." text="Choose a ritual you want to make part of your day." action={() => openForm('HABIT')} label="Create a habit"/>}</section>}

        {page === 'Career' && <section><SectionTitle icon={BriefcaseBusiness} title="Your next chapter" count={career.length}/>{career.length ? <div className="career-list">{career.map(application => <article className="application-row" key={application.id}><span className="company-mark">{application.company[0].toUpperCase()}</span><div><h3>{application.company}</h3><p>{application.position}</p></div><select aria-label={`Status for ${application.company}`} disabled={busy} value={application.status} onChange={e => action(() => boot.config.desktopMode ? put(api, `/api/local/records/application/${application.id}`, { ...application, status: e.target.value }) : post(`/api/workspace/career/${application.id}/status`, { status: e.target.value }), 'Application stage updated.')}>{statuses.map(status => <option key={status} value={status}>{pretty(status)}</option>)}</select></article>)}</div> : <Empty icon={BriefcaseBusiness} title="Keep the possibilities together." text="Track an opportunity from your wishlist to your next offer." action={() => openForm('CAREER')} label="Add an application"/>}</section>}

        {page === 'Documents' && <section><div className="library-intro"><div><h3>Your personal library</h3><p>PDF & Markdown · up to 5 MB</p></div><button className="primary" disabled={busy || domain === 'ALL'} onClick={() => fileInput.current.click()}><Upload size={15}/>{busy ? 'Processing…' : 'Add document'}</button><input ref={fileInput} hidden type="file" accept=".pdf,.md,.markdown" onChange={upload}/></div>{domain === 'ALL' && <p className="hint">Choose an area above to upload a document. Personal stays outside this view.</p>}{documents.length ? <div className="document-grid">{documents.map(doc => <article className="document-card" key={doc.id}><div className="document-preview"><FileText size={34} strokeWidth={1}/><span>{doc.contentType === 'application/pdf' ? 'PDF' : 'MD'}</span></div><div className="document-info"><h3>{doc.fileName}</h3><span className={`tag ${doc.domain.toLowerCase()}`}>{domainNames[doc.domain]}</span><p>{boot.config.aiEnabled === false ? 'Stored original · AI postponed' : doc.domain === 'PERSONAL' ? 'Private storage · AI excluded' : `${doc.chunkCount} searchable passages`} · {dateText(doc.updatedAt)}</p><button className="text-button" disabled={busy || !doc.storagePath} onClick={() => download(doc)}><Download size={13}/>Download original</button></div></article>)}</div> : <Empty icon={FolderOpen} title="Give your notes a home." text="Choose an area, then upload notes to build your library."/>}</section>}

        {page === 'Assistant' && boot.config.aiEnabled !== false && <section className="assistant-section"><div className="assistant-scope"><select aria-label="Assistant mode" value={assistantMode} disabled={asking} onChange={e => setAssistantMode(e.target.value)}><option value="notes">Ask my notes</option><option value="actions">Tasks & calendar</option></select><span><LockKeyhole size={13}/> Area</span><select aria-label="AI document area" value={aiDomain} disabled={asking} onChange={e => setAiDomain(e.target.value)}><option value="ACADEMICS">Academics</option><option value="CAREER">Career</option></select><span className="quota-label">Free-tier AI</span></div>{!boot.config.aiConfigured && <div className="privacy-callout"><Sparkles size={18}/><p>Connect your Gemini free-tier key in the backend settings to enable answers. Your records work without it.</p></div>}<div className="conversation">{messages.length ? messages.map((message, index) => <div className={`chat-message ${message.role}`} key={message.id || index}><div className="message-label">{message.role === 'user' ? 'You' : <><Sparkles size={13}/>Folio · {domainNames[message.domain]}</>}</div><div className="message-text">{message.text || (message.failure ? '' : 'Finding the right passages…')}</div>{message.failure && <p className="message-error">{message.failure}</p>}{message.citations?.length > 0 && <div className="citations">{message.citations.map((citation, i) => <details key={i}><summary><FileText size={12}/>[S{i + 1}] {citation.documentId}{citation.pageNumber ? ` · p. ${citation.pageNumber}` : ''}</summary>{citation.headingPath && <strong>{citation.headingPath}</strong>}<p>{citation.content}</p></details>)}</div>}</div>) : <div className="assistant-empty"><span className="assistant-star"><Sparkles size={30} strokeWidth={1.4}/></span><h2>{assistantMode === 'actions' ? 'A little help moving things forward.' : 'A conversation with your notes.'}</h2><p>{assistantMode === 'actions' ? 'Create tasks, move deadlines, and schedule reminders. Review every change before it is saved.' : 'Ask a question. Get an answer grounded in your documents, with sources you can check.'}</p><div className="suggestions">{(assistantMode === 'actions' ? ['Add a revision task for tomorrow at 6 pm', 'Move my revision task to Friday at 5 pm', 'Remind me about my revision task tomorrow at 9 am'] : ['What are the key ideas in my notes?', 'Explain this topic with a simple example', 'Compare the approaches in my documents']).map(text => <button key={text} onClick={() => setQuestion(text)}>{text}<ArrowUpRight size={15}/></button>)}</div></div>}<div ref={chatBottom}/></div><form className="chat-composer" onSubmit={ask}><textarea rows={2} value={question} onChange={e => setQuestion(e.target.value)} placeholder={assistantMode === 'actions' ? 'Add a task, change a deadline, or set a reminder…' : 'Ask something from your notes…'} maxLength={2000} aria-label="Question" onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(e); } }}/>{asking ? <button type="button" className="send-button" aria-label="Stop answering" onClick={() => abort.current?.abort()}><X size={18}/></button> : <button className="send-button" aria-label="Ask question" disabled={!question.trim() || !boot.config.aiConfigured}><ArrowUp size={18}/></button>}</form><p className="assistant-footnote">{assistantMode === 'actions' ? 'Check the proposed task and times before approving. Enable device reminders in Settings.' : 'Answers can be mistaken. Check the cited passages.'} Free-tier Gemini content may be used to improve Google's products.</p></section>}

        {page === 'Settings' && <section>{window.folioDesktop ? <DesktopSettings profile={profile} onProfile={async prefs => { setProfile(await put(api, '/api/local/settings', prefs)); }}/> : <ReminderSettings/>}{!boot.config.desktopMode && <div className="settings-list"><Setting title="Private access" text={boot.config.authEnabled ? 'Only the configured workspace owner can access this backend.' : 'Development mode. Sign-in is required for cloud deployment.'}/><Setting title="Personal AI boundary" text="Personal documents are stored privately and excluded from cloud AI. Personal capture uses local rules."/><Setting title="Free services" text="No paid fallback. Gemini quotas stop AI requests; tracker records remain usable."/><Setting title="Storage" text="Cloud uses PostgreSQL and private document storage. Local development uses persistent files."/></div>}<SectionTitle icon={Undo2} title="Recent changes"/><div className="history-domain"><select aria-label="History area" value={domain === 'ALL' ? 'ACADEMICS' : domain} onChange={e => setDomain(e.target.value)}>{domains.filter(d => d !== 'ALL').map(d => <option key={d} value={d}>{domainNames[d]}</option>)}</select></div>{history.length ? history.map(event => <div className="history-row" key={event.id}><div><strong>{pretty(event.action)} · {pretty(event.entityType)} · {eventLabel(event)}</strong><p>{new Date(event.timestamp).toLocaleString()}</p></div>{!event.undone && event.action !== 'ROLLBACK' && <button className="text-button" disabled={busy} onClick={() => action(() => post(`/api/capture/rollback/${event.id}`), 'Change undone.')}><Undo2 size={13}/>Undo</button>}</div>) : <p className="hint">Your changes will appear here.</p>}</section>}
        {page === 'Today' && <div className="bottom-links"><button onClick={() => navigate('Documents')}><FolderOpen size={18}/><div><strong>Your library</strong><p>Keep your notes close.</p></div><ArrowUpRight size={17}/></button>{boot.config.aiEnabled !== false && <button onClick={() => navigate('Assistant')}><Sparkles size={18}/><div><strong>Ask your notes</strong><p>Make a little more sense of things.</p></div><ArrowUpRight size={17}/></button>}</div>}
        <footer>YOUR SPACE. YOUR PACE.<span>Folio</span></footer>
      </div>
    </main>
    {searchOpen && boot.config.desktopMode && <WorkspaceSearch onClose={() => setSearchOpen(false)} onSelect={openRecord}/>}
    {searchOpen && !boot.config.desktopMode && <TaskSearch onClose={() => setSearchOpen(false)} onSelect={task => { setSearchOpen(false); setError(''); setModal({ type: 'TASK_UPDATE', task }); }}/>}
    {modal && <div className="modal-overlay" onMouseDown={e => { if (e.target === e.currentTarget && !busy) discard(); }}><div className="modal-card" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div className="modal-top"><span className="tag academics">{card ? 'REVIEW BEFORE SAVING' : 'MAKE IT YOURS'}</span><button className="icon-button" aria-label="Close dialog" disabled={busy} onClick={discard}><X size={19}/></button></div><h2 id="modal-title">{card ? 'Does this look right?' : { TASK: 'A new task', TASK_UPDATE: 'Edit task', HABIT: 'A small ritual', CAREER: 'A new opportunity', COURSE: 'A new course', CATEGORY: 'A grading category', ASSESSMENT: 'A new assessment', SCORE: 'Record your score', CAPTURE: 'Get it out of your head.' }[modal.type]}</h2>{error && <div className="alert" role="alert">{error}</div>}{card ? <><p className="modal-description">{card.summary}</p><dl className="review-fields">{Object.entries(card.proposedPayload).filter(([key]) => !['expectedUpdatedAt', 'expectedVersion', 'taskId'].includes(key)).map(([key, value]) => <div key={key}><dt>{pretty(key.replace(/([A-Z])/g, ' $1'))}</dt><dd>{value === '' ? 'None' : key === 'parentId' ? tasks.find(t => t.id === value)?.title || 'Selected parent' : ['dueDate', 'remindAt'].includes(key) ? new Date(value).toLocaleString() : String(value)}</dd></div>)}</dl><p className="hint">This preview expires in 15 minutes. Nothing is saved until you approve.</p><div className="modal-actions"><button className="secondary" disabled={busy} onClick={discard}>Discard</button><button className="primary" disabled={busy} onClick={commit}><Check size={15}/>{busy ? 'Saving…' : 'Approve & save'}</button></div></> : <form onSubmit={submitForm}>
      {['TASK', 'TASK_UPDATE', 'CAPTURE'].includes(modal.type) && <Field label="Area"><select name="domain" disabled={modal.type === 'TASK_UPDATE'} defaultValue={modal.task?.domain || modal.parent?.domain || (domain === 'ALL' ? 'ACADEMICS' : domain)}>{domains.filter(d => d !== 'ALL').map(d => <option key={d} value={d}>{domainNames[d]}</option>)}</select></Field>}
      {['HABIT', 'CAREER', 'ASSESSMENT'].includes(modal.type) && <input type="hidden" name="domain" value={modal.type === 'HABIT' ? 'PERSONAL' : modal.type === 'CAREER' ? 'CAREER' : 'ACADEMICS'}/>}
      {['TASK', 'TASK_UPDATE', 'ASSESSMENT'].includes(modal.type) && <Field label="What needs doing?"><input name="title" defaultValue={modal.task?.title || ''} required maxLength={255} autoFocus placeholder="Give it a clear name"/></Field>}
      {['TASK', 'TASK_UPDATE'].includes(modal.type) && <Field label="Priority"><select name="priority" defaultValue={modal.task?.priority || 'MEDIUM'}><option>LOW</option><option>MEDIUM</option><option>HIGH</option><option>URGENT</option></select></Field>}
      {['TASK', 'TASK_UPDATE', 'ASSESSMENT'].includes(modal.type) && <Field label="Due date · optional"><input name="dueDate" type="datetime-local" defaultValue={modal.dueDate || localInput(modal.task?.dueDate)}/></Field>}
      {modal.type === 'TASK_UPDATE' && <><input type="hidden" name="domain" value={modal.task.domain}/><Field label="Status"><select name="status" defaultValue={modal.task.status}>{['PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].map(status => <option key={status} value={status}>{pretty(status)}</option>)}</select></Field></>}
      {['TASK', 'TASK_UPDATE'].includes(modal.type) && <><Field label="Description · optional"><textarea name="description" maxLength={2000} rows={2} defaultValue={modal.task?.description || ''}/></Field><Field label="Remind me · optional"><input name="remindAt" type="datetime-local" defaultValue={localInput(modal.task?.remindAt)}/></Field><p className="hint">Times use {profile.timezone}. Keep Folio running in the tray for reminders.</p></>}
      {boot.config.desktopMode && ['TASK','TASK_UPDATE'].includes(modal.type) && <><Field label="Tags · comma separated"><input name="tags" maxLength={1200} defaultValue={(modal.task?.tags || []).join(', ')} list="task-tags"/><datalist id="task-tags">{[...new Set(tasks.flatMap(t => t.tags || []))].map(tag => <option key={tag} value={tag}/>)}</datalist></Field><Field label="Repeat after completion"><select name="recurrence" defaultValue={modal.task?.recurrence || 'NONE'}>{['NONE','DAILY','WEEKDAYS','WEEKLY','MONTHLY'].map(v => <option key={v}>{v}</option>)}</select></Field><Field label="Parent task · optional"><select name="parentId" defaultValue={modal.parent?.id || modal.task?.parentId || ''}><option value="">None</option>{tasks.filter(t => !t.archived && !t.parentId && t.id !== modal.task?.id).map(t => <option key={t.id} value={t.id}>{t.title} · {t.domain.toLowerCase()}</option>)}</select></Field></>}
      {modal.type === 'HABIT' && <><Field label="Habit"><input name="name" required maxLength={255} autoFocus placeholder="Read for 20 minutes"/></Field><Field label="Frequency"><select name="frequency"><option value="DAILY">Every day</option><option value="WEEKDAYS">Weekdays</option><option value="WEEKENDS">Weekends</option></select></Field></>}
      {modal.type === 'CAREER' && <><Field label="Company"><input name="company" required maxLength={255} autoFocus/></Field><Field label="Role"><input name="position" required maxLength={255}/></Field><Field label="Stage"><select name="status">{statuses.map(status => <option key={status} value={status}>{pretty(status)}</option>)}</select></Field></>}
      {modal.type === 'COURSE' && <><Field label="Course code"><input name="code" required maxLength={32} autoFocus placeholder="CS310"/></Field><Field label="Course name"><input name="name" required maxLength={255} placeholder="Algorithms"/></Field><div className="form-two"><Field label="Term"><input name="term" required maxLength={64} placeholder="Fall 2026"/></Field><Field label="Target grade (%)"><input name="targetGrade" type="number" step="0.01" min="0" max="100" defaultValue="90" required/></Field></div></>}
      {modal.type === 'CATEGORY' && <><Field label="Category name"><input name="name" required maxLength={128} placeholder="Homework" autoFocus/></Field><div className="form-two"><Field label="Weight (%)"><input name="weight" type="number" min="0.01" max="100" step="0.01" required/></Field><Field label="Drop lowest scores"><input name="dropLowestN" type="number" min="0" defaultValue="0" required/></Field></div></>}
      {modal.type === 'ASSESSMENT' && <><input type="hidden" name="courseCode" value={modal.course.code}/><Field label="Category"><select name="categoryName">{modal.course.categories.map(category => <option key={category.id}>{category.name}</option>)}</select></Field><Field label="Maximum score"><input name="maxScore" type="number" step="0.01" min="0.01" max="99999" defaultValue="100" required/></Field></>}
      {modal.type === 'SCORE' && <Field label={`Earned points (out of ${modal.assessment.maxScore})`}><input name="score" type="number" step="0.01" min="0" max={modal.assessment.maxScore} defaultValue={modal.assessment.earnedScore ?? ''} required autoFocus/></Field>}
      {modal.type === 'CAPTURE' && <><Field label="Your thought"><textarea name="input" rows={4} maxLength={2000} required autoFocus placeholder="Read chapter 4 by Friday at 5 pm"/></Field><p className="hint">Academics and Career use Gemini. Personal stays local. Always check the proposed date and details.</p></>}
      <div className="modal-actions"><button type="button" className="secondary" disabled={busy} onClick={discard}>Cancel</button><button className="primary" disabled={busy}>{busy ? 'Working…' : ['COURSE', 'CATEGORY', 'SCORE'].includes(modal.type) ? 'Confirm & save' : 'Review before saving'}<ChevronRight size={15}/></button></div>
    </form>}</div></div>}
  </div>;
}

function Login({ client, onSession }) {
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  async function submit(e) {
    e.preventDefault(); setBusy(true); setError(''); const values = Object.fromEntries(new FormData(e.currentTarget));
    try { const { data, error } = await client.auth.signInWithPassword(values); if (error) throw error; onSession(data.session); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  return <div className="login-shell"><div className="login-art"><span className="brand-symbol">f</span><h1>A place for<br/>your next chapter.</h1><p>Your studies. Your rituals. Your possibilities.</p><div className="login-orbit"/></div><div className="login-form"><span className="eyebrow">YOUR PERSONAL WORKSPACE</span><h2>Welcome back.</h2><p>Sign in to pick up where you left off.</p><form onSubmit={submit}>{error && <div className="alert" role="alert">{error}</div>}<Field label="Email"><input name="email" type="email" autoComplete="username" required autoFocus/></Field><Field label="Password"><input name="password" type="password" autoComplete="current-password" required/></Field><button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Open my workspace'}<ChevronRight size={16}/></button></form><p className="login-note"><LockKeyhole size={13}/>A private workspace for its owner.</p></div></div>;
}
function Field({ label, children }) { return <label className="field"><span>{label}</span>{children}</label>; }
function Stat({ label, value, note }) { return <div className="stat"><span>{label}</span><strong>{value.toString().padStart(2, '0')}</strong><p>{note}</p></div>; }
function SectionTitle({ icon: Icon, title, count }) { return <div className="section-title"><h2>{Icon && <Icon size={17}/>} {title}{count !== undefined && <span className="count">{count}</span>}</h2></div>; }
function Empty({ icon: Icon, title, text, action, label }) { return <div className="empty-state"><Icon size={25} strokeWidth={1.3}/><h3>{title}</h3><p>{text}</p>{action && <button className="text-button" onClick={action}><Plus size={14}/>{label}</button>}</div>; }
function Setting({ title, text }) { return <div className="setting"><strong>{title}</strong><p>{text}</p></div>; }

createRoot(document.getElementById('root')).render(<App/>);
