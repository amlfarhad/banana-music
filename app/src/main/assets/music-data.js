(function(){
  'use strict';
  if(location.hostname!=='music.apple.com'||!window.AuralTV||window.BananaData)return;
  const jobs=new Map(),models=new Map(),out=[],pending=[];
  let generation=0,active=0,sequence=0,country=location.pathname.match(/^\/([a-z]{2})\//)?.[1]||'us',queuedContext='',playbackError='';
  let desiredShuffle=0,desiredRepeat=0,modesRequested=false,modesApplied=false;
  let selectionSerial=0,selectionTask=null,pendingSelection=null,runningSelection=null,lastSelection="",lastSelectionAt=0;
  function applyModes(force=false){const m=kit();if(!modesRequested||modesApplied||(!force&&selectionTask)||!m?.api?.music||!m.queue?.items?.length)return;try{if(m.shuffleMode!==desiredShuffle)m.shuffleMode=desiredShuffle;if(m.repeatMode!==desiredRepeat)m.repeatMode=desiredRepeat;modesApplied=true;}catch(_){}}
  function setModes(shuffle,repeat){desiredShuffle=shuffle===1?1:0;desiredRepeat=[0,1,2].includes(repeat)?repeat:0;modesRequested=true;modesApplied=false;applyModes();}
  const supported=new Set(['albums','library-albums','playlists','library-playlists','songs','library-songs','stations','music-videos','library-music-videos']);
  function kit(){try{return MusicKit.getInstance();}catch(_){return null;}}
  async function api(path,params){const r=await kit().api.music(path,params||{});const b=r.data||r;if(b.errors?.length)throw Error(b.errors[0].detail||b.errors[0].title);return b;}
  async function collection(path,params){
    const all=[],seen=new Set();let next=path,first=true;
    while(next){if(seen.has(next))throw Error('Apple repeated a pagination cursor.');seen.add(next);
      const b=await api(next,first?params:{});first=false;all.push(...(b.data||[]));next=b.next||'';
      if(next&&!next.startsWith('/v1/')&&!next.startsWith('https://api.music.apple.com/'))throw Error('Unexpected pagination origin.');
    }
    return all;
  }
  const detailKey=r=>'detail|'+r.type+'|'+r.id;
  function descriptor(r,index,context){
    if(!supported.has(r.type)||!r.attributes?.name)return null;
    const a=r.attributes,p=a.playParams||{},type=r.type,library=type.startsWith('library-');
    return {api:true,id:r.id,mediaType:type,library,title:a.name.trim(),subtitle:a.artistName||a.curatorName||'',artwork:a.artwork?.url||'',
      kind:/songs|music-videos/.test(type)?'track':'card',url:a.url||'',duration:a.durationInMillis?Math.floor(a.durationInMillis/60000)+':'+String(Math.floor(a.durationInMillis/1000)%60).padStart(2,'0'):'',
      number:index+1,index,context:context||'',key:/albums|playlists/.test(type)?detailKey(r):'',playId:p.id||r.id,catalogId:p.catalogId||a.catalogId||''};
  }
  function items(rs,context){return rs.map((r,i)=>descriptor(r,i,context)).filter(Boolean);}
  function shell(key,title){return {key,url:'banana:'+key,title,rows:[],controls:[],updatedAt:Date.now(),api:true};}
  function editorialRows(b){
    const roots=b.data?.[0]?.relationships?.tabs?.data?.[0]?.relationships?.children?.data||[];
    const rows=[];
    function walk(n){
      const rel=n.relationships||{},direct=items(rel.contents?.data||[]);
      if(direct.length){rows.push({title:n.attributes?.name||n.attributes?.designTag||'Featured',kind:'card',items:direct});return;}
      const children=rel.children?.data||[];
      const cards=children.flatMap(c=>items(c.relationships?.contents?.data||[]));
      if(cards.length){rows.push({title:n.attributes?.name||'Featured',kind:'card',items:cards});return;}
      children.forEach(walk);
    }
    roots.forEach(walk);return rows;
  }
  async function build(key){
    let model;
    if(key==='home'){
      model=shell(key,'Home');
      const [recommendations,recent]=await Promise.all([
        collection('/v1/me/recommendations',{limit:10}),collection('/v1/me/recent/played',{limit:25}).catch(()=>[])]);
      const recentItems=items(recent);if(recentItems.length)model.rows.push({title:'Recently played',kind:'card',items:recentItems});
      for(const r of recommendations){let content=r.relationships?.contents?.data||[];
        if(!content.length&&r.relationships?.contents?.href)content=await collection(r.relationships.contents.href,{limit:25});
        const list=items(content);if(list.length)model.rows.push({title:r.attributes?.title?.stringForDisplay||'For you',kind:'card',items:list});}
    }else if(key==='new'||key==='radio'){
      model=shell(key,key==='new'?'New':'Radio');
      const b=await api('/v1/editorial/'+country+'/groupings',{name:key==='new'?'music':'radio',platform:'web',tabs:'subscriber'});
      model.rows=editorialRows(b);
    }else if(key.startsWith('library/')){
      const name=key.split('/')[1],titles={playlists:'Your playlists',albums:'Saved albums',songs:'Songs','recently-added':'Recently added'};
      model=shell(key,titles[name]||'Your library');
      const rs=await collection('/v1/me/library/'+name,{limit:name==='recently-added'?25:100});const list=items(rs,key);
      if(name==='songs')model.tracks=list;else model.rows=[{title:titles[name],kind:'card',items:list}];
      if(name==='playlists')for(const r of rs)request(detailKey(r),false);
    }else if(key.startsWith('detail|')){
      const [,type,id]=key.split('|'),library=type.startsWith('library-'),plural=type.replace('library-','');
      const base='/v1/'+(library?'me/library/':'catalog/'+country+'/')+plural+'/'+encodeURIComponent(id);
      const [header,tracks]=await Promise.all([api(base),collection(base+'/tracks',{limit:100})]);
      const r=header.data?.[0];if(!r)throw Error('This collection is unavailable.');
      model=shell(key,r.attributes.name);
      model.detail={title:model.title,subtitle:r.attributes.artistName||r.attributes.curatorName||'',artwork:r.attributes.artwork?.url||''};
      model.queue={id,type,library};model.tracks=items(tracks,key);model.totalTracks=tracks.length;
    }else if(key.startsWith('search|')){
      model=shell(key,'Search');const term=key.slice(7);
      const b=await api('/v1/catalog/'+country+'/search',{term,types:'songs,albums,playlists,stations',limit:25});
      for(const [type,result]of Object.entries(b.results||{})){const list=items(result.data||[]);if(list.length)model.rows.push({title:type.charAt(0).toUpperCase()+type.slice(1),kind:'card',items:list});}
    }else throw Error('Unknown section.');
    return model;
  }
  function pump(){
    applyModes();if(!kit()?.api?.music)return;
    while(active<3&&pending.length){const key=pending.shift();const job=jobs.get(key);if(!job||job.status!=='queued')continue;
      active++;job.status='loading';const started=performance.now(),epoch=generation;
      build(key).then(model=>{if(epoch!==generation)return;models.set(key,model);jobs.set(key,{status:'ready'});out.push({key,model,fetchMs:Math.round(performance.now()-started)});})
        .catch(e=>{if(epoch!==generation)return;jobs.set(key,{status:'error'});out.push({key,error:String(e.message||e).slice(0,160)});})
        .finally(()=>{active--;pump();});
    }
  }
  function request(key,priority=true){
    const job=jobs.get(key);if(job?.status==='queued'||job?.status==='loading'){
      if(priority&&job.status==='queued'){const i=pending.indexOf(key);if(i>=0)pending.splice(i,1);pending.unshift(key);}pump();return true;
    }
    if(models.has(key)&&Date.now()-models.get(key).updatedAt<300000){out.push({key,model:models.get(key),cached:true});return true;}
    jobs.set(key,{status:'queued',sequence:++sequence});priority?pending.unshift(key):pending.push(key);pump();return true;
  }
  function prefetch(){for(const key of ['library/playlists','home','new','radio','library/albums','library/recently-added','library/songs'])request(key,false);}
  function warm(keys){for(const key of keys)if(!models.has(key)&&!jobs.has(key))request(key,false);}
  function drain(){pump();return out.splice(0,2);}
  function identities(item){const p=item?.attributes?.playParams||item?.playParams||{};return new Set([item?.id,item?.playId,item?.catalogId,p.id,p.catalogId].filter(Boolean).map(String));}
  function sameSong(a,b){const ids=identities(a);return [...identities(b)].some(id=>ids.has(id));}
  function enqueueSelection(request){
    request.signature=request.item?'item|'+request.item.mediaType+'|'+request.item.id:request.key+'|'+request.index+'|'+(request.shuffle===true);
    if(runningSelection?.signature===request.signature&&!pendingSelection)return selectionTask;
    if(pendingSelection?.signature===request.signature)return selectionTask;
    if(!selectionTask&&lastSelection===request.signature&&performance.now()-lastSelectionAt<350)return Promise.resolve();
    request.serial=++selectionSerial;pendingSelection=request;
    if(!selectionTask){selectionTask=Promise.resolve().then(async()=>{
      while(pendingSelection){const next=pendingSelection;pendingSelection=null;runningSelection=next;
        try{playbackError='';await select(next);if(next.serial===selectionSerial){lastSelection=next.signature;lastSelectionAt=performance.now();}}
        catch(e){if(next.serial===selectionSerial){playbackError=String(e.message||e).slice(0,160);lastSelection='';}}
      }
    }).finally(()=>{runningSelection=null;selectionTask=null;applyModes();});}
    return selectionTask;
  }
  async function select(request){
    const m=kit();if(!m)throw Error('Playback is still connecting.');
    const superseded=()=>request.serial!==selectionSerial;
    // Stop the existing stream before queue replacement; only one selector runs at a time.
    await m.pause();if(superseded())return;
    if(request.item){
      const item=request.item,type=item.mediaType.replace('library-','');queuedContext='';
      await m.setQueue({[type==='stations'?'station':type==='music-videos'?'musicVideo':'song']:item.playId||item.id,library:!!item.library});
      if(superseded())return;modesApplied=false;applyModes(true);await m.play();if(superseded())await m.pause();return;
    }
    const model=models.get(request.key);if(!model)throw Error('Connecting this collection for playback. Try again shortly.');
    const target=model.tracks?.[request.index];if(!target)throw Error('This song is unavailable.');
    const q=model.queue;
    if(!q){await select({...request,item:target});return;}
    const prepare=async()=>{await m.setQueue({[q.type.includes('album')?'album':'playlist']:q.id,library:q.library});queuedContext=request.key;modesApplied=false;};
    if(queuedContext!==request.key)await prepare();if(superseded())return;
    if(request.shuffle===true){desiredShuffle=1;modesRequested=true;modesApplied=false;}
    applyModes(true);
    let position=request.shuffle===true?0:m.queue.items.findIndex(item=>sameSong(target,item));
    // An edited playlist may no longer match the retained playback queue. Resolve it once again.
    if(position<0){await prepare();if(superseded())return;applyModes(true);position=m.queue.items.findIndex(item=>sameSong(target,item));}
    if(position<0)throw Error('This song is not available in Apple’s current playlist.');
    const expected=m.queue.items[position];
    // This SDK method starts playback itself. Calling play again creates another start request.
    await m.changeToMediaAtIndex(position);
    if(superseded()){await m.pause();return;}
    if(!sameSong(expected,m.nowPlayingItem)){await m.pause();throw Error('Apple could not play the selected song.');}
  }
  function play(key,index,shuffle){return enqueueSelection({key,index,shuffle});}
  function playItem(item){return enqueueSelection({item});}
  function restore(model){if(model?.key&&(!models.has(model.key)||model.updatedAt>models.get(model.key).updatedAt))models.set(model.key,model);}
  function clear(){selectionSerial++;pendingSelection=null;lastSelection='';generation++;models.clear();jobs.clear();pending.length=0;out.length=0;queuedContext='';}
  const oldMedia=AuralTV.media;AuralTV.media=function(action){if(action==='pause'){selectionSerial++;pendingSelection=null;lastSelection='';}return oldMedia(action);};
  const oldState=AuralTV.state;AuralTV.state=function(){const s=oldState();const m=kit();s.error=s.error||playbackError;s.modesReady=modesRequested;s.shuffle=desiredShuffle;s.repeat=desiredRepeat;return s;};
  window.BananaData={request,prefetch,warm,drain,setModes,setShuffle:value=>setModes(value,desiredRepeat),setRepeat:value=>setModes(desiredShuffle,value),play,playItem,restore,clear,setCountry:value=>{country=value;}};
})();
