#!/usr/bin/env node
/*
 * Renders report-template.html with a real wiredoctor-report.json in jsdom and
 * fails on any runtime error. node --check only proves the JS parses; this
 * proves it runs — it is what caught the NAME_LIST_CAP TDZ error in v1.1.3.
 *
 *   npm i jsdom                     # once, anywhere on NODE_PATH
 *   node tools/render-check.js <report.json> [ghost-report.json]
 *   DUMP='#tab-cycles' node tools/render-check.js report.json   # print a pane
 *
 * The template is rendered WITHOUT the vendored vis-network bundle, so the
 * no-library degrade path is exercised on every run.
 */
const fs = require('fs');
const path = require('path');

let jsdom;
try { jsdom = require('jsdom'); }
catch { console.log('SKIP: jsdom not installed (npm i jsdom)'); process.exit(0); }

const tpl = path.join(__dirname, '..', 'wiredoctor-autoconfigure/src/main/resources/wiredoctor/report-template.html');
const [dataPath, ghostPath] = process.argv.slice(2);
if (!dataPath) { console.error('usage: node tools/render-check.js <wiredoctor-report.json> [wiredoctor-ghost-report.json]'); process.exit(2); }

const html = fs.readFileSync(tpl, 'utf8')
  .replace('<!-- VIS_NETWORK_INJECTION_POINT -->', '')
  .replace('<!-- VERSION_INJECTION_POINT -->', 'render-check')
  .replace('/* DATA_INJECTION_POINT */', fs.readFileSync(dataPath, 'utf8'))
  .replace('/* GHOST_INJECTION_POINT */', ghostPath ? fs.readFileSync(ghostPath, 'utf8') : 'null');

const errors = [];
const vc = new jsdom.VirtualConsole();
vc.on('jsdomError', e => errors.push(e.stack || String(e)));
vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));
const doc = new jsdom.JSDOM(html, { runScripts: 'dangerously', virtualConsole: vc }).window.document;

if (errors.length) { console.error('FAIL — runtime errors:\n' + errors.join('\n---\n')); process.exit(1); }

const badge = n => { const c = n.querySelector('.count'); return c ? `(${c.textContent}${c.classList.contains('hot') ? ' hot' : ''})` : ''; };
console.log('tabs: ' + [...doc.querySelectorAll('.nav-item')].map(n => n.dataset.tab + badge(n)).join(' | '));
let empty = 0;
for (const pane of doc.querySelectorAll('.tab')) {
  const len = pane.innerHTML.length;
  if (len < 200) { empty++; console.error(`FAIL — ${pane.id} rendered ${len} chars`); }
  else console.log(`  ${pane.id}: ${len} chars`);
}
// The coupling quadrant's framework toggle re-runs the whole render on click —
// the only interactive re-render in the template, so exercise it here.
const scHide = doc.querySelector('#sc-hide-fw'), scWrap = doc.querySelector('#sc-wrap');
if (scHide && scWrap) {
  const before = scWrap.innerHTML;
  scHide.checked = true;
  scHide.dispatchEvent(new (scWrap.ownerDocument.defaultView.Event)('change'));
  const after = scWrap.innerHTML;
  if (errors.length) { console.error('FAIL — errors after framework toggle:\n' + errors.join('\n')); process.exit(1); }
  // A degenerate graph renders the same empty state either way, so only the
  // absence of errors and of an emptied container is asserted.
  if (!after.trim()) { console.error('FAIL — framework toggle emptied the quadrant'); process.exit(1); }
  console.log(`  quadrant toggle: ${before.length} -> ${after.length} chars`);
  scHide.checked = false;
  scHide.dispatchEvent(new (scWrap.ownerDocument.defaultView.Event)('change'));
}
for (const sel of (process.env.DUMP || '').split(',').filter(Boolean)) {
  const el = doc.querySelector(sel);
  console.log(`\n--- ${sel} ---\n` + (el ? el.textContent.replace(/\n\s*\n/g, '\n').trim() : 'MISSING'));
}
console.log(empty ? 'FAIL' : 'OK');
process.exit(empty ? 1 : 0);
