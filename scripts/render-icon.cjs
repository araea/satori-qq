// Optional artwork export; requires puppeteer-core and a Chromium executable.
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('puppeteer-core');
const root = path.resolve(__dirname, '..');
const chromium = process.env.CHROMIUM || (process.env.PATH || '').split(path.delimiter)
  .map(dir => path.join(dir, 'chromium-browser')).find(file => fs.existsSync(file));
if (!chromium) throw new Error('Set CHROMIUM to your Chromium executable path');
(async () => {
  const browser = await puppeteer.launch({
    executablePath: chromium,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 512, height: 512, deviceScaleFactor: 1 });
    const svg = fs.readFileSync(path.join(root, 'artwork/icon.svg'), 'utf8');
    await page.setContent('<style>html,body{margin:0;background:transparent}</style>' + svg);
    await page.screenshot({ path: path.join(root, 'marketplace/ic_launcher.png'), omitBackground: true });
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
