// Scans CertAlert's pages with axe-core in Chromium and fails on serious or critical
// violations of WCAG 2.0/2.1 A and AA.
//
//   node scan.mjs <base-url> <report-dir> [username] [password]
//
// Run by AccessibilityScanTest (./gradlew a11yTest), which starts the application in its demo
// profile with a test license and passes the address. The demo publishes its accounts on the
// sign-in page; this signs in as the administrator, who sees every page.
//
// Writes <report-dir>/axe-results.json (every violation, any impact) and axe-summary.md, and
// exits 1 if any page has a serious or critical violation, 2 if a page could not be scanned.

import { chromium } from 'playwright';
import { AxeBuilder } from '@axe-core/playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [base, out = 'build/reports/a11y', username = 'demo.admin', password = 'password'] = process.argv.slice(2);
if (!base) {
  console.error('usage: node scan.mjs <base-url> <report-dir> [username] [password]');
  process.exit(2);
}

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const BLOCKING = new Set(['serious', 'critical']);
// axe's best practices are reported too, for the self-audit, but never fail the scan: they go
// beyond what WCAG 2.1 AA requires.
const ADVISORY = 'best-practice';
const isWcag = (v) => v.tags.length > 0;
const blocks = (v) => isWcag(v) && BLOCKING.has(v.impact);

// Every page a person reaches, signed in as an administrator; detail pages are found from the
// tables. The sign-in and consent pages are scanned before signing in.
const PAGES = [
  ['People', '/users'],
  ['Servers', '/servers'],
  ['Certificates', '/certificates'],
  ['Projects', '/projects'],
  ['Metrics', '/metrics'],
  ['Notifications', '/notifications'],
  ['Administration', '/admin'],
  ['Audit log', '/admin/audit'],
  ['Integrations', '/admin/integrations'],
  ['License', '/admin/license'],
  ['Not found', '/no-such-page'],
];

const results = [];
let failedToScan = 0;

async function settle(page) {
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  // The tables draw after their first request returns.
  await page.waitForTimeout(300);
}

async function scan(page, name, path) {
  try {
    if (path) {
      await page.goto(base + path, { waitUntil: 'domcontentloaded' });
    }
    await settle(page);
    const analysis = await new AxeBuilder({ page }).withTags([...TAGS, ADVISORY]).analyze();
    const violations = analysis.violations.map((v) => ({
      id: v.id,
      impact: v.impact,
      help: v.help,
      helpUrl: v.helpUrl,
      tags: v.tags.filter((t) => t.startsWith('wcag')),
      nodes: v.nodes.map((n) => ({ target: n.target.join(' '), summary: n.failureSummary })),
    }));
    results.push({ name, url: page.url(), violations, passes: analysis.passes.length });
    const blocking = violations.filter(blocks);
    const wcag = violations.filter(isWcag);
    console.log(`${blocking.length ? 'FAIL' : 'ok  '} ${name} (${page.url()}): ${wcag.length} WCAG violation(s), ${blocking.length} serious or critical; ${violations.length - wcag.length} best-practice note(s)`);
    for (const v of violations) {
      console.log(`     [${v.impact}${isWcag(v) ? '' : ', best practice'}] ${v.id}: ${v.help} - ${v.nodes.length} node(s): ${v.nodes.slice(0, 3).map((n) => n.target).join(' | ')}`);
    }
  } catch (error) {
    failedToScan++;
    console.log(`ERR  ${name} (${path}): ${error.message}`);
    results.push({ name, url: base + (path || ''), error: error.message, violations: [] });
  }
}

