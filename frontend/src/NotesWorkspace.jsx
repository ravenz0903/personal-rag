import React, { useEffect, useRef, useState } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { api, post } from './api';
import { put, downloadBlob } from './localTime';
export default function NotesWorkspace({ domain, selectedId, workspaceKey }) {
  const [notes, setNotes] = useState([]), [selected, setSelected] = useState(null), [archived, setArchived] = useState(false), [error, setError] = useState('');
  const selectionVersion = useRef(0);
  const listVersion = useRef(0), currentView = useRef(''); currentView.current = `${domain}:${archived}`;
  const includesArea = area => domain === 'ALL' ? ['ACADEMICS','CAREER'].includes(area) : domain === area;
  const load = () => { const view = `${domain}:${archived}`; if (currentView.current !== view) return Promise.resolve(); const version = ++listVersion.current; return api(`/api/local/notes?domain=${domain}&archived=${archived}`).then(result => { if (version === listVersion.current && currentView.current === view) setNotes(result); }).catch(e => { if (version === listVersion.current && currentView.current === view) setError(e.message); }); };
  useEffect(() => { load(); return () => { listVersion.current++; }; }, [domain, archived]);
  useEffect(() => { setSelected(null); return () => { selectionVersion.current++; }; }, [domain]);
  useEffect(() => { if (selectedId) open(selectedId); }, [selectedId]);
  async function open(id) { const version = ++selectionVersion.current; try { const note = await api(`/api/local/notes/${id}?domain=${domain}`); if (version !== selectionVersion.current) return; setSelected({ ...note, editorKey: Date.now() }); setError(''); } catch (e) { if (version === selectionVersion.current) setError(e.message); } }
  async function create() { const version = ++selectionVersion.current; try { const note = await post('/api/local/notes', { domain: domain === 'ALL' ? 'ACADEMICS' : domain, title: 'Untitled note', content: { type: 'doc', content: [{ type: 'paragraph' }] }, tags: [] }); if (version !== selectionVersion.current) return; setArchived(false); setSelected(note); if (!archived) load(); } catch (e) { if (version === selectionVersion.current) setError(e.message); } }
  return <section>{error && <p className="alert" role="alert">{error}</p>}<div className="local-toolbar"><button className="primary" onClick={create}>+ New note</button><label><input type="checkbox" checked={archived} onChange={e => { selectionVersion.current++; setSelected(null); setArchived(e.target.checked); }}/>Archived</label></div><div className="notes-workspace"><aside className="notes-list" aria-label="Notes">{notes.filter(note => includesArea(note.domain) && note.archived === archived).map(note => <button key={note.id} className={selected?.id === note.id ? 'selected' : ''} onClick={() => open(note.id)}>{note.pinned ? '★ ' : ''}{note.title}<small>{note.tags.join(', ')}</small></button>)}{!notes.length && <p className="hint">Your notes live here.</p>}</aside>{selected && includesArea(selected.domain) ? <NoteEditor key={selected.id + selected.editorKey} note={selected} workspaceKey={workspaceKey} onSaved={load} onReload={() => open(selected.id)}/> : <p className="hint">Choose a note, or start a fresh one.</p>}</div></section>;
}
function NoteEditor({ note, workspaceKey, onSaved, onReload }) {
  const key = `folio-note-draft:${workspaceKey}:${note.id}`;
  const nativeDrafts = window.folioDesktop?.noteDrafts;
  const recoveryError = useRef('');
  function readDraft() { try { const raw = nativeDrafts?.get(note.id); if (raw != null) return raw; } catch (error) { recoveryError.current = error.message; } try { return localStorage.getItem(key); } catch (error) { recoveryError.current = error.message; return null; } }
  const [recovered] = useState(() => { try { const raw = readDraft(), draft = JSON.parse(raw); if (!draft || draft.id !== note.id || draft.domain !== note.domain || !Array.isArray(draft.tags) || draft.content?.type !== 'doc') return null; if (nativeDrafts && raw) { if (nativeDrafts.get(note.id) == null) nativeDrafts.set(note.id, raw); localStorage.removeItem(key); } return draft; } catch (error) { recoveryError.current = error.message; return null; } });
  function persistDraft(next) { try { const raw = JSON.stringify(next); if (nativeDrafts) { nativeDrafts.set(note.id, raw); localStorage.removeItem(key); } else localStorage.setItem(key, raw); recoveryError.current = ''; } catch (error) { recoveryError.current = error.message; setStatus(`Couldn’t keep a recovery draft: ${error.message}. Keep this window open until your changes save.`); } }
  function removeDraft(expected) { if (nativeDrafts) { if (!nativeDrafts.remove(note.id, expected)) return; } else if (expected !== undefined && localStorage.getItem(key) !== expected) return; localStorage.removeItem(key); }
  const [linkOpen, setLinkOpen] = useState(false), [link, setLink] = useState('');
  const [value, setValue] = useState(() => recovered || note);
  const [status, setStatus] = useState(() => recovered ? 'Recovered a local draft. Review it before retrying save.' : recoveryError.current ? `Couldn’t read a recovery draft: ${recoveryError.current}. Your saved note is shown.` : 'Saved'), [stopped, setStopped] = useState(() => !!recovered), [dirty, setDirty] = useState(() => !!recovered);
  const latest = useRef(value), revision = useRef(value.revision), inFlight = useRef(false), serial = useRef(0), active = useRef(true), stoppedRef = useRef(stopped);
  useEffect(() => () => { active.current = false; }, []);
  const editor = useEditor({ extensions: [StarterKit.configure({ heading: { levels: [1, 2, 3] }, strike: false, code: false, codeBlock: false, underline: false, link: { openOnClick: false, protocols: ['http', 'https', 'mailto'] } }), TaskList, TaskItem.configure({ nested: false })], content: value.content, editorProps: { attributes: { 'aria-label': 'Note content', role: 'textbox', 'aria-multiline': 'true' } }, onUpdate: ({ editor }) => edit({ content: editor.getJSON() }) });
  function edit(fields) { const next = { ...latest.current, ...fields }; latest.current = next; serial.current++; setValue(next); setDirty(true); persistDraft(next); }
  async function save(force = false) {
    if (inFlight.current || stoppedRef.current && !force) return;
    inFlight.current = true; setStatus('Saving…');
    try {
      while (active.current) {
        const before = serial.current, payload = { ...latest.current, revision: revision.current }, expectedDraft = readDraft();
        const saved = await put(api, `/api/local/notes/${note.id}`, payload); revision.current = saved.revision; latest.current.revision = saved.revision;
        if(before !== serial.current && active.current)persistDraft(latest.current);
        if (before === serial.current) { if (expectedDraft !== null) removeDraft(expectedDraft); if (active.current) { setDirty(false); setStatus('Saved'); } onSaved(); break; }
      }
    } catch (e) { stoppedRef.current = true; if (active.current) { setStopped(true); setStatus(`Couldn’t save: ${e.message} ${recoveryError.current ? 'Keep this window open; a recovery draft could not be saved.' : 'Your draft is kept on this computer.'}`); } }
    finally { inFlight.current = false; }
  }
  useEffect(() => { if (!dirty || stopped) return; const timer = setTimeout(() => save(), 700); return () => clearTimeout(timer); }, [value, dirty, stopped]);
  async function retry() { stoppedRef.current = false; setStopped(false); await save(true); }
  async function exportNote() { try { const blob = await api(`/api/local/notes/${note.id}/markdown?domain=${note.domain}`, { download: true }); downloadBlob(blob, 'Folio-note.md'); } catch (e) { setStatus(e.message); } }
  return <div className="note-editor"><input aria-label="Note title" className="note-title" maxLength={255} value={value.title} onChange={e => edit({ title: e.target.value })}/><div className="local-toolbar"><input aria-label="Note tags" placeholder="Tags, separated by commas" defaultValue={value.tags.join(', ')} onBlur={e => edit({ tags: e.target.value.split(',').map(t => t.trim()).filter(Boolean) })}/><label><input type="checkbox" checked={value.pinned} onChange={e => edit({ pinned: e.target.checked })}/>Pin</label><label><input type="checkbox" checked={value.archived} onChange={e => edit({ archived: e.target.checked })}/>Archive</label><button disabled={dirty} onClick={exportNote}>Export Markdown</button></div><div className="editor-toolbar" role="toolbar" aria-label="Formatting">{[['Paragraph', () => editor?.chain().focus().setParagraph().run()], ['H1', () => editor?.chain().focus().toggleHeading({ level: 1 }).run()], ['H2', () => editor?.chain().focus().toggleHeading({ level: 2 }).run()], ['H3', () => editor?.chain().focus().toggleHeading({ level: 3 }).run()], ['Bold', () => editor?.chain().focus().toggleBold().run()], ['Italic', () => editor?.chain().focus().toggleItalic().run()], ['Bullets', () => editor?.chain().focus().toggleBulletList().run()], ['Numbered', () => editor?.chain().focus().toggleOrderedList().run()], ['Checklist', () => editor?.chain().focus().toggleTaskList().run()], ['Link', () => { setLink(editor?.getAttributes('link').href || ''); setLinkOpen(true); }], ['Remove link', () => editor?.chain().focus().unsetLink().run()]].map(([label, action]) => <button key={label} onClick={action}>{label}</button>)}</div>{linkOpen && <div className="local-toolbar"><input autoFocus aria-label="Link address" maxLength={2048} value={link} onChange={e => setLink(e.target.value)} placeholder="https://example.com"/><button onClick={() => { if (/^(https?:\/\/|mailto:)[^\s]+$/i.test(link)) { editor?.chain().focus().setLink({ href: link }).run(); setLinkOpen(false); } else setStatus('Use an https, http, or mailto link.'); }}>Apply link</button><button onClick={() => setLinkOpen(false)}>Cancel</button></div>}<EditorContent editor={editor}/><div className="save-status" role="status">{status}</div>{stopped && <div className="local-toolbar"><button onClick={retry}>Retry save</button><button onClick={() => { removeDraft(); onReload(); }}>Discard draft & reload</button></div>}<p className="hint">Autosaves after you pause typing. Notes are stored locally.</p></div>;
}
