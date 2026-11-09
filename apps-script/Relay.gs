/** Cyber Hayat Ijmāʿ — RELAY (Google is only the front door).
 *  Every app request is forwarded to the organization laptop, which is the real database.
 *  This Sheet only shows a read-only mirror (Dashboard + Members tabs). */
const KEY_TO_SET = 'PASTE-THE-RELAY-KEY-FROM-THE-LAPTOP-HERE';
const P = PropertiesService.getScriptProperties();
function setKey() { P.setProperty('RELAY_KEY', KEY_TO_SET); Logger.log('Relay key saved.'); }
const out = o => ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
const OFFLINE = { ok: false, code: 'server_offline', error: 'The organization server is offline. Your messages are saved and will send automatically.' };

function doGet() {
  const u = P.getProperty('SERVER_URL'); let up = false;
  try { up = !!u && UrlFetchApp.fetch(u + '/health', { muteHttpExceptions: true }).getResponseCode() === 200; } catch (e) {}
  return out({ ok: true, server: up ? 'online' : 'offline' });
}

function doPost(e) {
  let q; try { q = JSON.parse(e.postData.contents); } catch (x) { return out({ ok: false, error: 'Bad request' }); }
  const key = P.getProperty('RELAY_KEY');
  if (q.action === '_register' || q.action === '_mirror') {          // calls from the laptop only
    if (!key || q.key !== key) return out({ ok: false, code: 'auth', error: 'Bad key' });
    if (q.action === '_register') { P.setProperty('SERVER_URL', String(q.url)); return out({ ok: true }); }
    try { mirror(q); } catch (x) {} return out({ ok: true });
  }
  const url = P.getProperty('SERVER_URL');
  if (!url || !key) return out(OFFLINE);
  try {
    const r = UrlFetchApp.fetch(url.replace(/\/$/, '') + '/api', { method: 'post', contentType: 'application/json',
      payload: e.postData.contents, headers: { 'X-Relay-Key': key }, muteHttpExceptions: true, followRedirects: true });
    if (r.getResponseCode() !== 200) return out(OFFLINE);
    return ContentService.createTextOutput(r.getContentText()).setMimeType(ContentService.MimeType.JSON);
  } catch (err) { return out(OFFLINE); }
}

function mirror(q) {
  const ss = SpreadsheetApp.getActive(); if (!ss) return;
  const tab = n => ss.getSheetByName(n) || ss.insertSheet(n);
  const d = tab('Dashboard'); d.clear();
  d.getRange(1, 1, 1, 2).setValues([['Cyber Hayat Ijmāʿ — live mirror (read only)', '']]);
  const rows = Object.keys(q.stats || {}).map(k => [k, q.stats[k]]);
  if (rows.length) d.getRange(2, 1, rows.length, 2).setValues(rows);
  const m = tab('Members'); m.clear();
  const data = [['ID (handle)', 'Role', 'Status', 'Joined', 'Last seen']].concat(q.members || []);
  m.getRange(1, 1, data.length, 5).setValues(data);
}
