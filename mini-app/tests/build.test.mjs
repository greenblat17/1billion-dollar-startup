import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,access,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {build} from '../scripts/build.mjs';

test('standalone artifact contains every local UI asset and no backend code',async()=>{
 const temp=await mkdtemp(join(tmpdir(),'speaky-mini-'));
 try{
  const dist=await build(join(temp,'dist'));
  const html=await readFile(join(dist,'miniapp/index.html'),'utf8');
  for(const match of html.matchAll(/(?:src|href)="(\/miniapp\/assets\/[^"?]+)"/g)) await access(join(dist,match[1]));
  const script=await readFile(join(dist,'miniapp/assets/app.js'),'utf8');
  assert.ok(script.includes("'/miniapp/voice'"));
  assert.ok(script.includes("'/miniapp/diagnostics'"));
  await access(join(dist,'miniapp/assets/capture.js'));
  const version=JSON.parse(await readFile(join(dist,'version.json'),'utf8'));
  assert.equal(Object.keys(version.files).length,5);
  await assert.rejects(access(join(dist,'app')));
 }finally{await rm(temp,{recursive:true,force:true});}
});
