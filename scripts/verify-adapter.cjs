// Live public-page integration checks. No account credentials or tokens are read.
const fs = require('fs');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { chromium } = require(process.env.AURAL_PLAYWRIGHT || '/Users/amlfarhad/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const source = fs.readFileSync('app/src/main/assets/tv-adapter.js', 'utf8');
const isolated = { location: { protocol: 'https:', hostname: 'appleid.apple.com' }, window: {} };
vm.runInNewContext(source, isolated);
assert.equal(isolated.window.AuralTV, undefined, 'adapter must not run on account origin');

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9331');
  try {
    const context = browser.contexts()[0];
    const browse = context.pages().find(p => /\/new/.test(p.url()));
    assert(browse, 'public Apple New page must already be open');
    await browse.evaluate(source);
    const snapshot = await browse.evaluate(() => AuralTV.snapshot());
    assert(snapshot.rows.length >= 3, 'native catalog needs multiple shelves');
    assert(snapshot.rows[0].items[0].artwork.startsWith('https://'), 'native hero needs real artwork');
    assert(snapshot.rows.every(r => r.items.length <= 100), 'snapshot must remain bounded');
    assert(snapshot.rows.every(r => r.items.every(i => !/:root|@media/.test(i.title))), 'titles must exclude embedded SVG stylesheet text');
    assert(!JSON.stringify(snapshot).includes('devToken'), 'catalog must not include tokens');
    fs.writeFileSync('evidence/native-catalog-new.json', JSON.stringify(snapshot, null, 2));
    const quality = await browse.evaluate(() => {
      const low = AuralTV.quality(false); const lowValue = MusicKit.getInstance().bitrate;
      const high = AuralTV.quality(true); return {low, lowValue, high, highValue: MusicKit.getInstance().bitrate};
    });
    assert.deepEqual(quality, {low:true,lowValue:64,high:true,highValue:256});
    const focus = await browse.evaluate(() => {AuralTV.focusFirst();const a=document.querySelector('[data-aural-focus]');AuralTV.move('right');return {count:document.querySelectorAll('[data-aural-focus]').length,changed:a!==document.querySelector('[data-aural-focus]')};});
    assert.equal(focus.count,1);assert(focus.changed, 'directional focus must move');
    const album = context.pages().find(p => /\/album\//.test(p.url()));
    assert(album, 'public album page must already be open');
    await album.evaluate(source);
    const detail = await album.evaluate(() => AuralTV.snapshot());
    assert(detail.detail && detail.detail.artwork, 'native detail needs cover art');
    assert(detail.rows[0].kind === 'track' && detail.rows[0].items.length > 1, 'native detail needs playable tracks');
    fs.writeFileSync('evidence/native-catalog-album.json', JSON.stringify(detail, null, 2));
    const stale = await album.evaluate(() => AuralTV.action('missing'));
    assert.equal(stale,'stale');
    const target = detail.rows[0].items[0];
    const clicked = await album.evaluate(id => AuralTV.action(id), target.id);
    assert.equal(clicked,'media');
    await album.waitForTimeout(2500);
    const first = await album.evaluate(() => AuralTV.state());
    await album.waitForTimeout(2500);
    const second = await album.evaluate(() => AuralTV.state());
    const previewObserved = second.playing && second.position > first.position;
    await album.evaluate(() => AuralTV.media('pause'));
    await browse.evaluate(() => { window.auralSearchContextCheck = true; AuralTV.search('Nina Simone'); });
    await browse.waitForURL(/term=Nina(%20|\+)Simone/, {timeout:10000});
    const searchPreserved = await browse.evaluate(() => !!window.auralSearchContextCheck && !!window.AuralTV);
    assert(searchPreserved, 'search must preserve the player document');
    const report = {
      originGuard: 'passed', catalogShelves: snapshot.rows.length,
      catalogItems: snapshot.rows.reduce((n,r)=>n+r.items.length,0),
      albumTracks: detail.rows[0].items.length, qualityTargets:quality,
      directionalFocus:'passed', staleAction:'passed', desktopPreviewObserved:previewObserved,
      searchPreservesPlayerDocument:searchPreserved,
      previewError:second.error, limitations:[
        'Desktop Chrome public pages only; no Apple account signed in.',
        'Preview behavior does not establish full subscription playback.',
        'No native Android runtime or ONN hardware was available for these checks.',
        'Quality values are target preferences, not measured audio bitrates.'
      ]
    };
    fs.writeFileSync('evidence/adapter-verification.json',JSON.stringify(report,null,2));
    console.log(JSON.stringify(report,null,2));
  } finally { await browser.close(); }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
