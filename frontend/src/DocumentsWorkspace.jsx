import React from 'react';
import { Upload, FileText, Download, FolderOpen, Search, X } from 'lucide-react';
const names = { ACADEMICS: 'Academics', CAREER: 'Career', PERSONAL: 'Personal' };
export default function DocumentsWorkspace({ documents, domain, config, busy, inputRef, onUpload, onDownload, indexes, indexing, onIndex, onCancelIndex, dateText }) {
  return <section>
    <div className="library-intro"><div><h3>Your personal library</h3><p>PDF & Markdown · up to 5 MB</p></div>
      <button className="primary" disabled={busy || domain === 'ALL'} onClick={() => inputRef.current.click()}><Upload size={15}/>{busy ? 'Processing…' : 'Add document'}</button>
      <input ref={inputRef} hidden type="file" accept=".pdf,.md,.markdown" onChange={onUpload}/>
    </div>
    {domain === 'ALL' && <p className="hint">Choose an area above to upload a document. Personal stays outside this view.</p>}
    {documents.length ? <div className="document-grid">{documents.map(doc => <article className="document-card" key={doc.id}>
      <div className="document-preview"><FileText size={34} strokeWidth={1}/><span>{doc.contentType === 'application/pdf' ? 'PDF' : 'MD'}</span></div>
      <div className="document-info"><h3>{doc.fileName}</h3><span className={`tag ${doc.domain.toLowerCase()}`}>{names[doc.domain]}</span>
        <p>{config.desktopMode ? indexes[doc.id] ? `${indexes[doc.id]} searchable passages` : 'Stored original' : doc.domain === 'PERSONAL' ? 'Private storage · AI excluded' : `${doc.chunkCount} searchable passages`} · {dateText(doc.updatedAt)}</p>
        <button className="text-button" disabled={busy || !doc.storagePath} onClick={() => onDownload(doc)}><Download size={13}/>Download original</button>
        {config.desktopMode && config.aiEnabled && config.aiConfigured && doc.storagePath && (indexing === doc.id
          ? <><p role="status">Preparing local search…</p><button className="text-button" onClick={onCancelIndex}><X size={13}/>Stop indexing</button></>
          : <button className="text-button" disabled={Boolean(indexing)} onClick={() => onIndex(doc)}><Search size={13}/>{indexes[doc.id] ? 'Rebuild search' : 'Make searchable'}</button>)}
      </div>
    </article>)}</div> : <div className="empty-state"><FolderOpen size={28}/><h3>Give your notes a home.</h3><p>Choose an area, then upload notes to build your library.</p></div>}
  </section>;
}
