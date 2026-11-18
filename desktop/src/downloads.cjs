'use strict';
const fs=require('node:fs');const fsp=fs.promises;const path=require('node:path');const os=require('node:os');
const {pipeline}=require('node:stream/promises');const {Readable,Transform}=require('node:stream');const {randomUUID}=require('node:crypto');
const {ZipArchive}=require('archiver');const {safeFilename}=require('./core.cjs');
class Downloads {
  constructor(store,services,fetchAsset,notify){this.store=store;this.services=services;this.fetchAsset=fetchAsset;this.notify=notify;this.running=false;this.controllers=new Map();}
  emit(){this.store.save();this.notify(this.store.data.downloads);}
  add(spec){if(!this.store.data.settings.directory)throw new Error('Choose a download folder in Settings first.');
    const d={id:randomUUID(),...spec,state:'Queued',done:0,total:0,bytes:0,message:'',created:Date.now(),directory:this.store.data.settings.directory};this.store.data.downloads.unshift(d);this.emit();this.pump();return d.id;}
  cancel(id){const d=this.store.data.downloads.find(x=>x.id===id);if(!d)return;if(d.state==='Queued'){d.state='Canceled';this.emit();}this.controllers.get(id)?.abort();}
  retry(id){const d=this.store.data.downloads.find(x=>x.id===id);if(!d)throw new Error('Download not found');return this.add({kind:d.kind,label:d.label,source:d.source,artId:d.artId,ids:d.ids,fileIndex:d.fileIndex,poolId:d.poolId});}
  async unique(dir,name){for(let n=0;n<10000;n++){const p=path.join(dir,n?`${path.parse(name).name} (${n})${path.extname(name)}`:name);try{const handle=await fsp.open(p,'wx');await handle.close();return p;}catch(e){if(e.code!=='EEXIST')throw e;}}throw new Error('Could not allocate a filename');}
  filename(a,f,index){const ext=path.extname(new URL(f.url).pathname).replace(/[^.a-z0-9]/gi,'') || '.bin';return safeFilename(`${a.source}-${a.id}-${index+1}${ext}`);}
  async file(a,f,target,d,signal){
    const temp=target+'.part';let last=0;
    try{
      const r=await this.fetchAsset(a.source,f.url,{signal});if(!r.ok||!r.body)throw new Error(`Media request failed (${r.status})`);
      const meter=new Transform({transform:(chunk,enc,done)=>{d.bytes+=chunk.length;if(Date.now()-last>350){last=Date.now();this.notify(this.store.data.downloads);}done(null,chunk);}});
      await pipeline(Readable.fromWeb(r.body),meter,fs.createWriteStream(temp,{flags:'wx'}),{signal});await fsp.rename(temp,target);
    }finally{await fsp.rm(temp,{force:true}).catch(()=>{});}
  }
  async pump(){if(this.running)return;this.running=true;
    try{let d;while((d=this.store.data.downloads.find(x=>x.state==='Queued'))){const c=new AbortController();this.controllers.set(d.id,c);d.state='Downloading';this.emit();
      try{await fsp.mkdir(d.directory,{recursive:true});if(d.kind==='pool')await this.pool(d,c.signal);else await this.submissions(d,c.signal);d.state=d.skips?'Completed with skips':'Completed';}
      catch(e){d.state=c.signal.aborted?'Canceled':'Failed';d.message=c.signal.aborted?'Canceled by you':e.message;}
      finally{this.controllers.delete(d.id);this.emit();}
    }}finally{this.running=false;}}
  async submissions(d,signal){const ids=d.ids || [{source:d.source,id:d.artId}];d.total=ids.length;let skips=0;
    for(const item of ids){if(signal.aborted)throw new Error('Canceled');try{const a=await this.services.details(item.source,item.id);const files=Number.isInteger(d.fileIndex)&&d.fileIndex>=0?[a.files[d.fileIndex]].filter(Boolean):a.files;
      if(!files.length)throw new Error('No downloadable files');
      for(const f of files){const target=await this.unique(d.directory,this.filename(a,f,a.files.indexOf(f)));try{await this.file(a,f,target,d,signal);}catch(e){await fsp.rm(target,{force:true});throw e;}}
    }catch(e){if(signal.aborted)throw e;skips++;}d.done++;this.emit();}d.skips=skips;if(skips)d.message=`${skips} submission(s) could not be downloaded.`;
  }
  async pool(d,signal){const pool=await this.services.pool(d.poolId);d.total=pool.post_ids.length;const temp=await fsp.mkdtemp(path.join(os.tmpdir(),'unifiedart-pool-'));let target,zip,complete,output;
    try{target=await this.unique(d.directory,safeFilename(`e621-pool-${d.poolId}-${pool.name || 'pool'}.zip`));zip=new ZipArchive({zlib:{level:1}});output=fs.createWriteStream(target+'.part');
      complete=pipeline(zip,output,{signal});complete.catch(()=>{});
      const entries=[];for(let i=0;i<pool.post_ids.length;i++){if(signal.aborted)throw new Error('Canceled');const id=pool.post_ids[i],entry={order:i+1,postId:id,files:[],status:'ok'};
        try{const a=await this.services.details('e621',id);if(!a.files.length)throw new Error('No media');for(let j=0;j<a.files.length;j++){const name=`${String(i+1).padStart(5,'0')}-${this.filename(a,a.files[j],j)}`,local=path.join(temp,name);await this.file(a,a.files[j],local,d,signal);zip.file(local,{name});entry.files.push(name);}}
        catch(e){if(signal.aborted)throw e;entry.status='unavailable';entry.reason='Blocked, deleted, inaccessible, or download failed';d.skips=(d.skips||0)+1;}entries.push(entry);d.done++;this.emit();}
      zip.append(JSON.stringify({poolId:d.poolId,name:pool.name,source:`https://e621.net/pools/${d.poolId}`,complete:!d.skips,entries},null,2),{name:'manifest.json'});
      await zip.finalize();await complete;await fsp.rename(target+'.part',target);if(d.skips)d.message=`${d.skips} posts were unavailable; see manifest.json.`;
    }catch(e){zip?.abort();output?.destroy();await complete?.catch(()=>{});if(target){await fsp.rm(target+'.part',{force:true}).catch(()=>{});await fsp.rm(target,{force:true}).catch(()=>{});}throw e;}
    finally{await fsp.rm(temp,{recursive:true,force:true});}
  }
}
module.exports={Downloads};
