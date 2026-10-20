const { randomUUID } = require('node:crypto');
const { pathToFileURL } = require('node:url');

const xml = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[character]));

function createNotifier({ Notification, notifications, showWindow, icon, iconPath, platform = process.platform, report = console.error }) {
  const activations = new Map();
  if (platform === 'win32') Notification.handleActivation(details => {
    if (details.type !== 'click') return;
    const tag = new URLSearchParams(details.arguments).get('tag');
    activations.get(tag)?.();
  });
  function notify(message, onClick) {
    if (!Notification.isSupported()) throw new Error('Windows notifications are unavailable on this device.');
    const id = randomUUID();
    const options = { id, title: 'Folio', body: message, icon: icon() };
    // Include the ID for Windows COM activation after the banner has timed out.
    if (platform === 'win32') options.toastXml = `<toast launch="type=click&amp;tag=${id}"><visual><binding template="ToastGeneric"><text>Folio</text><text>${xml(message)}</text><image placement="appLogoOverride" src="${xml(pathToFileURL(iconPath).href)}"/></binding></visual></toast>`;
    const notification = new Notification(options);
    notifications.add(notification);
    const release = () => { notifications.delete(notification); activations.delete(id); };
    let activated = false;
    const activate = () => {
      if (activated) return;
      activated = true;
      release();
      report('Notification activated.');
      showWindow();
      Promise.resolve().then(() => onClick?.()).then(showWindow).catch(error => report('Notification open:', error.message));
    };
    activations.set(id, activate);
    notification.on('close', details => {
      // Windows keeps timed-out banners in Notification Centre. Keep both the
      // object and its activation callback alive until it is actually dismissed.
      if (platform === 'win32' && details?.reason !== 'userCanceled' && details?.reason !== 'applicationHidden') return;
      activated = true;
      release();
    });
    notification.once('click', activate);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { release(); notification.close(); reject(new Error('Windows did not acknowledge the notification. Check notification settings.')); }, 10000);
      notification.once('show', () => { clearTimeout(timeout); resolve(); });
      notification.once('failed', (_event, error) => { clearTimeout(timeout); release(); reject(new Error(error || 'Windows could not display the notification.')); });
      try { notification.show(); } catch (error) { clearTimeout(timeout); release(); reject(error); }
    });
  };
  notify.clear = () => {
    activations.clear();
    for (const notification of notifications) notification.close();
    notifications.clear();
  };
  return notify;
}

module.exports = { createNotifier };
