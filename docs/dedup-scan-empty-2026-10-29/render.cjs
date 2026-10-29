const { chromium } = require('C:/Users/10540/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const path = require('path');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1606, height: 900 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const base = 'file:///' + path.join(__dirname, 'index.html').replaceAll('\\', '/');
  const checks = {};
  for (const kind of ['empty', 'scan']) {
    for (const theme of ['light', 'dark']) {
      await page.goto(base + `?view=${kind}&sheet=1&theme=${theme}`);
      await page.evaluate(() => document.fonts.ready);
      if (await page.locator('.proposal').count() !== 10) throw new Error('Missing proposals');
      await page.screenshot({ path: path.join(__dirname, `${kind}-ten-${theme}.png`), fullPage: true });
      if (kind === 'empty') {
        for (const p of await page.locator('.phone').all()) {
          const text = await p.locator('.content').innerText();
          if (text.trim() !== '没有重复') throw new Error('Extra result text: ' + text);
          if (await p.locator('.content button').count()) throw new Error('Extra result action');
        }
      }
      for (const p of await page.locator('.phone').all()) {
        const bad = await p.evaluate(el => [...el.querySelectorAll('.content > *, .result-title, .stage, .count, .compact, .scan-capsule, .flat-progress')].filter(x => {
          const a = x.getBoundingClientRect(), b = el.getBoundingClientRect();
          return a.width > 0 && (a.left < b.left - 1 || a.right > b.right + 1 || a.bottom > b.bottom - 15);
        }).map(x => x.className));
        if (bad.length) throw new Error(kind + ' sheet overflow: ' + bad.join(','));
      }
    }
    checks[kind + 'LightDarkAndText'] = 'PASS';
  }
  await page.goto(base + '?view=empty');
  const original = await page.locator('.board').innerHTML();
  await page.locator('[data-entry="cleanup"]').click();
  if (await page.locator('.board').innerHTML() !== original) throw new Error('Result origins differ');
  checks.identicalResultOrigins = 'PASS';
  await page.locator('#theme').click();
  if (!await page.locator('body').evaluate(x => x.classList.contains('dark'))) throw new Error('Theme toggle failed');
  await page.locator('#focus').selectOption('4');
  if (await page.locator('.proposal:visible').count() !== 1) throw new Error('Focus failed');
  await page.setViewportSize({ width: 412, height: 980 });
  await page.screenshot({ path: path.join(__dirname, 'empty-04-dark-mobile.png'), fullPage: true });
  await page.locator('[data-kind="scan"]').click();
  await page.locator('#progress').fill('33');
  if (!await page.locator('#scan-06 .percent').innerText().then(x => x.includes('33'))) throw new Error('Progress is not reactive');
  await page.locator('#stage').selectOption('preparing');
  if (await page.locator('.count').count()) throw new Error('Preparing shows fake count');
  if (await page.locator('[aria-valuenow]').count()) throw new Error('Preparing claims determinate progress');
  await page.locator('#focus').selectOption('5');
  await page.screenshot({ path: path.join(__dirname, 'scan-05-preparing-mobile.png'), fullPage: true });
  await page.locator('#stage').selectOption('comparing');
  for (const kind of ['scan', 'empty']) {
    await page.goto(base + '?view=' + kind);
    for (let i = 1; i <= 10; i++) {
      await page.locator('#focus').selectOption(String(i));
      if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Mobile page overflows: ' + kind + i);
      const id = String(i).padStart(2, '0');
      await page.locator('#' + kind + '-' + id).screenshot({ path: path.join(__dirname, `${kind}-${id}.png`) });
      await page.locator('.proposal:visible .back').click();
      if (await page.locator('.cancel-layer').count() !== 1) throw new Error('Back failed');
      await page.locator('.cancel-layer button').click();
    }
  }
  checks.allTwentyMobileAndBack = 'PASS';
  checks.progressAndPreparing = 'PASS';
  await page.setViewportSize({ width: 900, height: 1060 });
  for (const theme of ['light', 'dark']) {
    await page.goto(base + '?final=1&theme=' + theme);
    if (await page.locator('.proposal').count() !== 2) throw new Error('Final pair is missing');
    if (await page.locator('.progress .end').count()) throw new Error('Progress stop dot remains');
    for (const id of ['scan-02', 'empty-03']) {
      const squares = await page.locator('#' + id + ' .pic rect').evaluateAll(rects => rects.map(r => [r.getAttribute('width'), r.getAttribute('height')]));
      if (squares.length !== 2 || squares.some(([w, h]) => w !== '136' || h !== '136')) throw new Error('Photo proportions changed: ' + id);
    }
    const scanPhotos = await page.locator('#scan-02 .pic g').evaluateAll(gs => gs.map(g => g.outerHTML).join(''));
    const resultPhotos = await page.locator('#empty-03 .pic g').evaluateAll(gs => gs.map(g => g.outerHTML).join(''));
    if (scanPhotos !== resultPhotos) throw new Error('Selected illustrations differ');
    if ((await page.locator('#empty-03 .content').innerText()).trim() !== '没有重复') throw new Error('Result gained extra text');
    await page.locator('.board').screenshot({ path: path.join(__dirname, `final-pair-${theme}.png`) });
    for (const value of ['0', '50', '100']) {
      await page.locator('#progress').fill(value);
      if (await page.locator('.progress .end').count()) throw new Error('A dot appeared at ' + value);
    }
  }
  await page.setViewportSize({ width: 412, height: 980 });
  await page.goto(base + '?final=1');
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Final pair mobile overflow');
  checks.finalPairSharedRoundedPhotos = 'PASS';
  checks.finalPairNoStopDot = 'PASS';
  await page.emulateMedia({ reducedMotion: 'reduce' });
  checks.reducedMotion = await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches);
  if (errors.length) throw new Error(errors.join('\n'));
  fs.writeFileSync(path.join(__dirname, 'checks.json'), JSON.stringify({ ...checks, browserErrors: errors }, null, 2));
  await browser.close();
  process.stdout.write(JSON.stringify(checks, null, 2));
})().catch(error => { console.error(error); process.exit(1); });
