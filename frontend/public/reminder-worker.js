self.addEventListener('push', event => {
  let data = {};
  try { data = event.data?.json() || {}; } catch { /* Use a private generic notification. */ }
  event.waitUntil(self.registration.showNotification('Folio reminder', {
    body: 'A task reminder is due. Open your private workspace to view it.',
    tag: typeof data.tag === 'string' ? data.tag : 'folio-reminder',
    data: { url: '/?view=tasks' }
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = windows.find(client => new URL(client.url).origin === self.location.origin);
    if (existing) { await existing.navigate('/?view=tasks'); return existing.focus(); }
    return self.clients.openWindow('/?view=tasks');
  })());
});
