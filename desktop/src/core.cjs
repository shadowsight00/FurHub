'use strict';
const SOURCES = ['e621', 'inkbunny'];
function stripSession(raw) {
  try { const u = new URL(raw); u.searchParams.delete('sid'); return u.href; } catch { return ''; }
}
function asset(raw) { try { const u = new URL(raw); return u.protocol === 'https:' ? stripSession(u.href) : ''; } catch { return ''; } }
function truth(v) { return ['t', 'true', '1', 'yes'].includes(String(v).toLowerCase()); }
function timestamp(v) { return Date.parse(String(v || '').replace(' ', 'T').replace(/([+-]\d{2})$/, '$1:00')) || 0; }
function media(url, type = '', width = 0, height = 0) {
  return { url: asset(url), type, width: Number(width) || 0, height: Number(height) || 0 };
}
function isVideo(file) { return !!file && (/video/.test(file.type) || /^(webm|mp4|m4v)$/i.test(file.type)); }
function isImage(file) { return !!file && (/^image\//.test(file.type) || /^(jpg|jpeg|png|gif|webp|avif)$/i.test(file.type)); }
function previewVariants(value, out = []) {
  if (!value || typeof value !== 'object') return out;
  for (const v of Object.values(value)) {
    if (typeof v === 'string' && /\.mp4(?:\?|$)/i.test(v) && asset(v)) out.push(media(v, 'video/mp4', value.width, value.height));
    else if (v && typeof v === 'object') previewVariants(v, out);
  }
  return out;
}
function parseE(p) {
  const f = p.file || {}, t = p.tags || {}, id = String(p.id);
  const variants = previewVariants(p.sample?.alternates?.samples).sort((a,b) => a.width*a.height - b.width*b.height);
  return { source:'e621', id, key:`e621:${id}`, title:`Post #${id}`, artists:t.artist || [], tags:Object.values(t).flat(),
    uploadedAt:timestamp(p.created_at), thumbnail:asset(p.preview?.url), files:f.url ? [media(f.url, f.ext, f.width, f.height)].filter(x=>x.url) : [],
    previewMedia:variants[0] || null, favorite:!!p.is_favorited, pools:(p.pools || []).map(String), pageCount:1, format:f.ext || '', blocked:false,
    fields:{id, rating:p.rating, type:f.ext, width:f.width, height:f.height, filesize:f.size, score:p.score?.total, favcount:p.fav_count,
      userid:p.uploader_id, uploader:p.uploader_name || '', tagcount:Object.values(t).flat().length,
      status:Object.keys(p.flags || {}).filter(k=>p.flags[k]).join(' ')} };
}
function parseI(p) {
  const id = String(p.submission_id);
  const files = [...(p.files || [])].sort((a,b)=>Number(a.submission_file_order)-Number(b.submission_file_order))
    .map(f=>media(f.file_url_full, f.mimetype, f.full_size_x, f.full_size_y)).filter(f=>f.url);
  if (!files.length && p.file_url_full) files.push(media(p.file_url_full, p.mimetype));
  const thumb = ['large','medium','huge'].flatMap(s=>[`thumbnail_url_${s}`,`thumbnail_url_${s}_noncustom`]).map(k=>asset(p[k])).find(Boolean) || '';
  return {source:'inkbunny', id, key:`inkbunny:${id}`, title:p.title || `Submission #${id}`, artists:p.username ? [p.username] : [],
    tags:(p.keywords || []).map(k=>k.keyword_name), uploadedAt:timestamp(p.create_datetime), thumbnail:thumb, files,
    favorite:truth(p.favorite), pools:(p.pools || []).map(x=>String(x.pool_id)), pageCount:Number(p.pagecount) || files.length || 1,
    format:(p.mimetype || '').split('/').pop(), blocked:truth(p.hidden), fields:{}, previewMedia:null};
}
function numberMatches(actual, expression, size) {
  function num(s) { const m = /^(\d+(?:\.\d+)?)(b|kb|mb)?$/.exec(s); return size ? m ? Number(m[1])*({kb:1024,mb:1048576}[m[2]] || 1) : NaN : Number(s); }
  actual = Number(actual); if (!Number.isFinite(actual)) return false;
  if (expression.includes('..')) { const [a,b] = expression.split('..'); return (!a || actual>=num(a)) && (!b || actual<=num(b)); }
  const [,op,v] = /^(<=|>=|=<|=>|<|>|=)?(.*)$/.exec(expression), n=num(v);
  if (op==='<') return actual<n; if (op==='>') return actual>n;
  if (['<=','=<'].includes(op)) return actual<=n; if (['>=','=>'].includes(op)) return actual>=n;
  return size ? actual>=Math.floor(n*.95) && actual<=Math.floor(n*1.05) : actual===n;
}
function tokenMatches(a, raw) {
  const prefix = raw.match(/^[~-]*/)[0], token = raw.slice(prefix.length), split=token.indexOf(':');
  const k=split<0?'':token.slice(0,split), v=split<0?token:token.slice(split+1), f=a.fields || {};
  let hit;
  if (['id','tagcount','width','height','score','favcount','userid','filesize'].includes(k)) hit=numberMatches(f[k],v,k==='filesize');
  else if (k==='rating') hit=f.rating===({safe:'s',questionable:'q',explicit:'e'}[v] || v);
  else if (k==='pool') hit=a.pools.includes(v);
  else if (k==='fav') hit=a.favorite;
  else if (['user','uploader','username'].includes(k)) hit=v.startsWith('!') ? numberMatches(f.userid,v.slice(1),false) : String(f.uploader).toLowerCase()===v;
  else if (['status','type'].includes(k)) hit=String(f[k] || '').split(' ').includes(v);
  else { const pattern=new RegExp('^'+token.split('*').map(x=>x.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('.*')+'$'); hit=a.tags.some(x=>pattern.test(x.toLowerCase())); }
  return prefix.includes('-') ? !hit : !!hit;
}
function blacklisted(a, rules='') {
  if (a.blocked) return true;
  if (a.source!=='e621') return false;
  return rules.split(/\r?\n/).some(line=>{
    line=line.split(' #')[0].trim().toLowerCase(); if (!line || line.startsWith('#')) return false;
    const tokens=[...new Set(line.split(/\s+/))], required=tokens.filter(t=>!t.match(/^[~-]*~/)), optional=tokens.filter(t=>!required.includes(t));
    return required.every(t=>tokenMatches(a,t)) && (!optional.length || optional.some(t=>tokenMatches(a,t)));
  });
}
function mergeArtwork(old, fresh, rules='') {
  return [...new Map([...old,...fresh].map(a=>[a.key,a])).values()].filter(a=>!blacklisted(a,rules)).sort((a,b)=>b.uploadedAt-a.uploadedAt || a.key.localeCompare(b.key));
}
function trustedAsset(source, raw) {
  try { const u=new URL(raw); if (u.protocol!=='https:' || u.username || u.password || u.port) return false;
    return source==='e621' ? u.hostname==='e621.net' || u.hostname.endsWith('.e621.net') : source==='inkbunny' && (u.hostname==='inkbunny.net' || u.hostname.endsWith('.ib.metapix.net'));
  } catch { return false; }
}
function mediaSessionUrl(raw,sid) {
  const clean=asset(raw); if (!clean) return '';
  const u=new URL(clean);
  if (sid && u.hostname==='inkbunny.net' && /^\/private_(?:files|thumbnails)\//.test(u.pathname)) u.searchParams.set('sid',sid);
  return u.href;
}
function safeFilename(value) {
  let s=String(value).replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').replace(/[. ]+$/,'').slice(0,150) || 'artwork';
  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(s)) s='_'+s;
  return s;
}
module.exports={SOURCES,asset,truth,timestamp,parseE,parseI,isVideo,isImage,blacklisted,mergeArtwork,trustedAsset,mediaSessionUrl,safeFilename,stripSession};
