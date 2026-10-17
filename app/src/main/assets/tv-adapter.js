(function () {
  'use strict';
  // This adapter only runs on music.apple.com, never on Apple account pages.
  if (location.protocol !== 'https:' || location.hostname !== 'music.apple.com') return;
  if (window.AuralTV) return;
  let current = null;
  let showAppleNavigation = false;
  let timer = 0;
  let nextAction = 0;
  let playbackError = '';
  let catalogRevision = 1;
  let dirtyCatalog = false;
  let trackPage = 0, trackPageUrl = '';
  const actions = new Map();
  const SELECTOR = 'a[href],button,input:not([type="hidden"]),select,textarea,[role="button"],[role="link"],[role="tab"],[tabindex]';
  const css = document.createElement('style');
  css.id = 'aural-tv-style';
  css.textContent = `
    html { color-scheme: dark; scroll-behavior: smooth; }
    body { background: #1c1c1e !important; }
    html.aural-tv .app-container { grid-template-columns: 0 minmax(0,1fr) !important; }
    html.aural-tv .scrollable-page { padding-inline-start: 0 !important; }
    html.aural-tv .app-container > .header,
    html.aural-tv .navigation { display: none !important; }
    .native-cta, .upsell-banner { display: none !important; }
    [data-aural-focus] {
      outline: 3px solid #ff4369 !important;
      outline-offset: 4px !important;
      box-shadow: 0 0 0 6px rgba(250,45,85,.16) !important;
      border-radius: 8px;
    }
    [data-aural-focus] .play-button, [data-aural-focus].play-button {
      opacity: 1 !important; visibility: visible !important;
    }
    input[data-aural-focus] { outline-offset: 2px !important; }
    .player-bar { z-index: 10 !important; }
    @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
  `;
  document.head.appendChild(css);
  document.documentElement.classList.add('aural-tv');

  function editable(e) {
    return !!e && (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA' || e.isContentEditable);
  }
  function visible(e, viewportOnly) {
    if (!e || !e.isConnected || e.disabled || e.getAttribute('aria-disabled') === 'true') return false;
    if (e.closest('[inert],[aria-hidden="true"]')) return false;
    if(e.checkVisibility && !e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}))return false;
    const r = e.getBoundingClientRect(), s = getComputedStyle(e);
    if (r.width < 8 || r.height < 8 || s.visibility === 'hidden' || s.display === 'none') return false;
    // Password fields are handled entirely by Apple's forms and the TV keyboard.
    if (e.matches('input[type="password"]')) return false;
    if (viewportOnly && (r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth)) return false;
    return true;
  }
  function controls(viewportOnly = true) {
    return [...document.querySelectorAll(SELECTOR)].filter(e => visible(e, viewportOnly));
  }
  function mark(e, scroll = false) {
    if (!e) return false;
    if (current) current.removeAttribute('data-aural-focus');
    current = e;
    e.setAttribute('data-aural-focus', '');
    if (!e.hasAttribute('tabindex')) e.setAttribute('tabindex', '0');
    e.focus({ preventScroll: true });
    if (scroll) e.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
    return true;
  }
  function first() {
    const main = document.querySelector('main');
    const list = controls();
    return mark(list.find(e => main && main.contains(e)) || list[0]);
  }
  function scrolling() {
    return document.querySelector('.scrollable-page') || document.scrollingElement;
  }
  function move(direction) {
    if (editable(document.activeElement)) return 'editing';
    if (!current || !visible(current, false)) return first() ? 'moved' : 'empty';
    const r = current.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    let best = null, score = Infinity;
    for (const e of controls()) {
      if (e === current || e.contains(current) || current.contains(e)) continue;
      const b = e.getBoundingClientRect();
      const dx = b.left + b.width / 2 - x, dy = b.top + b.height / 2 - y;
      const primary = direction === 'left' ? -dx : direction === 'right' ? dx : direction === 'up' ? -dy : dy;
      const cross = direction === 'left' || direction === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (primary < 8) continue;
      // Favor a shared row/column while still allowing diagonal movement.
      const value = primary + cross * 3 + (cross > primary * 1.6 ? 1200 : 0);
      if (value < score) { score = value; best = e; }
    }
    if (best) { mark(best, true); return 'moved'; }
    if (direction === 'left') return 'sidebar';
    if (direction === 'down' || direction === 'up') {
      const scroller = scrolling(), before = scroller.scrollTop;
      scroller.scrollBy({ top: (direction === 'down' ? 1 : -1) * innerHeight * .6, behavior: 'auto' });
      if (scroller.scrollTop !== before) {
        setTimeout(() => {
          const list = controls().filter(e => document.querySelector('main')?.contains(e));
          const target = direction === 'down' ? list[0] : list[list.length - 1];
          if (target) mark(target);
        }, 60);
        return 'scrolled';
      }
    }
    return 'edge';
  }
  function activate() {
    if (!current || !visible(current, false)) first();
    if (!current) return 'empty';
    if (editable(current)) { current.focus(); current.click(); return 'editing'; }
    const label = (current.getAttribute('aria-label') || current.innerText || '').toLowerCase();
    current.click();
    return /play|pause/.test(label) ? 'media' : 'activated';
  }
  function clickFirst(selectors) {
    for (const selector of selectors) {
      const e = [...document.querySelectorAll(selector)].find(e => !e.disabled && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().width > 0);
      if (e) { e.click(); return true; }
    }
    return false;
  }
  function media(action) {
    const m = kit();
    if (m) {
      const methods = { play: 'play', pause: 'pause', next: 'skipToNextItem', previous: 'skipToPreviousItem' };
      const method = action === 'toggle' ? (m.playbackState === window.MusicKit.PlaybackStates.playing ? 'pause' : 'play') : methods[action];
      if (method && typeof m[method] === 'function') { settle(m[method]()); return true; }
    }
    if (action === 'toggle') {
      return clickFirst(['.playback-play__pause', '.playback-play__play', '[data-testid="play-pause-button"]']);
    }
    if (action === 'pause') return clickFirst(['.playback-play__pause']);
    if (action === 'play') return clickFirst(['.playback-play__play']);
    if (action === 'next') return clickFirst(['[data-testid="skip-forward-button"]', '.playback-controls__next', '[aria-label="Next"]', '[aria-label="Next song"]']);
    if (action === 'previous') return clickFirst(['[data-testid="skip-back-button"]', '.playback-controls__previous', '[aria-label="Previous"]', '[aria-label="Previous song"]']);
    return false;
  }
  function signIn() {
    const e = document.querySelector('[data-testid="sign-in-button"]');
    if (e) { e.click(); return 'signin'; }
    const account = document.querySelector('[data-testid="account-menu-button"], [data-testid="account-button"]');
    if (account) { account.click(); return 'account'; }
    return 'unavailable';
  }
  function library() {
    const links = [...document.querySelectorAll('.navigation a[href], a[href*="/library"]')];
    const e = links.find(e => /\/library/.test(e.getAttribute('href')));
    if (e) { e.click(); return 'library'; }
    return signIn();
  }
  function player() {
    const e = [...document.querySelectorAll('.player-bar button')].find(e => visible(e, false));
    if (e) { mark(e, true); return 'focused'; }
    return 'unavailable';
  }
  function appleMenu() {
    showAppleNavigation = !showAppleNavigation;
    document.documentElement.classList.toggle('aural-tv', !showAppleNavigation);
    if (current) current.removeAttribute('data-aural-focus');
    current = null;
    return showAppleNavigation;
  }
  function state() {
    const m = kit(), item = m?.nowPlayingItem, attrs = item?.attributes || item || {};
    const md = navigator.mediaSession && navigator.mediaSession.metadata;
    const pause = document.querySelector('.playback-play__pause');
    const playing = m ? m.playbackState === window.MusicKit.PlaybackStates.playing :
      (navigator.mediaSession && navigator.mediaSession.playbackState === 'playing') ||
      !!(pause && getComputedStyle(pause).display !== 'none' && pause.getBoundingClientRect().width > 0);
    const art = attrs.artwork?.url || md?.artwork?.[0]?.src || '';
    if (dirtyCatalog) { catalogRevision++; dirtyCatalog = false; }
    return { ready: !!m || !!md || !!document.querySelector('[data-testid="account-menu-button"], [data-testid="account-button"]'), revision: catalogRevision, url: location.href, playing: !!playing, title: md?.title || attrs.name || item?.title || '',
      artist: md?.artist || attrs.artistName || item?.artistName || '', artwork: art.replace('{w}', '320').replace('{h}', '320'),
      duration: Number(m?.currentPlaybackDuration) || 0, position: Number(m?.currentPlaybackTime) || 0,
      editing: editable(document.activeElement), needsSignIn: !!document.querySelector('[data-testid="sign-in-button"]'),
      qualityAvailable: !!m && 'bitrate' in m, bitrateTarget: m?.bitrate || 0, error: playbackError };
  }
  function kit() { try { return window.MusicKit?.getInstance?.() || null; } catch (_) { return null; } }
  function settle(value) {
    playbackError = '';
    Promise.resolve(value).catch(() => { playbackError = 'Apple could not start playback. Check your sign-in and try another song.'; });
  }
  function quality(high) {
    try {
      const m = kit(), values = window.MusicKit?.PlaybackBitrate;
      if (!m || !values || !('bitrate' in m)) return false;
      m.bitrate = high ? values.HIGH : values.STANDARD;
      return m.bitrate === (high ? values.HIGH : values.STANDARD);
    } catch (_) { return false; }
  }
  function seek(seconds) {
    const m = kit();
    if (!m || !Number.isFinite(seconds) || typeof m.seekToTime !== 'function') return false;
    settle(m.seekToTime(Math.max(0, Math.min(seconds, Number(m.currentPlaybackDuration) || 0))));
    return true;
  }
  function register(e) {
    if (!e) return '';
    if (!e.dataset.auralAction) e.dataset.auralAction = 'a' + (++nextAction);
    actions.set(e.dataset.auralAction, e);
    return e.dataset.auralAction;
  }
  function text(e, selectors) {
    for (const sel of selectors) { const s = e.querySelector(sel)?.textContent?.trim(); if (s) return s.slice(0,180); }
    return '';
  }
  function artwork(e) {
    const source = e.querySelector('source[type="image/jpeg"][srcset]') || e.querySelector('source[srcset]');
    const image = e.querySelector('img');
    let url = source?.getAttribute('srcset')?.split(',')[0]?.trim().split(/\s+/)[0] || image?.currentSrc || image?.src || '';
    if (/\/1x1\.gif/.test(url)) url = '';
    return url;
  }
  function card(e, kind) {
    // A container can itself be the link. Prefer collection links over the
    // artwork's Play button or an artist link on personalized/library cards.
    const links = [e, ...e.querySelectorAll('a[href], [role="link"][href]')].filter(n=>n.hasAttribute('href'));
    const link = links.find(n=>/\/(?:album|playlist|library\/(?:playlist|album|playlist-folder))\//.test(n.getAttribute('href') || '')) ||
      e.querySelector('[data-testid="hero-lockup__link"], [data-testid="product-lockup-link"]') || links[0];
    const play = e.querySelector('[data-testid="track-play-button"] button, [data-testid="play-button"], button[aria-label*="Play"]');
    let title = text(e, ['[data-testid="hero-lockup__title"]','[data-testid="product-lockup-title"]','[data-testid="track-title"]','[data-testid="track-lockup-title"]','[data-testid="song-name-wrapper"]','[data-testid="vertical-video-title"]']);
    if (!title) title = link?.innerText?.trim().slice(0,160) || link?.getAttribute('aria-label')?.trim().slice(0,160) || '';
    if (!title) return null;
    const subtitle = text(e, ['[data-testid="hero-lockup__sub-title"]','[data-testid="product-lockup-subtitles"]','[data-testid="vertical-video-subtitle"]','[data-testid="track-subtitle"]','[data-testid="track-lockup-subtitle"]','[data-testid="track-title-by-line"]','[data-testid="track-column-secondary"]']);
    const href = link?.getAttribute('href');
    const navigates = href && href !== '#' && !href.startsWith('javascript:');
    // Personalized/library collections use href="#" plus Apple's click handler.
    // That handler opens the collection; it must take precedence over Play.
    const action = kind === 'track' ? (play || link) : link;
    if (!action) return null; // Never disguise a Play-only control as an openable album.
    if(kind !== 'track') action.dataset.auralKind = 'open';
    return { id: register(action), title, subtitle, artwork: artwork(e), kind,
      url: navigates ? new URL(href, location.href).href : '', duration: text(e, ['[data-testid="track-duration"]']) };
  }
  function snapshot() {
    for (const [key, value] of actions) if (!value.isConnected) actions.delete(key);
    const main = document.querySelector('main');
    if (!main) return { url: location.href, title: '', rows: [], controls: [] };
    const searchTitle = /\/search$/.test(location.pathname) ? (new URL(location.href).searchParams.get('term') || 'Search') : '';
    const libraryTitle = /\/all-playlists\/?$/.test(location.pathname) ? 'Your playlists' : /\/library\/albums\/?$/.test(location.pathname) ? 'Saved albums' : '';
    const result = { url: location.href, title: main.querySelector('h1')?.textContent?.trim() || libraryTitle || searchTitle, rows: [], controls: [], detail: null };
    // Apple exposes library categories and user playlists in its sidebar.
    // Collect those even when the current page isn't a shelf-based catalog.
    if (/\/library\/(all-playlists|playlist-folder)(\/|$)/.test(location.pathname) && !main.querySelector('[data-testid="product-lockup"], [data-testid="lockup-virtual-scrolling-component"]')) {
      const seen = new Set();
      const playlists = [...document.querySelectorAll('.navigation a[href]')].filter(a=>/\/library\/(playlist|playlist-folder)\//.test(a.getAttribute('href') || '')).map(a=>{
        const url = new URL(a.getAttribute('href'),location.href).href;
        if(seen.has(url)) return null; seen.add(url);
        const title = (a.innerText || a.getAttribute('aria-label') || '').trim();
        return title ? {id:register(a),title,subtitle:'Your playlist',artwork:'',kind:'card',url} : null;
      }).filter(Boolean);
      if(playlists.length) result.rows.push({title:'Your playlists',kind:'card',items:playlists});
    }
    const detail = main.querySelector('[data-testid="container-detail-header"]');
    if (detail) {
      result.detail = { title: result.title, subtitle: text(detail,['[data-testid="product-subtitles"]']), artwork: artwork(detail) };
      for (const button of detail.querySelectorAll('button')) {
        const label = (button.getAttribute('aria-label') || button.textContent || '').trim();
        if (/^(play|shuffle|preview)/i.test(label) && !button.disabled) result.controls.push({id:register(button), title:label.slice(0,40)});
      }
    }
    if(trackPageUrl !== location.href){trackPageUrl=location.href;trackPage=0;}
    const trackNodes = [...main.querySelectorAll('[data-testid="track-list-item"]')].filter(e=>visible(e,false));
    const tracks = trackNodes.slice(trackPage*50,trackPage*50+50).map((e,i)=>{
      const item=card(e,'track');if(item)item.number=trackPage*50+i+1;return item;
    }).filter(Boolean);
    if (tracks.length) {
      result.rows.push({ title:trackNodes.length>50 ? `Songs ${trackPage*50+1}–${trackPage*50+tracks.length} of ${trackNodes.length}` : 'Songs', kind:'track', items:tracks });
      if(trackPage>0)result.controls.push({id:'tracks-previous',title:'Previous songs'});
      if((trackPage+1)*50<trackNodes.length)result.controls.push({id:'tracks-next',title:'More songs'});
    }
    for (const section of [...main.querySelectorAll('[data-testid="shelf-component"]')].slice(0,10)) {
      const list = section.querySelector('[data-testid="shelf-item-list"]');
      if (!list) continue;
      const hero = !!list.querySelector('[data-testid="hero-lockup__link"]');
      const track = !!list.querySelector('[data-testid="song-name-wrapper"], .track-lockup');
      const kind = hero ? 'hero' : track ? 'track' : 'card';
      const items = [...list.children].filter(e=>visible(e,false)).slice(0,18).map(e=>card(e,kind)).filter(Boolean);
      if (!items.length) continue;
      let owner = section.parentElement;
      let heading = text(section, ['[data-testid="header-title"]']);
      for (let i=0;i<3&&!heading&&owner;i++,owner=owner.parentElement) heading=text(owner,['[data-testid="header-title"]']);
      result.rows.push({ title:heading || (hero ? 'Featured' : 'Explore'), kind, items });
    }
    // Library/search layouts sometimes expose a table instead of shelves.
    if (!main.querySelector('[data-testid="shelf-component"]') && !tracks.length) {
      const items = [...main.querySelectorAll('[data-testid="product-lockup"], .product-lockup, .track-lockup')].filter(e=>visible(e,false)).slice(0,100).map(e=>card(e,'card')).filter(Boolean);
      if (items.length) result.rows.push({title:result.title || 'Results',kind:'card',items});
    }
    return result;
  }
  function action(id, url, kind) {
    if(id==='tracks-next'||id==='tracks-previous'){
      trackPage=Math.max(0,trackPage+(id==='tracks-next'?1:-1));catalogRevision++;return 'refresh';
    }
    let e = actions.get(id);
    if (url) {
      const exact = [...document.querySelectorAll('a[href]')].find(a=>a.href === url);
      if (exact && kind !== 'track' && e?.dataset.auralKind === 'open') e = exact;
      else if (exact && kind === 'track') {
        const row=exact.closest('[data-testid="track-list-item"]');
        const play=row?.querySelector('[data-testid="track-play-button"] button,button[data-testid="play-button"]');
        if(play)e=play;
      }
    }
    if (!e?.isConnected) return 'stale';
    const href = e.getAttribute('href');
    e.click();
    return e.dataset.auralKind === 'open' || (href && href !== '#') ? 'navigate' : 'media';
  }
  function navigate(url) {
    let destination;
    try { destination = new URL(url, location.href); } catch (_) { return 'blocked'; }
    if (destination.origin !== 'https://music.apple.com') return 'blocked';
    const route = value => {const u=new URL(value,location.href);return u.pathname.replace(/^\/[a-z]{2}(?=\/)/,'').replace(/\/+$/,'')+u.search;};
    const e = [...document.querySelectorAll('a[href]')].find(e=>route(e.href) === route(destination.href));
    if (e) { e.click(); return 'spa'; }
    // Router navigation is used when possible. A full-page fallback can stop playback.
    const a = document.createElement('a'); a.href=destination.href; a.style.display='none';
    document.body.appendChild(a); a.click(); setTimeout(()=>a.remove(),500); return 'link';
  }
  function search(term) {
    const nav = [...document.querySelectorAll('.navigation a[href]')].find(e=>/\/search$/.test(new URL(e.href).pathname));
    if (!/\/search$/.test(location.pathname)) {
      if (!nav) return false;
      nav.click();
    }
    let tries = 0;
    const wait = setInterval(() => {
      const input = document.querySelector('input[data-testid="search-input__text-field"], main input[type="search"]');
      if (input) {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,term);
        input.dispatchEvent(new Event('input',{bubbles:true}));
        input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
        input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
        const form=input.closest('form');
        if (form?.requestSubmit) form.requestSubmit();
        else form?.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
        clearInterval(wait);
      } else if (++tries > 40) clearInterval(wait);
    }, 100);
    return true;
  }
  function capabilities() {
    return { secure: window.isSecureContext, encryptedMedia: typeof navigator.requestMediaKeySystemAccess === 'function',
      userAgent: navigator.userAgent, webAudio: typeof window.AudioContext === 'function' };
  }
  document.addEventListener('pointerdown', e => {
    const node = e.target.closest(SELECTOR);
    if (node && visible(node, true) && !editable(node)) mark(node);
  }, true);
  new MutationObserver(changes => {
    // Ignore player timers and pointer/focus markup. Watch only catalog changes.
    if(changes.some(change=>(change.target.nodeType===3?change.target.parentElement:change.target)?.closest?.('main, .navigation, .scrollable-page'))) dirtyCatalog = true;
    clearTimeout(timer);
    timer = setTimeout(() => { if (current && !current.isConnected) current = null; }, 100);
  }).observe(document.body, { childList: true, characterData: true, attributes:true, attributeFilter:['class','style','href','srcset','aria-hidden'], subtree: true });
  window.AuralTV = { move, activate, focusFirst: first, media, signIn, library, player, appleMenu, state, capabilities,
    snapshot, action, navigate, quality, seek, search };
})();
