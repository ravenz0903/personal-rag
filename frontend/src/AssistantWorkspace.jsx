import React from 'react';
import { Sparkles, LockKeyhole, FileText, ArrowUpRight, ArrowUp, X } from 'lucide-react';

const names = { ALL: 'Academics + Career', ACADEMICS: 'Academics', CAREER: 'Career', PERSONAL: 'Personal' };
export default function AssistantWorkspace({ config, mode, onMode, domain, onDomain, messages, question, onQuestion, asking, onAsk, onStop, chatBottom }) {
  const actions = mode === 'actions';
  return <section className="assistant-section">
    <div className="assistant-scope">
      <select aria-label="Assistant mode" value={mode} disabled={asking} onChange={e => onMode(e.target.value)}>
        <option value="notes">Ask my notes</option><option value="actions">Tasks & calendar</option>
      </select>
      <span><LockKeyhole size={13}/> Area</span>
      <select aria-label="AI document area" value={domain} disabled={asking} onChange={e => onDomain(e.target.value)}>
        {config.desktopMode && <option value="ALL">Academics + Career</option>}
        <option value="ACADEMICS">Academics</option><option value="CAREER">Career</option>
        {config.desktopMode && <option value="PERSONAL">Personal</option>}
      </select>
      <span className="quota-label">{config.desktopMode ? 'On-device AI' : 'Free-tier AI'}</span>
    </div>
    {!config.aiConfigured && <div className="privacy-callout"><Sparkles size={18}/>
      <p>{config.desktopMode ? 'Local AI is unavailable. Your records and reminders remain usable.' : 'Connect your Gemini free-tier key in the backend settings to enable answers. Your records work without it.'}</p>
    </div>}
    <div className="conversation">
      {messages.length ? messages.map((message, index) => <div className={`chat-message ${message.role}`} key={message.id || index}>
        <div className="message-label">{message.role === 'user' ? 'You' : <><Sparkles size={13}/>Folio · {names[message.domain]}</>}</div>
        <div className="message-text">{message.text || (message.failure ? '' : message.status || (actions ? 'Preparing a draft…' : 'Finding the right passages…'))}</div>
        {message.failure && <p className="message-error">{message.failure}</p>}
        {message.choices?.length > 0 && <div className="assistant-choices" aria-label="Select a task to edit">
          {message.choices.map(choice => <button key={choice.id} className="text-button" disabled={asking}
            onClick={() => onAsk(null, choice, message)}>{choice.title} · {names[choice.domain]}</button>)}
        </div>}
        {message.citations?.length > 0 && <div className="citations">{message.citations.map((citation, i) => <details key={citation.sourceId || i}>
          <summary><FileText size={12}/>[{citation.sourceId || `S${i + 1}`}] {citation.documentId}{citation.pageNumber ? ` · p. ${citation.pageNumber}` : ''}</summary>
          {citation.headingPath && <strong>{citation.headingPath}</strong>}<p>{citation.content}</p>
        </details>)}</div>}
      </div>) : <div className="assistant-empty">
        <span className="assistant-star"><Sparkles size={30} strokeWidth={1.4}/></span>
        <h2>{actions ? 'A little help moving things forward.' : 'A conversation with your notes.'}</h2>
        <p>{actions ? 'Create tasks, calendar events, and reminders. Review every change before it is saved.' : 'Ask a question. Get an answer grounded in your documents, with sources you can check.'}</p>
        <div className="suggestions">{(actions ? ['Add a revision task for tomorrow at 6 pm', 'Create a study event tomorrow from 4 pm to 5 pm', 'Remind me to review my notes tomorrow at 9 am'] : ['What are the key ideas in my notes?', 'Explain this topic with a simple example', 'Compare the approaches in my documents']).map(text =>
          <button key={text} onClick={() => onQuestion(text)}>{text}<ArrowUpRight size={15}/></button>)}</div>
      </div>}
      <div ref={chatBottom}/>
    </div>
    <form className="chat-composer" onSubmit={onAsk}>
      <textarea rows={2} value={question} onChange={e => onQuestion(e.target.value)} maxLength={2000} aria-label="Question"
        placeholder={actions ? 'Add a task, calendar event, or reminder…' : 'Ask something from your notes…'}
        onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onAsk(e); } }}/>
      {asking ? <button type="button" className="send-button" aria-label="Stop answering" onClick={onStop}><X size={18}/></button>
        : <button className="send-button" aria-label="Ask question" disabled={!question.trim() || !config.aiConfigured}><ArrowUp size={18}/></button>}
    </form>
    <p className="assistant-footnote">{actions ? 'Check dates and times before approving. Enable reminders in Settings.' : 'Answers can be mistaken. Check the cited passages.'}
      {' '}{config.desktopMode ? 'Processing stays on this device.' : "Free-tier Gemini content may be used to improve Google's products."}</p>
  </section>;
}
