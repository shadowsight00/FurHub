'use strict';
const {trustedAsset,mediaSessionUrl}=require('./core.cjs');
const INKBUNNY_MEDIA={urls:['https://*.ib.metapix.net/*','https://inkbunny.net/files/*','https://inkbunny.net/thumbnails/*','https://inkbunny.net/private_files/*','https://inkbunny.net/private_thumbnails/*']};
function inkbunnyHeaders(details,callback) {
  const headers={...details.requestHeaders};
  if(trustedAsset('inkbunny',details.url)) {
    for(const k of Object.keys(headers))if(k.toLowerCase()==='referer')delete headers[k];
    headers.Referer='https://inkbunny.net/';
  }
  callback({requestHeaders:headers});
}
function retryWait(ms,signal){return new Promise((resolve,reject)=>{if(signal?.aborted)return reject(new Error('Canceled'));const stop=()=>{clearTimeout(timer);reject(new Error('Canceled'));};const timer=setTimeout(()=>{signal?.removeEventListener('abort',stop);resolve();},ms);signal?.addEventListener('abort',stop,{once:true});});}
// net.fetch cancels manual redirects in Electron; ClientRequest exposes the target first.
function createRedirectTransport(net,session) {
  const {Readable}=require('node:stream');
  return (url,options={})=>new Promise((resolve,reject)=>{
    if(options.signal?.aborted)return reject(new Error('Canceled'));
    const request=net.request({url,session,method:options.method||'GET',headers:options.headers||{},redirect:'manual',credentials:'omit'});
    const abort=()=>request.abort();options.signal?.addEventListener('abort',abort,{once:true});
    request.on('close',()=>options.signal?.removeEventListener('abort',abort));
    request.on('error',reject);
    request.on('abort',()=>reject(new Error('Canceled')));
    request.on('redirect',(status,method,target)=>{
      // Expose only the destination to the allowlist/SID loop, never follow automatically.
      resolve(new Response(null,{status,headers:{location:target}}));request.abort();
    });
    request.on('response',incoming=>{
      const headers=new Headers();for(const [key,value] of Object.entries(incoming.headers))if(value!==undefined)headers.set(key,Array.isArray(value)?value.join(', '):value);
      try{const noBody=options.method==='HEAD'||[204,205,304].includes(incoming.statusCode);
        resolve(new Response(noBody?null:Readable.toWeb(incoming),{status:incoming.statusCode,headers}));
        if(noBody)incoming.resume();
      }catch(error){request.abort();reject(error);}
    });
    request.end();
  });
}
function createAssetFetcher(session,getSid,userAgent,transport=(url,options)=>session.fetch(url,options)) {
  // Chromium rejects Referer in fetch's headers. Set it in the supported session hook instead.
  session.webRequest.onBeforeSendHeaders(INKBUNNY_MEDIA,inkbunnyHeaders);
  return async function fetchAsset(source,raw,options={}) {
    if(!trustedAsset(source,raw))throw new Error('Untrusted media address');
    const withSession=url=>mediaSessionUrl(url,source==='inkbunny'?getSid():'');
    let url=withSession(raw);
    for(let hops=0;hops<4;hops++) {
      const headers={'User-Agent':userAgent};
      const range=options.headers?.Range;if(range)headers.Range=range;
      let response;
      for(let attempt=0;attempt<3;attempt++) {
        try {
          response=await transport(url,{method:options.method || 'GET',headers,redirect:'manual',signal:options.signal});
          if(![429,500,502,503,504].includes(response.status)||attempt===2)break;
          const delay=Math.min(10000,Math.max(300*2**attempt,Number(response.headers.get('retry-after'))*1000||0));
          await response.body?.cancel();await retryWait(delay,options.signal);
        }catch(error){if(options.signal?.aborted||attempt===2)throw error;await retryWait(300*2**attempt,options.signal);}
      }
      if([301,302,303,307,308].includes(response.status)) {
        const location=response.headers.get('location');
        await response.body?.cancel();
        if(!location)throw new Error('Media redirect did not include a destination');
        const next=new URL(location,url);
        if(!trustedAsset(source,next.href))throw new Error('Media redirected to an unsupported address');
        url=withSession(next.href);continue;
      }
      return response;
    }
    throw new Error('Too many media redirects');
  };
}
function mediaResponse(request,remote) {
  if(!remote.ok){remote.body?.cancel().catch(()=>{});return new Response(null,{status:remote.status});}
  const type=remote.headers.get('content-type') || '';
  if(!/^(image\/|video\/|audio\/|application\/octet-stream)/i.test(type)){remote.body?.cancel().catch(()=>{});return new Response(null,{status:415});}
  const headers=new Headers({'Cache-Control':'no-store','Access-Control-Allow-Origin':'*','X-Content-Type-Options':'nosniff'});
  for(const k of ['content-type','content-range','accept-ranges'])if(remote.headers.has(k))headers.set(k,remote.headers.get(k));
  // Chromium returns decoded bytes; a compressed Content-Length would truncate the proxied image.
  const encoding=remote.headers.get('content-encoding');
  if((!encoding||encoding==='identity')&&remote.headers.has('content-length'))headers.set('content-length',remote.headers.get('content-length'));
  return new Response(request.method==='HEAD'?null:remote.body,{status:remote.status,headers});
}
module.exports={createRedirectTransport,INKBUNNY_MEDIA,inkbunnyHeaders,createAssetFetcher,mediaResponse};

// Session-local image bytes only: bounded, short-lived, and never persisted to disk.
class ImageCache {
  constructor(maxBytes=48*1024*1024,maxEntry=12*1024*1024,ttl=300000){this.maxBytes=maxBytes;this.maxEntry=maxEntry;this.ttl=ttl;this.bytes=0;this.entries=new Map();}
  clear(){this.entries.clear();this.bytes=0;}
  get(key){const hit=this.entries.get(key);if(!hit)return null;if(hit.expires<Date.now()){this.entries.delete(key);this.bytes-=hit.data.byteLength;return null;}this.entries.delete(key);this.entries.set(key,hit);return new Response(hit.data,{status:200,headers:hit.headers});}
  async remember(key,response){
    if(response.status!==200||!/^image\//i.test(response.headers.get('content-type')||'')||Number(response.headers.get('content-length'))>this.maxEntry)return;
    const copy=response.clone(),reader=copy.body?.getReader();if(!reader)return;let size=0;const chunks=[];
    try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>this.maxEntry){reader.cancel().catch(()=>{});return;}chunks.push(value);}
      const data=new Uint8Array(size);let offset=0;for(const chunk of chunks){data.set(chunk,offset);offset+=chunk.byteLength;}
      const headers=new Headers(response.headers);headers.delete('content-encoding');headers.set('content-length',String(size));
      const previous=this.entries.get(key);if(previous)this.bytes-=previous.data.byteLength;
      this.entries.delete(key);this.entries.set(key,{data,headers,expires:Date.now()+this.ttl});this.bytes+=size;
      while(this.bytes>this.maxBytes){const oldest=this.entries.keys().next().value;this.bytes-=this.entries.get(oldest).data.byteLength;this.entries.delete(oldest);}
    }catch{}finally{reader.releaseLock();}
  }
}
module.exports.ImageCache=ImageCache;
