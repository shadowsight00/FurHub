'use strict';
const fs=require('node:fs');const path=require('node:path');
function atomic(file,data) {fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file+'.tmp',data,{mode:0o600});fs.renameSync(file+'.tmp',file);}
class Store {
  constructor(dir) {
    this.file=path.join(dir,'library.json');
    this.data={settings:{size:220,newest:true,autoplay:true,reduced:false,enabled:['e621','inkbunny'],directory:''},follows:[],viewed:[],records:[],history:[],downloads:[]};
    try {const d=JSON.parse(fs.readFileSync(this.file,'utf8'));this.data={...this.data,...d,settings:{...this.data.settings,...d.settings}};}catch{}
    this.data.downloads=this.data.downloads.map(d=>['Queued','Downloading'].includes(d.state)?{...d,state:'Interrupted',message:'Restart the download to continue.'}:d);
  }
  save(){atomic(this.file,JSON.stringify(this.data));}
}
class Vault {
  constructor(dir,safeStorage){this.file=path.join(dir,'accounts.bin');this.crypto=safeStorage;this.data={};this.notice='';this.tail=Promise.resolve();}
  async load(){try{if(fs.existsSync(this.file)){const r=await this.crypto.decryptStringAsync(fs.readFileSync(this.file));this.data=JSON.parse(r.result);}}catch{this.notice='Saved accounts could not be decrypted. Reconnect in Settings.';}}
  async set(source,value){
    if(!['e621','inkbunny'].includes(source))throw new Error('Unknown source');
    const operation=this.tail.then(async()=>{
      if(!await this.crypto.isAsyncEncryptionAvailable())throw new Error('Windows credential encryption is unavailable. Account was not saved.');
      const next={...this.data};if(value)next[source]=value;else delete next[source];
      const encrypted=await this.crypto.encryptStringAsync(JSON.stringify(next));atomic(this.file,encrypted);this.data=next;this.notice='';
    });this.tail=operation.catch(()=>{});return operation;
  }
}
module.exports={Store,Vault,atomic};
