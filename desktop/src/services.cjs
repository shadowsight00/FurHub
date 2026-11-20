'use strict';
const {parseE,parseI,mergeArtwork,blacklisted}=require('./core.cjs');
const UA='FurHub-Desktop/0.2.1 (https://github.com/shadowsight00/FurHub)';
const wait=(ms,signal)=>new Promise((resolve,reject)=>{
  if(signal?.aborted) return reject(new Error('Canceled'));
  const stop=()=>{clearTimeout(timer);reject(new Error('Canceled'));};
  const timer=setTimeout(()=>{signal?.removeEventListener('abort',stop);resolve();},ms);
  signal?.addEventListener('abort',stop,{once:true});
});
class Services {
  constructor(fetch,vault,store) {this.fetch=fetch;this.vault=vault;this.store=store;this.guest='';this.guestTask=null;this.tails={};this.last={};this.detailsCache=new Map();this.detailTasks=new Map();}
  detailKey(source,id){const a=this.vault.data[source];return `${source}:${a?.id||'guest'}:${a?.sid||''}:${a?.blacklist||''}:${id}`;}
  rememberDetail(a,key=this.detailKey(a.source,a.id)){if(!a.blocked){this.detailsCache.delete(key);this.detailsCache.set(key,{art:a,expires:Date.now()+60000});while(this.detailsCache.size>160)this.detailsCache.delete(this.detailsCache.keys().next().value);}return a;}
  eHeaders() {const a=this.vault.data.e621;return a?{Authorization:'Basic '+Buffer.from(a.name+':'+a.key).toString('base64')}:{};}
  async request(source,path,params={},options={}) {
    const previous=this.tails[source] || Promise.resolve(); let unlock;
    this.tails[source]=new Promise(r=>{unlock=r;}); await previous;
    try {
      for(let attempt=0;attempt<3;attempt++) {
        await wait(Math.max(0,(source==='e621'?650:1100)-(Date.now()-(this.last[source] || 0))),options.signal);
        this.last[source]=Date.now();
        const base=source==='e621'?'https://e621.net':'https://inkbunny.net', u=new URL(base+path);
        const headers={'User-Agent':UA,...(source==='e621'?this.eHeaders():{}),...options.headers};
        const init={headers,method:options.method || (source==='inkbunny'?'POST':'GET'),redirect:'error',signal:AbortSignal.any([AbortSignal.timeout(40000),...(options.signal?[options.signal]:[])])};
        if(init.method==='GET') for(const [k,v] of Object.entries(params)) u.searchParams.set(k,String(v));
        else if(init.method==='POST') {headers['Content-Type']='application/x-www-form-urlencoded';init.body=new URLSearchParams(params).toString();}
        try {
          const r=await this.fetch(u.href,init);
          if(!r.ok) {const error=new Error(`${source} request failed (HTTP ${r.status})`);error.retry=r.status===429 || r.status>=500;error.delay=Math.min(60000,Number(r.headers.get('retry-after'))*1000 || 1000*2**attempt);throw error;}
          if(r.status===204)return {};
          const body=await r.text();return body.trim()?JSON.parse(body):{};
        } catch(error) {
          if(options.signal?.aborted) throw new Error('Canceled');
          if(attempt===2 || error.retry===false || error.message?.includes('HTTP') && !error.retry) throw new Error(error.message?.includes('HTTP')?error.message:`${source} connection failed. Please retry.`);
          await wait(error.delay || 1000*2**attempt,options.signal);
        }
      }
    } finally {unlock();}
  }
  checkI(r) {if(r.error_code!==undefined) throw new Error(Number(r.error_code)===2?'Inkbunny session expired. Reconnect in Settings.':`Inkbunny API error ${Number(r.error_code)}. Check account access and retry.`);return r;}
  async sid() {
    if(this.vault.data.inkbunny?.sid) return this.vault.data.inkbunny.sid;
    if(this.guest) return this.guest;
    if(!this.guestTask) this.guestTask=(async()=>{
      const r=this.checkI(await this.request('inkbunny','/api_login.php',{username:'guest'}));
      if(!r.sid) throw new Error('Inkbunny did not create a guest session.');
      this.checkI(await this.request('inkbunny','/api_userrating.php',{sid:r.sid,'tag[2]':'yes','tag[3]':'yes','tag[4]':'yes','tag[5]':'yes'}));
      this.guest=r.sid;return r.sid;
    })().finally(()=>{this.guestTask=null;});
    return this.guestTask;
  }
  async ink(path,params,signal) {
    const sid=await this.sid(), r=await this.request('inkbunny',path,{...params,sid},{signal});
    if(Number(r.error_code)===2 && !this.vault.data.inkbunny) {
      this.guest='';return this.checkI(await this.request('inkbunny',path,{...params,sid:await this.sid()},{signal}));
    }
    return this.checkI(r);
  }
  status() {return {e621:this.vault.data.e621?.name || '',inkbunny:this.vault.data.inkbunny?.name || '',notice:this.vault.notice || ''};}
  async connect(source,name,secret) {
    if(typeof name!=='string'||!name.trim()||typeof secret!=='string'||!secret.trim()||name.length>100||secret.length>1000) throw new Error('Enter your username and credentials.');
    if(source==='e621') {
      const headers={Authorization:'Basic '+Buffer.from(name.trim()+':'+secret.trim()).toString('base64')};
      const r=await this.request('e621','/users/'+encodeURIComponent(name.trim())+'.json',{}, {headers});
      if(!Object.hasOwn(r,'blacklisted_tags') || String(r.name).toLowerCase()!==name.trim().toLowerCase()) throw new Error('Could not verify your e621 account/API key.');
      const a={name:r.name,key:secret.trim(),id:String(r.id),blacklist:r.blacklisted_tags || ''};
      await this.vault.set(source,a);
    } else if(source==='inkbunny') {
      const r=this.checkI(await this.request('inkbunny','/api_login.php',{username:name.trim(),password:secret}));
      if(!r.sid || !r.user_id) throw new Error('No Inkbunny session returned. Enable API Access in your Inkbunny account settings.');
      await this.vault.set(source,{name:name.trim(),sid:r.sid,id:String(r.user_id),ratings:r.ratingsmask || '11111'});
    } else throw new Error('Unknown source');
    return this.status();
  }
  async disconnect(source) {
    if(source==='inkbunny' && this.vault.data.inkbunny?.sid) {try{await this.ink('/api_logout.php',{});}catch{}}
    await this.vault.set(source,null);return this.status();
  }
  async refreshBlacklist() {
    const a=this.vault.data.e621;if(!a) throw new Error('Connect e621 first.');
    const r=await this.request('e621',`/users/${a.id}.json`);
    if(!Object.hasOwn(r,'blacklisted_tags')) throw new Error('Reconnect your e621 account.');
    await this.vault.set('e621',{...a,blacklist:r.blacklisted_tags || ''});return this.status();
  }
  async search(source,q,page=1,rid=null,signal) {
    page=Math.max(1,Math.min(10000,Number(page)||1));
    if(source==='e621') {
      if(q.mode==='following') {
        const names=this.store.data.follows; if(!names.length) return {items:[],more:false};
        const pages=[];
        for(let i=0;i<names.length;i+=4) pages.push(await this.search(source,{...q,mode:'explore',e621:names.slice(i,i+4).map(a=>'~'+a).join(' ')},page,null,signal));
        return {items:mergeArtwork([],pages.flatMap(x=>x.items),this.vault.data.e621?.blacklist),more:pages.some(x=>x.more)};
      }
      if(q.mode==='favorites'&&!this.vault.data.e621) return {items:[],more:false};
      const params={v1:true,tags:String(q.e621 || '').slice(0,2000),limit:40,page};
      if(q.mode==='favorites') params.user_id=this.vault.data.e621.id;
      const r=await this.request(source,q.mode==='favorites'?'/favorites.json':'/posts.json',params,{signal});
      if(!Array.isArray(r.posts)) throw new Error('Unexpected e621 response.');
      return {items:r.posts.map(parseE).map(a=>this.rememberDetail(a)).filter(a=>!blacklisted(a,this.vault.data.e621?.blacklist)),more:r.posts.length>=40};
    }
    if(source!=='inkbunny') throw new Error('Unknown source');
    if(q.mode!=='explore'&&!this.vault.data.inkbunny) return {items:[],more:false};
    const params={page,submissions_per_page:40};
    if(rid) params.rid=String(rid);
    else {
      Object.assign(params,{text:String(q.inkbunny || '').slice(0,2000),keywords:'yes',orderby:'create_datetime',get_rid:'yes'});
      if(q.inkArtist) params.username=String(q.inkArtist).slice(0,100);
      if(q.mode==='favorites') params.favs_user_id=this.vault.data.inkbunny.id;
      if(q.mode==='following') params.unread_submissions='yes';
    }
    const r=await this.ink('/api_search.php',params,signal);
    if(!Array.isArray(r.submissions)) throw new Error('Unexpected Inkbunny response.');
    return {items:r.submissions.map(parseI).filter(a=>!a.blocked).map(a=>({...a,favorite:q.mode==='favorites'||a.favorite})),more:page<Number(r.pages_count),rid:r.rid || rid};
  }
  async details(source,id) {
    const key=this.detailKey(source,id),cached=this.detailsCache.get(key);
    if(cached&&cached.expires>Date.now()&&!blacklisted(cached.art,this.vault.data.e621?.blacklist))return {...cached.art};
    if(this.detailTasks.has(key))return this.detailTasks.get(key);
    const task=this.fetchDetails(source,id).then(a=>this.rememberDetail(a,key)).finally(()=>this.detailTasks.delete(key));
    this.detailTasks.set(key,task);return task;
  }
  async fetchDetails(source,id) {
    if(!/^\d+$/.test(String(id))) throw new Error('Invalid submission ID');
    let a;
    if(source==='e621') {const r=await this.request(source,`/posts/${id}.json`,{v1:true});if(!r.post) throw new Error('Submission unavailable.');a=parseE(r.post);}
    else {const r=await this.ink('/api_submissions.php',{submission_ids:id,show_pools:'yes'});if(!r.submissions?.[0]) throw new Error('Submission unavailable to this session.');a=parseI(r.submissions[0]);}
    if(blacklisted(a,this.vault.data.e621?.blacklist)) throw new Error('This artwork is blocked by your source preferences.');
    return a;
  }
  async favorite(id,desired) {
    if(!this.vault.data.e621) throw new Error('Connect e621 in Settings first.');
    if(!/^\d+$/.test(String(id))) throw new Error('Invalid submission ID');
    return this.request('e621',desired?'/favorites.json':`/favorites/${id}.json`,desired?{post_id:id}:{},{method:desired?'POST':'DELETE'});
  }
  async suggestions(source,text) {
    if(typeof text!=='string'||text.length<2||text.length>150) return [];
    if(source==='e621') {const term=text.split(' ').pop().replace(/^[-~]/,'');if(term.includes(':'))return[];
      const r=await this.request(source,'/tags.json',{'search[name_matches]':term+'*',limit:8});return (Array.isArray(r)?r:[]).map(t=>t.name);}
    const r=await this.request('inkbunny','/api_search_autosuggest.php',{keyword:text,ratingsmask:this.vault.data.inkbunny?.ratings || '11111'});
    return (r.results || []).slice(0,8).map(x=>String(x.value).replace(/<[^>]*>/g,'').replace(/&amp;/g,'&'));
  }
  async watched() {if(!this.vault.data.inkbunny) throw new Error('Connect Inkbunny first.');const r=await this.ink('/api_watchlist.php',{orderby:'alphabetical'});return (r.watches || []).map(x=>x.username);}
  async pool(id) {if(!/^\d+$/.test(String(id)))throw new Error('Invalid pool ID');return this.request('e621',`/pools/${id}.json`);}
}
module.exports={Services,UA};
