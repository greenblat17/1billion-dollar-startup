#!/usr/bin/env node
// Drive the built Mini App in an isolated Chrome profile through CDP.
import {spawn} from 'node:child_process';
import {readFile, mkdir, writeFile, rm, open} from 'node:fs/promises';
import {join} from 'node:path';

const runDir=process.argv[2];
if(!runDir)throw Error('Usage: node drive-progress.mjs RUN_DIR');
const instance=JSON.parse(await readFile(join(runDir,'instance.json'),'utf8'));
const evidence=join(runDir,'evidence');
await mkdir(evidence,{recursive:true});
const profile=join(runDir,'chrome-profile');
const log=await open(join(evidence,'chrome.log'),'w');
const chrome=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',[
 '--headless=new','--no-first-run','--no-default-browser-check',
 '--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:['ignore',log.fd,log.fd]});
let socket;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));

try{
 let port;
 for(let i=0;i<100;i++){
  try{port=Number((await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);break;}
  catch{if(chrome.exitCode!==null)throw Error(`Chrome exited ${chrome.exitCode}`);await pause(100);}
 }
 if(!port)throw Error('Chrome did not expose DevToolsActivePort');
 const targets=await (await fetch(`http://127.0.0.1:${port}/json`)).json();
 const target=targets.find(t=>t.type==='page');
 if(!target)throw Error('No Chrome page target');
 socket=new WebSocket(target.webSocketDebuggerUrl);
 await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
 let seq=0;
 const pending=new Map();
 socket.addEventListener('message',event=>{
  const result=JSON.parse(event.data);
  if(!result.id)return;
  const item=pending.get(result.id);if(!item)return;pending.delete(result.id);
  result.error?item.reject(Error(result.error.message)):item.resolve(result.result);
 });
 const send=(method,params={})=>new Promise((resolve,reject)=>{
  const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));
 });
 const evaluate=async expression=>{
  const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails)throw Error(result.exceptionDetails.text);
  return result.result.value;
 };
 await send('Page.enable');
 await send('Runtime.enable');
 await send('Emulation.setDeviceMetricsOverride',{width:412,height:915,deviceScaleFactor:1,mobile:true});
 await send('Page.navigate',{url:`${instance.url}/miniapp?view=progress`});
 let state;
 for(let i=0;i<100;i++){
  state=await evaluate(`(() => ({title:document.getElementById('progress-title')?.textContent,
   goal:document.getElementById('progress-goal')?.textContent,
   steps:[...document.querySelectorAll('#progress-steps li h2')].map(x=>x.textContent),
   visible:!document.getElementById('progress')?.hidden}))()`);
  if(state.visible&&state.goal)break;
  await pause(100);
 }
 if(!state?.visible||state.goal!=='Уверенно говорить на рабочих встречах'||state.steps.length!==3)
  throw Error(`Progress did not render: ${JSON.stringify(state)}`);
 await writeFile(join(evidence,'before.json'),JSON.stringify(state,null,2)+'\n');
 const before=await send('Page.captureScreenshot',{format:'png'});
 await writeFile(join(evidence,'before.png'),Buffer.from(before.data,'base64'));
 await evaluate(`document.getElementById('progress-refresh').click()`);
 let lines=[];
 for(let i=0;i<100;i++){
  lines=(await readFile(join(runDir,'requests.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);
  if(lines.filter(x=>x.action==='progress').length>=2)break;
  await pause(100);
 }
 if(lines.filter(x=>x.action==='progress').length!==2)throw Error(`Expected two progress requests: ${JSON.stringify(lines)}`);
 const after=await evaluate(`(() => ({title:document.getElementById('progress-title').textContent,
  goal:document.getElementById('progress-goal').textContent,
  steps:[...document.querySelectorAll('#progress-steps li h2')].map(x=>x.textContent),
  visible:!document.getElementById('progress').hidden}))()`);
 if(!after.visible||after.goal!==state.goal||after.steps.length!==3)throw Error('Progress changed unexpectedly after refresh');
 await writeFile(join(evidence,'after.json'),JSON.stringify(after,null,2)+'\n');
 const screenshot=await send('Page.captureScreenshot',{format:'png'});
 await writeFile(join(evidence,'after.png'),Buffer.from(screenshot.data,'base64'));
 console.log(JSON.stringify({result:'pass',feature:'P1-P2',evidence,requests:lines.length}));
}finally{
 if(socket)socket.close();
 chrome.kill('SIGTERM');
 if(chrome.exitCode===null)await Promise.race([new Promise(resolve=>chrome.once('exit',resolve)),pause(2000)]);
 await rm(profile,{recursive:true,force:true});
 await log.close();
}
