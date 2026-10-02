/**
 * AB fork of PennyWise — Feature 1: receive transactions from the phone.
 *
 * Paste this into Extensions → Apps Script of the Google Sheet, then
 * Deploy → New deployment → Web app, "Execute as: Me", "Who has access: Anyone".
 *
 * The phone sends batches of parsed transactions (never the raw SMS text).
 * Rows are keyed on PennyWise's transaction hash, so a re-sent row updates
 * itself instead of appearing twice.
 */

const TOKEN = 'PASTE_TOKEN_HERE'; // must match the SHEET_TOKEN GitHub secret
const SHEET_NAME = 'Transactions';
const HEADERS = [
  'Date', 'Type', 'Amount', 'Currency', 'Merchant', 'Category',
  'Bank', 'Account', 'To Account', 'Balance After', 'Note',
  'Deleted', 'PennyWise ID', 'Hash', 'Synced At'
];
const HASH_COL = HEADERS.indexOf('Hash') + 1;

function doPost(e) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) return reply({ ok: false, error: 'busy' });
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.token !== TOKEN) return reply({ ok: false, error: 'bad token' });

    const sheet = getSheet();
    const index = hashIndex(sheet);
    const now = new Date();
    const toAppend = [];
    let updated = 0;

    (body.rows || []).forEach(function (r) {
      const row = [
        new Date(r.dateTime), r.type, Number(r.amount), r.currency,
        r.merchant, r.category, r.bank, r.account, r.toAccount,
        r.balanceAfter === '' ? '' : Number(r.balanceAfter),
        r.description, r.deleted, r.id, r.hash, now
      ];
      const existing = index[r.hash];
      if (existing > 0) {
        sheet.getRange(existing, 1, 1, row.length).setValues([row]);
        updated++;
      } else if (existing < 0) {
        toAppend[-existing - 1] = row; // same hash twice in one batch: keep the latest
      } else {
        toAppend.push(row);
        index[r.hash] = -toAppend.length;
      }
    });

    if (toAppend.length) {
      sheet.getRange(sheet.getLastRow() + 1, 1, toAppend.length, HEADERS.length)
        .setValues(toAppend);
    }
    return reply({ ok: true, added: toAppend.length, updated: updated });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

/** Open the web app URL in a browser to check the deployment is live. */
function doGet() {
  return reply({ ok: true, alive: true, rows: Math.max(getSheet().getLastRow() - 1, 0) });
}

function getSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_NAME);
    sheet.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]).setFontWeight('bold');
    sheet.setFrozenRows(1);
    sheet.getRange('A:A').setNumberFormat('dd-mmm-yyyy hh:mm');
    sheet.getRange('C:C').setNumberFormat('#,##0.00');
    sheet.getRange('J:J').setNumberFormat('#,##0.00');
  }
  return sheet;
}

function hashIndex(sheet) {
  const last = sheet.getLastRow();
  const index = {};
  if (last < 2) return index;
  sheet.getRange(2, HASH_COL, last - 1, 1).getValues().forEach(function (v, i) {
    if (v[0]) index[v[0]] = i + 2;
  });
  return index;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
