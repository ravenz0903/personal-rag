export function wallTime(value, zone) {
  if (!value) return '';
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value)).map(p => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
export const dateInZone = (value, zone) => wallTime(value, zone).slice(0, 10);
export function toInstant(value, zone) {
  if (!value) return null;
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error('Choose a valid date and time.');
  const nominal = Date.parse(value + ':00Z'), candidates = new Set();
  for (let hour = -36; hour <= 36; hour += 6) {
    const sample = nominal + hour * 3600000;
    const offset = Date.parse(wallTime(sample, zone) + ':00Z') - sample;
    const candidate = nominal - offset;
    if (wallTime(candidate, zone) === value) candidates.add(candidate);
  }
  if (!candidates.size) throw new Error('This time does not exist because the clocks move forward. Choose another time.');
  return new Date(Math.min(...candidates)).toISOString();
}
export function addDays(key, count) { const date = new Date(key + 'T12:00:00Z'); date.setUTCDate(date.getUTCDate() + count); return date.toISOString().slice(0, 10); }
export function weekDays(key, weekStart) { const weekday = new Date(key + 'T12:00:00Z').getUTCDay(); const start = addDays(key, -((weekday - weekStart + 7) % 7)); return Array.from({ length: 7 }, (_, i) => addDays(start, i)); }
export const put = (api, path, body) => api(path, { method: 'PUT', body: JSON.stringify(body) });
export function downloadBlob(blob, name) { const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
