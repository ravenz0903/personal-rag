import React, { useEffect, useState } from 'react';
import { Bell } from 'lucide-react';
import { api, post } from './api';

export async function disableDeviceReminders() {
  if (!('serviceWorker' in navigator)) return;
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) {
    try { await post('/api/reminders/unsubscribe', { endpoint: subscription.endpoint }); }
    finally { await subscription.unsubscribe(); }
  }
}

export default function ReminderSettings() {
  const [config, setConfig] = useState(null), [enabled, setEnabled] = useState(false), [busy, setBusy] = useState(false), [message, setMessage] = useState('');
  const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  useEffect(() => {
    let active = true;
    api('/api/reminders/config').then(async config => {
      if (!active) return;
      setConfig(config);
      if (supported) {
        const registration = await navigator.serviceWorker.getRegistration();
        const subscription = await registration?.pushManager.getSubscription();
        if (active) setEnabled(!!subscription);
      }
    }).catch(e => { if (active) setMessage(e.message); });
    return () => { active = false; };
  }, []);
  async function toggle() {
    setBusy(true); setMessage('');
    try {
      if (enabled) { await disableDeviceReminders(); setEnabled(false); setMessage('Reminders disabled on this device.'); return; }
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('Allow notifications in your browser settings to enable reminders.');
      await navigator.serviceWorker.register('/reminder-worker.js');
      const registration = await navigator.serviceWorker.ready;
      const base64 = config.publicKey.replace(/-/g, '+').replace(/_/g, '/');
      const key = Uint8Array.from(atob(base64), char => char.charCodeAt(0));
      const subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
      try { await post('/api/reminders/subscribe', subscription.toJSON()); }
      catch (e) { await subscription.unsubscribe(); throw e; }
      setEnabled(true); setMessage('Reminders enabled on this device, including when Folio is closed.');
    } catch (e) { setMessage(e.message); } finally { setBusy(false); }
  }
  return <div className="reminder-settings"><div><h3><Bell size={16}/>Device reminders</h3><p>Choose a reminder time when creating or editing a task. Notifications keep task details private.</p><p className="hint">{!supported ? 'This browser does not support push. On iPhone or iPad, install Folio on your Home Screen first.' : !config?.enabled ? 'Online reminders need Supabase scheduler setup before they can be enabled.' : 'Delivery requires browser permission and an internet connection. Times use your device’s timezone.'}</p></div><button className="secondary" disabled={busy || !supported || (!config?.enabled && !enabled)} onClick={toggle}>{busy ? 'Updating…' : enabled ? 'Disable on this device' : 'Enable on this device'}</button>{message && <p role="status">{message}</p>}</div>;
}
