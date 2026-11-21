import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,access,rm,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {SourceTextModule} from 'node:vm';
import {build} from '../scripts/build.mjs';

test('standalone artifact contains every local UI asset and no backend code',async()=>{
 const temp=await mkdtemp(join(tmpdir(),'speaky-mini-'));
 try{
  const dist=await build(join(temp,'dist'));
  const html=await readFile(join(dist,'miniapp/index.html'),'utf8');
  for(const match of html.matchAll(/(?:src|href)="(\/miniapp\/assets\/[^"?]+)"/g)) await access(join(dist,match[1]));
  const assets=join(dist,'miniapp/assets');
  const script=await readFile(join(assets,'call.js'),'utf8');
  const api=await readFile(join(assets,'api.js'),'utf8');
  assert.ok(script.includes("'/miniapp/voice'"));
  assert.ok(api.includes("'/miniapp/diagnostics'"));
  await access(join(dist,'miniapp/assets/capture.js'));
  const version=JSON.parse(await readFile(join(dist,'version.json'),'utf8'));
  const modules=new Map();
  for(const name of await readdir(new URL('../src/',import.meta.url),{withFileTypes:true})){
   if(!name.isFile()||!name.name.endsWith('.js'))continue;
   const source=await readFile(join(assets,name.name),'utf8');
   assert.equal(version.files[name.name],createHash('sha256').update(source).digest('hex'));
   modules.set(name.name,new SourceTextModule(source,{identifier:name.name}));
   if(name.name==='schedule.js'||name.name==='progress.js'){
    assert.doesNotMatch(source,/call\.js|WebSocket|AudioContext|getUserMedia/);
   }
  }
  for(const module of modules.values()){
   if(module.status!=='unlinked')continue;
   await module.link(specifier=>{
    const dependency=specifier.startsWith('./')&&modules.get(specifier.slice(2));
    assert.ok(dependency,`Missing built module ${specifier}`);
    return dependency;
   });
  }
  await assert.rejects(access(join(dist,'app')));
 }finally{await rm(temp,{recursive:true,force:true});}
});
