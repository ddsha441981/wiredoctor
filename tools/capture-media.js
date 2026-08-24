#!/usr/bin/env node
/*
 * Captures every screenshot, GIF and video that README.md and docs/ embed,
 * straight out of a real generated wiredoctor-report.html — so the media can
 * never drift from what the report actually renders.
 *
 *   npm i --no-save playwright gifenc pngjs
 *   node tools/capture-media.js <wiredoctor-report.html> [outDir]
 *
 * outDir defaults to docs/images. PNGs keep the filenames the docs already
 * reference, so refreshing them needs no markdown edits.
 *
 * GIFs are built by screenshotting the page while a scripted tour drives it,
 * then encoding those frames directly — Playwright's bundled ffmpeg has no GIF
 * muxer, and requiring a system ffmpeg for a docs refresh is not worth it.
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { chromium } = require('playwright');
const { GIFEncoder, quantize, applyPalette } = require('gifenc');
const { PNG } = require('pngjs');

const reportArg = process.argv[2];
const outDir = path.resolve(process.argv[3] || path.join(__dirname, '..', 'docs', 'images'));
if (!reportArg) {
  console.error('usage: node tools/capture-media.js <wiredoctor-report.html> [outDir]');
  process.exit(2);
}
const reportUrl = 'file://' + path.resolve(reportArg);
fs.mkdirSync(outDir, { recursive: true });

const VIEWPORT = { width: 1440, height: 940 };
const GRAPH_SETTLE_MS = 3500;

/* One entry per PNG the docs embed. `open` runs inside the page; `full`
   captures the whole scroll height, for tabs whose charts sit below the fold. */
const SHOTS = [
  { name: 'overview.png', tab: 'overview', full: true },
  {
    name: 'cycle-detection.png', tab: 'graph',
    open: () => focusBean('legacyImportService'),
  },
  {
    name: 'graph-focus.png', tab: 'graph',
    open: () => focusBean('orderService'),
  },
  { name: 'ghosts.png', tab: 'ghosts' },
  { name: 'smells.png', tab: 'smells' },
  { name: 'timing.png', tab: 'timing', full: true },
  { name: 'conditions.png', tab: 'conditions' },
];

/** Waits for the report script to have built its tabs. */
async function ready(page) {
  await page.waitForFunction(() => document.querySelectorAll('.nav-item').length > 0);
}

/** Opens a tab and lets the graph settle when that tab draws one. */
async function openTab(page, tab, open) {
  await page.evaluate(t => activate(t), tab);
  if (tab === 'graph') await page.waitForTimeout(GRAPH_SETTLE_MS);
  if (open) {
    await page.evaluate(open);
    await page.waitForTimeout(1400);
  }
  await page.waitForTimeout(400);
}

async function screenshots(browser) {
  const page = await browser.newPage({ viewport: VIEWPORT, deviceScaleFactor: 2 });
  await page.goto(reportUrl, { waitUntil: 'load' });
  await ready(page);
  for (const shot of SHOTS) {
    await openTab(page, shot.tab, shot.open);
    const file = path.join(outDir, shot.name);
    if (shot.full) {
      // The tab bodies scroll inside a fixed-height pane, so fullPage sees only
      // the viewport. Grow the window to the pane's content height instead.
      const h = await page.evaluate(t => {
        const el = document.querySelector('#tab-' + t);
        return Math.ceil(el.scrollHeight + 90);
      }, shot.tab);
      await page.setViewportSize({ width: VIEWPORT.width, height: Math.min(h, 6000) });
      await page.waitForTimeout(600);
    }
    await page.screenshot({ path: file });
    if (shot.full) await page.setViewportSize(VIEWPORT);
    console.log('png  ' + shot.name + '  ' + (fs.statSync(file).size / 1024).toFixed(0) + 'KB');
  }
  await page.close();
}

/**
 * Encodes captured frames as a GIF. One global palette keeps the file small —
 * the report is flat UI colour, so 128 colours is plenty — and each frame keeps
 * its real delay so the GIF plays at the speed the tour actually ran.
 */
