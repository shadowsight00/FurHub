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
function createAssetFetcher(session,getSid,userAgent) {
  // Chromium rejects Referer in fetch's headers. Set it in the supported session hook instead.
  session.webRequest.onBeforeSendHeaders(INKBUNNY_MEDIA,inkbunnyHeaders);
  return async function fetchAsset(source,raw,options={}) {
    if(!trustedAsset(source,raw))throw new Error('Untrusted media address');
    const withSession=url=>mediaSessionUrl(url,source==='inkbunny'?getSid():'');
    let url=withSession(raw);
    for(let hops=0;hops<4;hops++) {
      const headers={'User-Agent':userAgent};
      const range=options.headers?.Range;if(range)headers.Range=range;
      const response=await session.fetch(url,{method:options.method || 'GET',headers,redirect:'manual',signal:options.signal});
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
module.exports={INKBUNNY_MEDIA,inkbunnyHeaders,createAssetFetcher,mediaResponse};