const browser = await chromium.launch();
try {
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  const page = await context.newPage();

  // Before signing in: the consent banner, where it is on, and the sign-in page.
  await page.goto(base + '/login', { waitUntil: 'domcontentloaded' });
  if (page.url().includes('/consent')) {
    await scan(page, 'Notice and consent', null);
    await page.click('#consent-agree');
    await page.waitForURL((url) => !url.pathname.startsWith('/consent'));
  }
  await scan(page, 'Sign in', '/login');

  await page.fill('#username', username);
  await page.fill('#password', password);
  await Promise.all([page.waitForLoadState('domcontentloaded'), page.click('form[action$="/login"] button[type=submit]')]);
  await settle(page);
  if (page.url().includes('/login')) {
    throw new Error(`could not sign in as ${username}`);
  }

  for (const [name, path] of PAGES) {
    await scan(page, name, path);
  }

  // A person, a server and a project: the first of each the tables link to.
  for (const [name, list, pattern] of [
    ['Person', '/users', /\/users\/\d+$/],
    ['Server', '/servers', /\/servers\/\d+$/],
    ['Project', '/projects', /\/projects\/\d+$/],
  ]) {
    await page.goto(base + list, { waitUntil: 'domcontentloaded' });
    await settle(page);
    const hrefs = await page.$$eval('a[href]', (links) => links.map((a) => a.getAttribute('href')));
    const href = hrefs.find((h) => pattern.test(h));
    if (href) {
      await scan(page, name, href);
    } else {
      console.log(`skip ${name}: no link found on ${list}`);
    }
  }

  // Keyboard: a search table's row opens with Enter on its button, says so, and the certificate
  // detail it shows is scanned too.
  for (const [name, list] of [['People, a row opened', '/users'], ['Servers, a row opened', '/servers']]) {
    await page.goto(base + list, { waitUntil: 'domcontentloaded' });
    await settle(page);
    const toggle = page.locator('button.expand-toggle').first();
    if (await toggle.count()) {
      await toggle.focus();
      await page.keyboard.press('Enter');
      await page.waitForSelector('.cert-detail', { timeout: 10000 }).catch(() => {});
      const expanded = await toggle.getAttribute('aria-expanded');
      console.log(`${expanded === 'true' ? 'ok  ' : 'FAIL'} ${name}: Enter opens it (aria-expanded=${expanded})`);
      if (expanded !== 'true') {
        results.push({ name, url: base + list, violations: [{ id: 'keyboard-expand', impact: 'serious', tags: ['wcag2a', 'wcag211'], help: 'A row opens from the keyboard', nodes: [] }] });
      }
      await scan(page, name, null);
    }
  }

  // Keyboard: the skip link is the first thing Tab reaches, and it moves focus to the main region.
  await page.goto(base + '/users', { waitUntil: 'domcontentloaded' });
  await page.keyboard.press('Tab');
  const first = await page.evaluate(() => ({
    text: document.activeElement && document.activeElement.textContent.trim(),
    href: document.activeElement && document.activeElement.getAttribute('href'),
  }));
  const skip = first.href === '#main-content';
  console.log(`${skip ? 'ok  ' : 'FAIL'} skip link first in tab order (${JSON.stringify(first)})`);
  if (skip) {
    await page.keyboard.press('Enter');
    const focused = await page.evaluate(() => document.activeElement && document.activeElement.id);
    console.log(`${focused === 'main-content' ? 'ok  ' : 'FAIL'} skip link moves focus to the main region (${focused})`);
    if (focused !== 'main-content') {
      results.push({ name: 'Skip link', url: base + '/users', violations: [{ id: 'skip-link-target', impact: 'serious', tags: ['wcag2a', 'wcag241'], help: 'The skip link moves focus to the main region', nodes: [] }] });
    }
  } else {
    results.push({ name: 'Skip link', url: base + '/users', violations: [{ id: 'skip-link', impact: 'serious', tags: ['wcag2a', 'wcag241'], help: 'A skip link is the first focusable element', nodes: [] }] });
  }
} finally {
  await browser.close();
}

mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'axe-results.json'), JSON.stringify({ base, tags: TAGS, results }, null, 2));
const blocking = results.flatMap((r) => r.violations.filter((v) => v.tags && blocks(v)).map((v) => ({ page: r.name, ...v })));
const lines = [
  '# axe-core scan',
  '',
  `Rules: ${TAGS.join(', ')}. Fails on: ${[...BLOCKING].join(', ')}.`,
  '',
  '| Page | WCAG violations | Serious or critical | Best-practice notes |',
  '| --- | --- | --- | --- |',
  ...results.map((r) => `| ${r.name} | ${r.error ? 'not scanned: ' + r.error : r.violations.filter((v) => !v.tags || isWcag(v)).length} | ${r.violations.filter((v) => !v.tags || blocks(v)).length} | ${r.violations.filter((v) => v.tags && !isWcag(v)).length} |`),
  '',
  ...results.flatMap((r) => r.violations.map((v) => `- **${r.name}** [${v.impact}${v.tags && !isWcag(v) ? ', best practice' : ''}] \`${v.id}\`: ${v.help} (${v.nodes.length} node(s))`)),
];
writeFileSync(join(out, 'axe-summary.md'), lines.join('\n') + '\n');

if (failedToScan) {
  console.log(`${failedToScan} page(s) could not be scanned`);
  process.exit(2);
}
if (blocking.length) {
  console.log(`${blocking.length} serious or critical violation(s); see ${join(out, 'axe-summary.md')}`);
  process.exit(1);
}
console.log('No serious or critical violations.');
