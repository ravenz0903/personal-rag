export function trustedPushEndpoint(endpoint) {
  try {
    const url = new URL(endpoint), host = url.hostname.toLowerCase();
    return url.protocol === 'https:' && !url.username && !url.password && !url.hash && (!url.port || url.port === '443') &&
      (host === 'fcm.googleapis.com' || host === 'updates.push.services.mozilla.com' || host.endsWith('.notify.windows.com') || host === 'web.push.apple.com');
  } catch { return false; }
}

export async function deliverReminders(rows, services) {
  for (const row of rows) {
    // Defense in depth: never send stored endpoints to arbitrary hosts.
    if (!trustedPushEndpoint(row.endpoint)) { await services.removeSubscription(row); continue; }
    try {
      if (!await services.stillEligible(row)) continue;
      const status = await services.send(row);
      if (status === 404 || status === 410) await services.removeSubscription(row);
      else if (status >= 200 && status < 300) await services.markSent(row);
      // Transient failures retain the lease and retry, with at most three attempts.
    } catch { /* No secrets or task contents in logs. The lease allows a later retry. */ }
  }
}
