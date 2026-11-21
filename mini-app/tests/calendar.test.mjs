import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';

for(const duration of [15,20,30])test(`calendar shows the saved ${duration}-minute slot without opening voice`,async()=>{
 const elements=new Map(),requests=[],links=[],downloads=[];
 let shareError,loadError=false,downloadError=false,openError=false;
 const element=()=>({children:[],append(...children){this.children.push(...children);},replaceChildren(){this.children=[];},click(){downloads.push(this.download);},remove(){}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',ready(){},expand(){},openLink:url=>{
  if(openError)throw Error('UNSAFE_LINK');
  links.push(url);
 }}},addEventListener(){}},
  document:{body:{classList:{toggle(){}},append(){}},getElementById:get,createElement:element},location:{search:'?view=calendar&lesson=owned'},
  URLSearchParams,crypto:{randomUUID:()=> 'test'},
  File:class{constructor(parts,name){this.name=name;}},
  URL:{createObjectURL:()=>{
   if(downloadError)throw Error('UNSAFE_DOWNLOAD');
   return 'blob:test';
  },revokeObjectURL(){}},setTimeout(){},
  navigator:{canShare:()=>true,share:async()=>{if(shareError)throw shareError;},mediaDevices:{getUserMedia(){throw Error('must not open microphone');}}},
  fetch:async(url,options)=>{requests.push(JSON.parse(options.body));
   if(url==='/miniapp/diagnostics')return {ok:true};
   if(loadError)return {ok:true,json:async()=>{throw Error('UNSAFE_CALENDAR_RESPONSE');}};
   return {ok:true,json:async()=>({
   lesson:{id:'owned',starts_at:'2026-11-14T16:00:00Z',timezone:'UTC+03:00',status:'scheduled',duration_minutes:duration},
   googleUrl:'https://calendar.google.com/calendar/render?action=TEMPLATE',ics:'BEGIN:VCALENDAR',filename:'speaky.ics'})};}});
 await loadModule(context);
 await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(requests,[{action:'calendar',initData:'signed',lessonId:'owned'}]);
 const card=get('meetings').children[0];
 assert.match(card.children[1].textContent,/19:00/);
 assert.match(card.children[2].textContent,/UTC\+03:00/);
 assert.equal(card.children.find(child=>child.className==='lesson-duration').textContent,`${duration} минут`);
 card.children.find(child=>child.textContent==='Google Calendar').onclick();
 assert.equal(links.length,1);
 assert.match(card.children.at(-1).textContent,/обновите событие/);
 if(duration===15){
  const download=card.children.find(child=>child.className==='calendar-secondary');
  await download.onclick();
  assert.deepEqual(downloads,[]);
  shareError={name:'AbortError',message:'UNSAFE_ABORT'};
  await download.onclick();
  assert.deepEqual(downloads,[]);
  shareError={name:'Error',message:'UNSAFE_SHARE'};
  await download.onclick();
  assert.deepEqual(downloads,['speaky.ics']);
  downloadError=true;
  await download.onclick();
  assert.deepEqual(downloads,['speaky.ics']);
  openError=true;
  card.children.find(child=>child.textContent==='Google Calendar').onclick();
  loadError=true;
  await get('retry').onclick();
  assert.deepEqual(requests.slice(1),[
   {initData:'signed',action:'calendar',stage:'export',code:'share_cancelled'},
   {initData:'signed',action:'calendar',stage:'export',code:'share_failed_download_fallback'},
   {initData:'signed',action:'calendar',stage:'export',code:'download_failed'},
   {initData:'signed',action:'calendar',stage:'export',code:'external_open_failed'},
   {action:'calendar',initData:'signed',lessonId:'owned'},
   {initData:'signed',action:'calendar',stage:'parse',code:'invalid_response'},
  ]);
 }
});
