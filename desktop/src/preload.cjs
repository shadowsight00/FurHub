'use strict';
const {contextBridge,ipcRenderer}=require('electron');
const commands=new Set(['boot','search','cancelSearch','details','prepare','connect','disconnect','refreshBlacklist','favorite','suggestions','history','saveSearch','saveArt','createCollection','removeRecord','saveArtist','follow','watched','pool','savePool','settings','chooseDirectory','download','downloadBatch','downloadPool','cancelDownload','retryDownload']);
contextBridge.exposeInMainWorld('unifiedart',{
  call:(method,payload={})=>{if(!commands.has(method))return Promise.reject(new Error('Unknown command'));return ipcRenderer.invoke('unifiedart:call',method,payload);},
  onDownloads:callback=>{const listener=(_event,rows)=>callback(rows);ipcRenderer.on('unifiedart:downloads',listener);return()=>ipcRenderer.removeListener('unifiedart:downloads',listener);}
});
