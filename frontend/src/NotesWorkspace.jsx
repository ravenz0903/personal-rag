import React, { useEffect, useRef, useState } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { api, post } from './api';
import { put, downloadBlob } from './localTime';
export default function NotesWorkspace({ domain, selectedId, workspaceKey }) {
  const [notes, setNotes] = useState([]), [selected, setSelected] = useState(null), [archived, setArchived] = useState(false), [error, setError] = useState('');
  const includesArea = area => domain === 'ALL' ? ['ACADEMICS','CAREER'].includes(area) : domain === area;
  const load = () => api(`/api/local/notes?domain=${domain}&archived=${archived}`).then(setNotes).catch(e => setError(e.message));
  useEffect(() => { setSelected(null); load(); }, [domain, archived]);
  useEffect(() => { if (selectedId) open(selectedId); }, [selectedId]);
  async function open(id) { try { setSelected({ ...await api(`/api/local/notes/${id}?domain=${domain}`), editorKey: Date.now() }); setError(''); } catch (e) { setError(e.message); } }
  async function create() { try { const note = await post('/api/local/notes', { domain: domain === 'ALL' ? 'ACADEMICS' : domain, title: 'Untitled note', content: { type: 'doc', content: [{ type: 'paragraph' }] }, tags: [] }); setArchived(false); setSelected(note); load(); } catch (e) { setError(e.message); } }
  return <section>{error && <p className="alert" role="alert">{error}</p>}<div className="local-toolbar"><button className="primary" onClick={create}>+ New note</button><label><input type="checkbox" checked={archived} onChange={e => setArchived(e.target.checked)}/>Archived</label></div><div className="notes-workspace"><aside className="notes-list" aria-label="Notes">{notes.filter(note => includesArea(note.domain)).map(note => <button key={note.id} className={selected?.id === note.id ? 'selected' : ''} onClick={() => open(note.id)}>{note.pinned ? '★ ' : ''}{note.title}<small>{note.tags.join(', ')}</small></button>)}{!notes.length && <p className="hint">Your notes live here.</p>}</aside>{selected && includesArea(selected.domain) ? <NoteEditor key={selected.id + selected.editorKey} note={selected} workspaceKey={workspaceKey} onSaved={load} onReload={() => open(selected.id)}/> : <p className="hint">Choose a note, or start a fresh one.</p>}</div></section>;
}
function NoteEditor({ note, workspaceKey, onSaved, onReload }) {
  const key = `folio-note-draft:${workspaceKey}:${note.id}`;
  const [linkOpen, setLinkOpen] = useState(false), [link, setLink] = useState('');
  const [value, setValue] = useState(() => { try { const draft = JSON.parse(localStorage.getItem(key)); return draft || note; } catch { return note; } });
  const [status, setStatus] = useState(() => localStorage.getItem(key) ? 'Recovered a local draft. Review it before retrying save.' : 'Saved'), [stopped, setStopped] = useState(() => !!localStorage.getItem(key)), [dirty, setDirty] = useState(() => !!localStorage.getItem(key));
  const latest = useRef(value), revision = useRef(value.revision), inFlight = useRef(false), serial = useRef(0), active = useRef(true), stoppedRef = useRef(stopped);
  useEffect(() => () => { active.current = false; }, []);
  const editor = useEditor({ extensions: [StarterKit.configure({ heading: { levels: [1, 2, 3] }, strike: false, code: false, codeBlock: false, underline: false, link: { openOnClick: false, protocols: ['http', 'https', 'mailto'] } }), TaskList, TaskItem.configure({ nested: false })], content: value.content, editorProps: { attributes: { 'aria-label': 'Note content', role: 'textbox', 'aria-multiline': 'true' } }, onUpdate: ({ editor }) => edit({ content: editor.getJSON() }) });
  function edit(fields) { const next = { ...latest.current, ...fields }; latest.current = next; serial.current++; setValue(next); setDirty(true); localStorage.setItem(key, JSON.stringify(next)); }
  async function save(force = false) {
    if (inFlight.current || stoppedRef.current && !force) return;
    inFlight.current = true; setStatus('Saving…');
    try {
      while (active.current) {
        const before = serial.current, payload = { ...latest.current, revision: revision.current };
        const saved = await put(api, `/api/local/notes/${note.id}`, payload); revision.current = saved.revision; latest.current.revision = saved.revision;
        if(before !== serial.current)localStorage.setItem(key, JSON.stringify(latest.current));
        if (before === serial.current) { localStorage.removeItem(key); if (active.current) { setDirty(false); setStatus('Saved'); } onSaved(); break; }
      }
    } catch (e) { stoppedRef.current = true; if (active.current) { setStopped(true); setStatus(`Couldn’t save: ${e.message} Your draft is kept on this computer.`); } }
    finally { inFlight.current = false; }
  }
  useEffect(() => { if (!dirty || stopped) return; const timer = setTimeout(() => save(), 700); return () => clearTimeout(timer); }, [value, dirty, stopped]);
  async function retry() { stoppedRef.current = false; setStopped(false); await save(true); }
  async function exportNote() { try { const blob = await api(`/api/local/notes/${note.id}/markdown?domain=${note.domain}`, { download: true }); downloadBlob(blob, 'Folio-note.md'); } catch (e) { setStatus(e.message); } }
  return <div className="note-editor"><input aria-label="Note title" className="note-title" maxLength={255} value={value.title} onChange={e => edit({ title: e.target.value })}/><div className="local-toolbar"><input aria-label="Note tags" placeholder="Tags, separated by commas" defaultValue={value.tags.join(', ')} onBlur={e => edit({ tags: e.target.value.split(',').map(t => t.trim()).filter(Boolean) })}/><label><input type="checkbox" checked={value.pinned} onChange={e => edit({ pinned: e.target.checked })}/>Pin</label><label><input type="checkbox" checked={value.archived} onChange={e => edit({ archived: e.target.checked })}/>Archive</label><button disabled={dirty} onClick={exportNote}>Export Markdown</button></div><div className="editor-toolbar" role="toolbar" aria-label="Formatting">{[['Paragraph', () => editor?.chain().focus().setParagraph().run()], ['H1', () => editor?.chain().focus().toggleHeading({ level: 1 }).run()], ['H2', () => editor?.chain().focus().toggleHeading({ level: 2 }).run()], ['H3', () => editor?.chain().focus().toggleHeading({ level: 3 }).run()], ['Bold', () => editor?.chain().focus().toggleBold().run()], ['Italic', () => editor?.chain().focus().toggleItalic().run()], ['Bullets', () => editor?.chain().focus().toggleBulletList().run()], ['Numbered', () => editor?.chain().focus().toggleOrderedList().run()], ['Checklist', () => editor?.chain().focus().toggleTaskList().run()], ['Link', () => { setLink(editor?.getAttributes('link').href || ''); setLinkOpen(true); }], ['Remove link', () => editor?.chain().focus().unsetLink().run()]].map(([label, action]) => <button key={label} onClick={action}>{label}</button>)}</div>{linkOpen && <div className="local-toolbar"><input autoFocus aria-label="Link address" maxLength={2048} value={link} onChange={e => setLink(e.target.value)} placeholder="https://example.com"/><button onClick={() => { if (/^(https?:\/\/|mailto:)[^\s]+$/i.test(link)) { editor?.chain().focus().setLink({ href: link }).run(); setLinkOpen(false); } else setStatus('Use an https, http, or mailto link.'); }}>Apply link</button><button onClick={() => setLinkOpen(false)}>Cancel</button></div>}<EditorContent editor={editor}/><div className="save-status" role="status">{status}</div>{stopped && <div className="local-toolbar"><button onClick={retry}>Retry save</button><button onClick={() => { localStorage.removeItem(key); onReload(); }}>Discard draft & reload</button></div>}<p className="hint">Autosaves after you pause typing. Notes are stored locally.</p></div>;
}
