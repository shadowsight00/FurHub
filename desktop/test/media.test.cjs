'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');
const {createAssetFetcher,inkbunnyHeaders,mediaResponse}=require('../src/media.cjs');
function harness(responses=[new Response('image',{headers:{'content-type':'image/png'}})]) {
  const calls=[];let hook;
  const session={webRequest:{onBeforeSendHeaders(filter,handler){hook=handler;assert(filter.urls.every(u=>u.startsWith('https:')));}},async fetch(url,init){
    // Chromium rejects direct Referer headers before issuing the network request.
    assert(!Object.keys(init.headers).some(k=>k.toLowerCase()==='referer'));
    let wire;hook({url,requestHeaders:init.headers},r=>{wire=r.requestHeaders;});calls.push({url,init,wire});return responses.shift();
  }};
  return {fetch:createAssetFetcher(session,()=> 'secret-session','UnifiedArt/test'),calls};
}
test('Inkbunny full-size, thumbnail and private media get their required wire referrer',async()=>{
  for(const url of ['https://nl1.ib.metapix.net/files/full/a.png','https://nl1.ib.metapix.net/thumbnails/large/a.jpg','https://inkbunny.net/private_files/full/a.png']) {
    const h=harness();await h.fetch('inkbunny',url);assert.equal(h.calls[0].wire.Referer,'https://inkbunny.net/');assert.equal(h.calls[0].url.includes('sid=secret-session'),url.includes('/private_files/'));
  }
});
test('e621 stays unchanged and credentials never go to public CDNs',async()=>{const h=harness();await h.fetch('e621','https://static1.e621.net/data/a.png',{headers:{Range:'bytes=1-2',Referer:'bad'}});assert.equal(h.calls[0].wire.Referer,undefined);assert.equal(h.calls[0].wire.Range,'bytes=1-2');assert(!h.calls[0].url.includes('secret'));});
test('redirected Inkbunny assets keep the referrer and only private official URLs get SID',async()=>{const h=harness([new Response(null,{status:302,headers:{location:'https://inkbunny.net/private_files/full/a.png'}}),new Response('image')]);await h.fetch('inkbunny','https://nl1.ib.metapix.net/files/full/a.png');assert.equal(h.calls.length,2);assert(h.calls[1].url.includes('sid=secret-session'));assert(h.calls.every(c=>c.wire.Referer==='https://inkbunny.net/'));});
test('redirects to unknown hosts are rejected before session data could leave',async()=>{const h=harness([new Response(null,{status:302,headers:{location:'https://example.com/private_files/a.png'}})]);await assert.rejects(()=>h.fetch('inkbunny','https://inkbunny.net/private_files/full/a.png'),/unsupported/);assert.equal(h.calls.length,1);});
test('header hook does not add Inkbunny headers to unrelated hosts',()=>{let result;inkbunnyHeaders({url:'https://example.com/a',requestHeaders:{Accept:'image/png'}},r=>{result=r;});assert.deepEqual(result.requestHeaders,{Accept:'image/png'});});
test('compressed upstream lengths are not copied onto decoded image streams',async()=>{const r=mediaResponse({method:'GET'},new Response('decoded-image',{headers:{'content-type':'image/png','content-encoding':'gzip','content-length':'999'}}));assert.equal(r.headers.get('content-length'),null);assert.equal(await r.text(),'decoded-image');});
test('image streams and video range headers survive the media proxy',async()=>{const r=mediaResponse({method:'GET'},new Response('abc',{status:206,headers:{'content-type':'video/webm','content-length':'3','content-range':'bytes 0-2/20','accept-ranges':'bytes'}}));assert.equal(r.status,206);assert.equal(r.headers.get('content-length'),'3');assert.equal(r.headers.get('content-range'),'bytes 0-2/20');assert.equal(await r.text(),'abc');});

test('temporary CDN server failures are retried before returning media',async()=>{
  let calls=0;const session={webRequest:{onBeforeSendHeaders(){}},fetch:async()=>++calls===1?new Response('temporary',{status:503}):new Response('image',{headers:{'content-type':'image/png'}})};
  const fetch=createAssetFetcher(session,()=>'', 'test');const response=await fetch('inkbunny','https://nl1.ib.metapix.net/files/full/test.png');
  assert.equal(calls,2);assert.equal(await response.text(),'image');
});
