'use strict';
// A settled page is a batch. Later pages fill new slots, never insert above existing artwork.
function appendBatch(old,fresh) {
  const updates=new Map(fresh.map(a=>[a.key,a]));
  const existing=new Set(old.map(a=>a.key));
  return [...old.map(a=>updates.get(a.key)||a).filter(a=>!a.blocked),
    ...fresh.filter(a=>!existing.has(a.key)&&!a.blocked).filter((a,i,all)=>all.findIndex(b=>b.key===a.key)===i)
      .sort((a,b)=>b.uploadedAt-a.uploadedAt||a.key.localeCompare(b.key))];
}
function tagQuery(source,tag){return {mode:'explore',e621:source==='e621'?tag:'',inkbunny:source==='inkbunny'?tag:'',onlySource:source};}
if(typeof module!=='undefined')module.exports={appendBatch,tagQuery};
