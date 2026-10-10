import React, { useEffect, useRef } from 'react';
export default function LocalDialog({ title, onClose, children, busy = false }) {
  const ref = useRef(null);
  const closing = useRef(onClose), working = useRef(busy); closing.current = onClose; working.current = busy;
  useEffect(() => {
    const previous = document.activeElement;
    const controls = () => [...ref.current.querySelectorAll('button:not(:disabled),input:not(:disabled):not([type=hidden]),select:not(:disabled),textarea:not(:disabled),[contenteditable=true]')];
    (ref.current.querySelector('input:not([type=hidden]),textarea,[contenteditable=true]') || controls()[0])?.focus();
    const key = e => { if (e.key === 'Escape' && !working.current) closing.current(); if (e.key === 'Tab') { const items = controls(), first = items[0], last = items.at(-1); if (e.shiftKey && (document.activeElement === first || !items.includes(document.activeElement))) { e.preventDefault(); last?.focus(); } else if (!e.shiftKey && (document.activeElement === last || !items.includes(document.activeElement))) { e.preventDefault(); first?.focus(); } } };
    ref.current.addEventListener('keydown', key);
    const current = ref.current;
    return () => { current.removeEventListener('keydown', key); previous?.focus(); };
  }, []);
  return <div className="modal-overlay"><div ref={ref} className="modal-card" role="dialog" aria-modal="true" aria-label={title}><div className="modal-top"><h2>{title}</h2><button className="icon-button" disabled={busy} aria-label="Close dialog" onClick={onClose}>×</button></div>{children}</div></div>;
}