function encodeGif(frames, file) {
  const gif = GIFEncoder();
  let palette = null;
  frames.forEach((frame, i) => {
    const { data, width: w, height: h } = frame;
    if (!palette) palette = quantize(data, 128);
    const indexed = applyPalette(data, palette);
    const delay = i + 1 < frames.length
      ? Math.max(40, Math.round(frames[i + 1].t - frame.t))
      : 900; // hold the last frame before the loop restarts
    gif.writeFrame(indexed, w, h, { palette: i === 0 ? palette : undefined, delay });
  });
  gif.finish();
  fs.writeFileSync(file, Buffer.from(gif.bytes()));
}

/** Decodes a PNG screenshot to the RGBA buffer the encoder wants. */
function decode(buffer) {
  const png = PNG.sync.read(buffer);
  return { data: new Uint8ClampedArray(png.data), width: png.width, height: png.height };
}

/**
 * Drives the page through `steps` while screenshotting it, then writes the GIF.
 * Screenshotting rather than recording video keeps the whole pipeline in-process
 * and makes every frame a real rendered state instead of a re-encoded one.
 */
async function animate(browser, name, steps) {
  // The GIF is whatever the viewport is — no resampling step, and 1000px keeps
  // the desktop layout (the nav collapses into a drawer below 860px).
  const page = await browser.newPage({ viewport: { width: 1000, height: 640 } });
  await page.goto(reportUrl, { waitUntil: 'load' });
  await ready(page);
  await page.waitForTimeout(700);

  const frames = [];
  let capturing = true;
  const grab = (async () => {
    while (capturing) {
      const t = Date.now();
      let shot;
      try {
        shot = await page.screenshot({ type: 'png' });
      } catch { break; } // page closed mid-capture
      const frame = decode(shot);
      frame.t = t;
      frames.push(frame);
      await page.waitForTimeout(120);
    }
  })();

  await steps(page);
  capturing = false;
  await grab;

  const file = path.join(outDir, name + '.gif');
  encodeGif(frames, file);
  await page.close();
  console.log('gif  ' + name + '.gif  ' + (fs.statSync(file).size / 1024 / 1024).toFixed(2)
    + 'MB  ' + frames.length + ' frames');
}

const hold = (page, ms) => page.waitForTimeout(ms);
const go = async (page, tab, ms = 2000) => {
  await page.evaluate(t => activate(t), tab);
  await hold(page, tab === 'graph' ? GRAPH_SETTLE_MS : ms);
};

/* The README hero: one pass over every tab, ending on the cycle in the graph. */
async function tourSteps(page) {
  await hold(page, 1200);
  await go(page, 'cycles', 2400);
  await go(page, 'ghosts', 2600);
  await go(page, 'smells', 2600);
  await go(page, 'timing', 2800);
  await go(page, 'graph');
  await page.evaluate(() => focusBean('legacyImportService'));
  await hold(page, 2600);
}

/* Search a bean, focus it, read the panel — the graph's actual workflow. */
async function graphSteps(page) {
  await go(page, 'graph');
  await page.fill('#g-search', 'orderService');
  await hold(page, 900);
  await page.press('#g-search', 'Enter');
  await hold(page, 2600);
  await page.evaluate(() => focusBean('auditService'));
  await hold(page, 2600);
}

/* The three charts the Timing tab draws, scrolled through in order. */
async function timingSteps(page) {
  await go(page, 'timing', 1800);
  for (const y of [500, 1100, 1700, 2300]) {
    await page.evaluate(top => {
      const pane = document.querySelector('#tab-timing');
      (pane.scrollHeight > pane.clientHeight ? pane : document.scrollingElement).scrollTo({ top, behavior: 'smooth' });
    }, y);
    await hold(page, 1500);
  }
}

(async () => {
  const browser = await chromium.launch();
  try {
    await screenshots(browser);
    await animate(browser, 'report-tour', tourSteps);
    await animate(browser, 'graph-drilldown', graphSteps);
    await animate(browser, 'timing-charts', timingSteps);
  } finally {
    await browser.close();
  }
  console.log('\nwrote to ' + outDir);
})();
