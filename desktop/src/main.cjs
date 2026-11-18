'use strict';
const {app,BrowserWindow,ipcMain,protocol,net,session,safeStorage,dialog,Menu}=require('electron');
const path=require('node:path');const fs=require('node:fs');const {randomUUID}=require('node:crypto');const {pathToFileURL}=require('node:url');
const {Store,Vault}=require('./store.cjs');const {Services,UA}=require('./services.cjs');const {Downloads}=require('./downloads.cjs');
const {createAssetFetcher,inkbunnyHeaders,mediaResponse}=require('./media.cjs');
const {asset,stripSession,trustedAsset,mediaSessionUrl,blacklisted,parseE,parseI}=require('./core.cjs');
protocol.registerSchemesAsPrivileged([{scheme:'artmedia',privileges:{standard:true,secure:true,stream:true,supportFetchAPI:true,corsEnabled:true}}]);
const smoke=process.argv.includes('--smoke-test');if(smoke)app.setPath('userData',path.join(require('node:os').tmpdir(),'unifiedart-smoke-'+process.pid));
let win,store,vault,services,downloads,assetFetcher;const artwork=new Map(),assets=new Map(),assetKeys=new Map(),controllers=new Map();
const uiURL=pathToFileURL(path.join(__dirname,'index.html')).href;
function text(v,max=200){if(typeof v!=='string'||v.length>max)throw new Error('Invalid input');return v.trim();}
function source(v){if(!['e621','inkbunny'].includes(v))throw new Error('Unknown source');return v;}
function currentArtwork(key){const a=artwork.get(text(key));if(!a)throw new Error('Artwork is no longer loaded. Refresh and retry.');return a;}
function proxy(source,url){url=asset(url);if(!url||!trustedAsset(source,url))return '';const account=source==='e621'?vault.data.e621?.id:vault.data.inkbunny?.id;const key=`${account||'guest'}:${source}:${url}`;
  let id=assetKeys.get(key);if(!id){id=randomUUID();assetKeys.set(key,id);assets.set(id,{source,url});}return `artmedia://asset/${id}`;}
function expose(a){artwork.set(a.key,a);return {...a,thumbnail:proxy(a.source,a.thumbnail),files:a.files.map(f=>({...f,url:proxy(a.source,f.url)})),previewMedia:a.previewMedia?{...a.previewMedia,url:proxy(a.source,a.previewMedia.url)}:null};}
async function fetchAsset(source,raw,options={}){
  if(!trustedAsset(source,raw))throw new Error('Untrusted media address');
  if(smoke && new URL(raw).pathname.includes('unifiedart-smoke')) {
    const video=raw.endsWith('.webm'),data=fs.readFileSync(video?path.join(__dirname,'../test/fixtures/video.webm'):path.join(__dirname,'../assets/icon.png'));
    const match=/^bytes=(\d+)-(\d*)$/.exec(options.headers?.Range || '');
    const start=match?Number(match[1]):0,end=match&&match[2]?Math.min(Number(match[2]),data.length-1):data.length-1;
    return new Response(data.subarray(start,end+1),{status:match?206:200,headers:{'content-type':video?'video/webm':'image/png','content-length':String(end-start+1),'accept-ranges':'bytes',...(match?{'content-range':`bytes ${start}-${end}/${data.length}`}:{})}});
  }
  return assetFetcher(source,raw,options);
}
function records(){return store.data.records.map(r=>r.art?{...r,art:expose({...r.art,needsVerification:true})}:r).filter(r=>!r.art || !blacklisted(r.art,vault.data.e621?.blacklist));}
function boot(){return {accounts:services.status(),settings:store.data.settings,records:records(),follows:store.data.follows,history:store.data.history,viewed:store.data.viewed,downloads:store.data.downloads,version:app.getVersion()};}
function persist(){store.save();return boot();}
function record(kind,key,label,extra={}){const r={kind,key,label,...extra};store.data.records=store.data.records.filter(x=>x.key!==key);store.data.records.unshift(r);return persist();}
const handlers={
  boot:()=>boot(),
  search:async p=>{
    const s=source(p.source);controllers.get(s)?.abort();const c=new AbortController();controllers.set(s,c);
    const q=p.query||{};if(!['explore','favorites','following'].includes(q.mode))throw new Error('Unknown gallery');
    const result=await services.search(s,q,p.page,p.rid,c.signal);
    return {...result,items:result.items.map(expose)};
  },
  cancelSearch:()=>{for(const c of controllers.values())c.abort();},
  details:async p=>{const a=currentArtwork(p.key);const fresh=await services.details(a.source,a.id);store.data.viewed=[fresh.key,...store.data.viewed.filter(x=>x!==fresh.key)].slice(0,5000);store.save();return expose(fresh);},
  connect:async p=>{await services.connect(source(p.source),p.name,p.secret);assets.clear();assetKeys.clear();return boot();},
  disconnect:async p=>{await services.disconnect(source(p.source));assets.clear();assetKeys.clear();return boot();},
  refreshBlacklist:async()=>{await services.refreshBlacklist();return boot();},
  favorite:async p=>{const a=currentArtwork(p.key);if(a.source!=='e621')throw new Error('Inkbunny site favorites are read-only through its API. Use a local bookmark.');await services.favorite(a.id,!!p.desired);a.favorite=!!p.desired;return expose(a);},
  suggestions:p=>services.suggestions(source(p.source),text(p.text,150)),
  history:p=>{const q={e621:text(p.query.e621||'',2000),inkbunny:text(p.query.inkbunny||'',2000)};store.data.history=[q,...store.data.history.filter(x=>JSON.stringify(x)!==JSON.stringify(q))].slice(0,12);store.save();return store.data.history;},
  saveSearch:p=>record('search',`search:${text(p.label)}`,text(p.label),{query:{e621:text(p.query.e621||'',2000),inkbunny:text(p.query.inkbunny||'',2000)}}),
  saveArt:p=>{const a=currentArtwork(p.key),group=text(p.group||'');const kind=group?'item':'bookmark';return record(kind,`${kind}:${group}:${a.key}`,a.title,{group,art:a});},
  createCollection:p=>{const n=text(p.name);if(!n)throw new Error('Enter a collection name.');return record('collection','collection:'+n,n);},
  removeRecord:p=>{const k=text(p.key,500);store.data.records=store.data.records.filter(r=>r.key!==k && !(k.startsWith('collection:')&&r.kind==='item'&&r.group===k.slice(11)));return persist();},
  saveArtist:p=>{const s=source(p.source),name=text(p.name);return record('artist',`artist:${s}:${name}`,name,{source:s});},
  follow:p=>{const name=text(p.name);if(!/^[a-zA-Z0-9_()\-.'!]+$/.test(name))throw new Error('Unsupported artist tag');store.data.follows=store.data.follows.includes(name)?store.data.follows.filter(x=>x!==name):[...store.data.follows,name];return persist();},
  watched:async()=>{const names=await services.watched();store.data.records=store.data.records.filter(r=>r.kind!=='watch');for(const name of names)store.data.records.push({kind:'watch',key:'watch:'+name,label:name,source:'inkbunny'});return persist();},
  pool:async p=>{const r=await services.pool(text(String(p.id),30));return {id:String(r.id),name:r.name,description:r.description,postIds:r.post_ids};},
  savePool:p=>record('pool',`pool:${text(String(p.id),30)}`,text(p.name||`Pool #${p.id}`),{poolId:text(String(p.id),30)}),
  settings:p=>{const s=store.data.settings;for(const k of ['newest','autoplay','reduced'])if(typeof p[k]==='boolean')s[k]=p[k];if(Number.isFinite(p.size))s.size=Math.max(140,Math.min(400,p.size));if(Array.isArray(p.enabled))s.enabled=p.enabled.filter(x=>['e621','inkbunny'].includes(x));return persist();},
  chooseDirectory:async()=>{const r=await dialog.showOpenDialog(win,{title:'Choose artwork download folder',properties:['openDirectory','createDirectory']});if(!r.canceled){store.data.settings.directory=r.filePaths[0];store.save();}return boot();},
  download:p=>{const a=currentArtwork(p.key);return downloads.add({kind:'submission',source:a.source,artId:a.id,fileIndex:Number.isInteger(p.fileIndex)?p.fileIndex:-1,label:a.title});},
  downloadBatch:p=>{if(!Array.isArray(p.keys)||p.keys.length>500)throw new Error('Select up to 500 artworks.');const ids=p.keys.map(k=>currentArtwork(k)).map(a=>({source:a.source,id:a.id}));return downloads.add({kind:'batch',ids,label:`${ids.length} artworks`});},
  downloadPool:p=>{if(!/^\d+$/.test(String(p.id)))throw new Error('Invalid pool ID');return downloads.add({kind:'pool',poolId:String(p.id),label:`e621 pool #${p.id} ZIP`});},
  cancelDownload:p=>downloads.cancel(text(p.id)),retryDownload:p=>downloads.retry(text(p.id))
};
app.whenReady().then(async()=>{
  store=new Store(app.getPath('userData'));vault=new Vault(app.getPath('userData'),safeStorage);await vault.load();services=new Services((...args)=>net.fetch(...args),vault,store);
  assetFetcher=createAssetFetcher(session.fromPartition('unifiedart-media'),()=>vault.data.inkbunny?.sid || services.guest,UA);
  if(smoke) {
    const first=parseE({id:1,title:'test',created_at:'2026-11-17T12:00:00Z',file:{url:'https://static1.e621.net/unifiedart-smoke.png',ext:'png',width:512,height:512},preview:{url:'https://static1.e621.net/unifiedart-smoke.png'},tags:{artist:['Desktop smoke test'],general:['test']}});
    const second=parseI({submission_id:2,title:'Video playback smoke test',username:'Test artist',create_datetime:'2026-11-16 12:00:00+00:00',thumbnail_url_large_noncustom:'https://inkbunny.net/files/unifiedart-smoke.png',mimetype:'video/webm',file_url_full:'https://inkbunny.net/files/unifiedart-smoke.webm'});
    const fixture=[first,second];
    services.search=async source=>({items:fixture.filter(a=>a.source===source),more:false});
    services.details=async(source,id)=>fixture.find(a=>a.source===source && a.id===String(id));
    services.suggestions=async()=>[];
  }
  downloads=new Downloads(store,services,fetchAsset,rows=>{if(win&&!win.isDestroyed())win.webContents.send('unifiedart:downloads',rows);});
  protocol.handle('artmedia',async request=>{
    try{const u=new URL(request.url),a=u.hostname==='asset'?assets.get(u.pathname.slice(1)):null;if(!a||!['GET','HEAD'].includes(request.method))return new Response(null,{status:404});
      const headers={};const range=request.headers.get('range');if(range)headers.Range=range;
      const r=await fetchAsset(a.source,a.url,{method:request.method,headers,signal:request.signal});
      if(smoke)console.log('MEDIA_RESPONSE '+JSON.stringify({source:a.source,status:r.status,type:r.headers.get('content-type')}));
      return mediaResponse(request,r);
    }catch(error){if(smoke)console.log('MEDIA_FAILURE '+(String(error.message).match(/net::[A-Z_]+/)?.[0]||error.name));return new Response(null,{status:502});}
  });
  ipcMain.handle('unifiedart:call',async(event,method,payload={})=>{
    if(event.senderFrame!==win?.webContents.mainFrame||event.senderFrame.url!==uiURL)throw new Error('Unauthorized request');
    if(!Object.hasOwn(handlers,method))throw new Error('Unknown command');
    if(!payload||typeof payload!=='object'||Array.isArray(payload))throw new Error('Invalid request');
    try{return await handlers[method](payload);}catch(e){throw new Error(e.message || 'Operation failed. Please retry.');}
  });
  win=new BrowserWindow({width:1420,height:940,minWidth:860,minHeight:620,backgroundColor:'#141419',title:'UnifiedArt',icon:path.join(__dirname,'../assets/icon.png'),
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true,webSecurity:true}});
  win.webContents.setWindowOpenHandler(()=>({action:'deny'}));win.webContents.on('will-navigate',e=>e.preventDefault());
  win.webContents.session.setPermissionRequestHandler((_w,_p,callback)=>callback(false));
  win.webContents.session.setPermissionCheckHandler(()=>false);
  win.webContents.on('render-process-gone',()=>{if(!win.isDestroyed())win.reload();});
  Menu.setApplicationMenu(Menu.buildFromTemplate([{label:'UnifiedArt',submenu:[{role:'quit'}]},{label:'View',submenu:[{role:'resetZoom'},{role:'zoomIn'},{role:'zoomOut'},{type:'separator'},{role:'togglefullscreen'}]}]));
  await win.loadURL(uiURL);
  if(smoke){try{
    const http=require('node:http');
    const server=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify({referer:req.headers.referer || ''}));});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try {
      const testSession=session.fromPartition('unifiedart-header-smoke');
      testSession.webRequest.onBeforeSendHeaders({urls:['http://127.0.0.1/*']},(details,callback)=>inkbunnyHeaders({...details,url:'https://nl1.ib.metapix.net/files/full/header-test.png'},callback));
      const echoed=await (await testSession.fetch('http://127.0.0.1:'+server.address().port+'/')).json();
      if(echoed.referer!=='https://inkbunny.net/')throw new Error('Inkbunny media referrer was not sent');
      console.log('MEDIA_HEADER_OK');
    } finally {server.close();}
    const result=await win.webContents.executeJavaScript(`(async()=>{
      const wait=async test=>{for(let n=0;n<250;n++){if(test())return;await new Promise(r=>setTimeout(r,20));}throw new Error('UI test timed out');};
      const b=await window.unifiedart.call('boot');
      if(!b.version||typeof require!=='undefined'||typeof process!=='undefined')throw new Error('Renderer isolation failed');
      await wait(()=>document.querySelectorAll('.tile').length===2);
      document.querySelector('.tile').click();
      await wait(()=>document.querySelector('#media-stage img')?.naturalWidth>0);
      document.querySelector('#next-art').click();
      await wait(()=>document.querySelector('#media-stage video')?.readyState>=2);
      const video=document.querySelector('#media-stage video');video.muted=true;await video.play();
      await wait(()=>video.currentTime>0);
      document.querySelector('#viewer-close').click();
      await wait(()=>!document.querySelector('#viewer').open);
      for(const name of ['collections','downloads','settings','favorites','following','explore']){
        document.querySelector('[data-tab="'+name+'"]').click();
        await wait(()=>document.querySelector('[data-tab="'+name+'"]').classList.contains('active'));
        await new Promise(r=>setTimeout(r,40));
      }
      await wait(()=>document.querySelectorAll('.tile').length===2);
      return {version:b.version,isolated:true,tabs:6,mergedGrid:true,imageLoaded:true,videoDecoded:true};
    })()`);
    if(process.platform==='win32') {
      // A separate real guest session tests the live API -> full file -> protocol -> Chromium image path.
      // Guest defaults remain general-rated; the test never uses saved accounts or changes their preferences.
      const live=new Services((...args)=>net.fetch(...args),{data:{}},store);let sid;const previousGuest=services.guest;
      try {
        const login=live.checkI(await live.request('inkbunny','/api_login.php',{username:'guest'}));sid=login.sid;
        if(!sid)throw new Error('Guest session unavailable');services.guest=sid;
        const search=live.checkI(await live.request('inkbunny','/api_search.php',{sid,text:'landscape',type:'1',submissions_per_page:'1',page:'1'}));
        const id=search.submissions?.[0]?.submission_id;if(!id)throw new Error('No public test submission');
        const detail=live.checkI(await live.request('inkbunny','/api_submissions.php',{sid,submission_ids:id}));
        const art=parseI(detail.submissions?.[0] || {});
        if(art.blocked||!art.files[0]?.url)throw new Error('Public test media unavailable');
        const imageURL=proxy('inkbunny',art.files[0].url);
        const dimensions=await win.webContents.executeJavaScript(`new Promise((resolve,reject)=>{const img=new Image();const timer=setTimeout(()=>reject(new Error('Live image timeout')),30000);img.onload=()=>{clearTimeout(timer);resolve({width:img.naturalWidth,height:img.naturalHeight});};img.onerror=()=>{clearTimeout(timer);reject(new Error('Live Inkbunny image failed'));};img.src=${JSON.stringify(imageURL)};})`);
        if(!dimensions.width)throw new Error('Live image did not decode');
        console.log('LIVE_INKBUNNY_IMAGE_OK '+JSON.stringify(dimensions));
      } catch(error) {
        // External API outages cannot be fixed by packaging; synthetic network and renderer tests remain required.
        console.log('LIVE_INKBUNNY_IMAGE_UNAVAILABLE '+(error.message==='Live Inkbunny image failed'?'image fetch/decode failed':'public API or media unavailable'));
        if(error.message==='Live Inkbunny image failed')throw error;
      } finally {services.guest=previousGuest;if(sid)try{await live.request('inkbunny','/api_logout.php',{sid});}catch{}}
    }
    console.log('SMOKE_OK '+JSON.stringify(result));await new Promise(r=>setTimeout(r,400));
    const image=await win.webContents.capturePage();fs.mkdirSync(path.join(__dirname,'../test-output'),{recursive:true});fs.writeFileSync(path.join(__dirname,'../test-output/desktop.png'),image.toPNG());app.exit(0);
  }catch{console.error('SMOKE_FAILED');app.exit(1);}}
}).catch(()=>{console.error('UnifiedArt could not start.');app.exit(1);});
app.on('window-all-closed',()=>{for(const c of controllers.values())c.abort();for(const c of downloads?.controllers.values() || [])c.abort();app.quit();});
