import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';
async function harness({audioFailure,activation}={}){
 const elements=new Map(),timers=[],sockets=[],events={},diagnostics=[];let microphoneCalls=0;
 const element=id=>{if(!elements.has(id))elements.set(id,{dataset:{},children:[],setAttribute(){},
  replaceChildren(){this.children=[];},append(child){this.children.push(child);}});return elements.get(id);};
 const track={stop(){this.stopped=true;}};
 const state={serverNow:'2026-11-13T09:00:00Z',lesson:{timezone:'UTC',starts_at:'2026-11-13T10:00:00Z',status:'scheduled',launch_mode:'executable'}};
 class Socket{
  static OPEN=1;
  readyState=0;
  sent=[];
  constructor(){sockets.push(this);}
  send(frame){if(this.failSend)throw Error('RAW_SOCKET_ERROR');this.sent.push(JSON.parse(frame));}
  close(){this.closed=true;this.readyState=3;}
 }
 class AudioContext{
  constructor(){if(audioFailure==='audio_context')throw Error('RAW_AUDIO_CONTEXT_ERROR');}
  state=activation?'suspended':'running';
  resumeCalls=0;
  audioWorklet={addModule:async()=>{if(audioFailure==='worklet')throw Error('RAW_WORKLET_ERROR');}};
  async resume(){
   if(activation&&++this.resumeCalls===1)return;
   if(activation==='unchanged_once'&&this.resumeCalls===2)return;
   if(activation==='fails')throw Error('RAW_RESUME_ERROR');
   this.state='running';
  }
  async close(){}
 }
 const media={async getUserMedia(){microphoneCalls++;return{getAudioTracks:()=>[track],getTracks:()=>[track]};}};
 const telegram={initData:'test',ready(){},expand(){},close(){this.closed=true;}};
 const context=vm.createContext({window:{Telegram:{WebApp:telegram},addEventListener:(name,fn)=>events[name]=fn},
 document:{getElementById:element,createElement:()=>({})},
 location:{search:'?lesson=test',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'attempt'},performance:{now:()=>0},
 fetch:async(url,options)=>{
  if(url==='/miniapp/diagnostics'){diagnostics.push(JSON.parse(options.body));throw Error('diagnostics unavailable');}
  return {ok:true,json:async()=>state};
 },navigator:{mediaDevices:media},AudioContext,WebSocket:Socket,
 setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length;},clearTimeout(){},clearInterval(){}});
 const call=await loadModule(context,'call.js');
 call.bindCallControls();
 return {state,media,track,elements,element,timers,sockets,events,diagnostics,telegram,get microphoneCalls(){return microphoneCalls;},
  connect:()=>call.startCall('test')};
}
test('early arrival waits using server time, rechecks changes, then automatically connects',async()=>{
 const h=await harness();await h.connect();
 assert.equal(h.microphoneCalls,0);assert.equal(h.sockets.length,0);
 assert.equal(h.element('lobby-title').textContent,'Ждём начала занятия');
 assert.equal(h.element('lobby-time').hidden,false);
 assert.match(h.element('lobby-time').textContent,/10:00/);
 assert.match(h.element('lobby-note').textContent,/автоматически/);
 assert.equal(h.timers.at(-1).ms,15000);
 h.state.lesson.starts_at='2026-11-13T11:00:00Z';h.state.serverNow='2026-11-13T10:00:00Z';
 await h.timers.at(-1).fn();assert.equal(h.microphoneCalls,0);
 h.state.serverNow='2026-11-13T11:00:00Z';await h.timers.at(-1).fn();
 assert.equal(h.microphoneCalls,1);assert.equal(h.sockets.length,1);
});
test('cancelled lesson never requests microphone or connects',async()=>{
 const h=await harness();h.state.lesson.status='cancelled';await h.connect();
 assert.equal(h.microphoneCalls,0);assert.equal(h.sockets.length,0);
 assert.equal(h.element('error-title').textContent,'Урок недоступен');
 assert.match(h.element('error-text').textContent,/Telegram/);
 assert.equal(h.element('retry').hidden,true);
});
test('denied microphone explains permissions and offers retry',async()=>{
 const h=await harness();h.state.serverNow=h.state.lesson.starts_at;
 h.media.getUserMedia=async()=>{const e=new Error('Raw browser error');e.name='NotAllowedError';throw e;};
 await h.connect();assert.equal(h.sockets.length,0);
 assert.match(h.element('error-text').textContent,/Разрешите микрофон/);
 assert.equal(h.element('retry').textContent,'Повторить подключение');
 assert.equal(h.element('retry').hidden,false);
 assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'microphone',code:'microphone_denied'}]);
});
test('missing, busy and unknown microphone failures have distinct safe codes',async()=>{
 const cases=[[null,'microphone_missing'],['NotFoundError','microphone_missing'],
  ['NotReadableError','microphone_busy'],['UnknownError','microphone_failed']];
 for(const [name,code] of cases){
  const h=await harness();h.state.serverNow=h.state.lesson.starts_at;
  h.media.getUserMedia=name?async()=>{const error=Error('RAW_MICROPHONE_ERROR');error.name=name;throw error;}:undefined;
  await h.connect();
  assert.equal(h.sockets.length,0);
  assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'microphone',code}]);
 }
});
test('AudioContext and AudioWorklet failures stop capture and report their stages',async()=>{
 for(const [stage,code] of [['audio_context','audio_context_failed'],['worklet','worklet_failed']]){
  const h=await harness({audioFailure:stage});h.state.serverNow=h.state.lesson.starts_at;
  await h.connect();
  assert.equal(h.track.stopped,true);
  assert.equal(h.sockets.length,0);
  assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage,code}]);
 }
});
test('manual audio activation reports the observed resume outcome',async()=>{
 for(const [activation,code] of [['resumes','audio_activated'],['fails','audio_context_failed']]){
  const h=await harness({activation});h.state.serverNow=h.state.lesson.starts_at;
  const pending=h.connect();await new Promise(resolve=>setImmediate(resolve));
  const [button]=h.element('notice').children;
  assert.equal(button.textContent,'Включить звук');
  assert.equal(h.sockets.length,0);
  await button.onclick();await pending;
  assert.equal(h.sockets.length,activation==='resumes'?1:0);
  assert.deepEqual(h.diagnostics,[
   {initData:'test',action:'voice',attemptId:'attempt',stage:'audio_context',code:'audio_activation_required'},
   {initData:'test',action:'voice',attemptId:'attempt',stage:'audio_context',code},
  ]);
 }
});
test('an activation click that leaves audio suspended is quiet until audio resumes',async()=>{
 const h=await harness({activation:'unchanged_once'});h.state.serverNow=h.state.lesson.starts_at;
 const pending=h.connect();await new Promise(resolve=>setImmediate(resolve));
 const [button]=h.element('notice').children;
 await button.onclick();
 assert.equal(h.sockets.length,0);
 assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'audio_context',code:'audio_activation_required'}]);
 await button.onclick();await pending;
 assert.equal(h.sockets.length,1);
 assert.deepEqual(h.diagnostics.map(item=>item.code),['audio_activation_required','audio_activated']);
});
test('end control distinguishes locally queued, unavailable and failed socket sends',async()=>{
 for(const [socketOpen,failSend,code] of [
  [true,false,'end_queued'],[false,false,'end_not_sent'],[true,true,'end_send_failed'],
 ]){
  const h=await harness();h.state.serverNow=h.state.lesson.starts_at;await h.connect();
  const [socket]=h.sockets;
  socket.readyState=socketOpen?1:0;
  socket.failSend=failSend;
  h.element('end').onclick();h.events.pagehide();
  assert.deepEqual(socket.sent,code==='end_queued'?[{type:'end'}]:[]);
  assert.equal(socket.closed,true);
  assert.equal(h.track.stopped,true);
  assert.equal(h.telegram.closed,true);
  assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'close',code}]);
 }
});
test('closing during microphone permission request stops late stream and prevents connection',async()=>{
 const h=await harness();h.state.serverNow=h.state.lesson.starts_at;let resolve;
 h.media.getUserMedia=()=>new Promise(r=>resolve=r);
 const pending=h.connect();while(!resolve)await Promise.resolve();
 h.events.pagehide();h.events.pagehide();resolve({getTracks:()=>[h.track]});await pending;
 assert.equal(h.track.stopped,true);assert.equal(h.sockets.length,0);
 assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'close',code:'page_hidden'}]);
});
test('activation that resolves after pagehide does not report completion',async()=>{
 const h=await harness({activation:'resumes'});h.state.serverNow=h.state.lesson.starts_at;
 const pending=h.connect();await new Promise(resolve=>setImmediate(resolve));
 const [button]=h.element('notice').children;
 const activation=button.onclick();
 h.events.pagehide();await activation;await pending;
 assert.equal(h.sockets.length,0);
 assert.deepEqual(h.diagnostics,[
  {initData:'test',action:'voice',attemptId:'attempt',stage:'audio_context',code:'audio_activation_required'},
  {initData:'test',action:'voice',attemptId:'attempt',stage:'close',code:'page_hidden'},
 ]);
});

test('recovery keeps diagnostics separate and clears them on a different error',async()=>{
 const h=await harness();
 h.state.serverNow=h.state.lesson.starts_at;await h.connect();
 const socket=h.sockets[0],errorId='1234567890abcdef1234567890abcdef';
 await socket.onmessage({data:JSON.stringify({type:'error',code:'connection_lost',errorId})});
 socket.onerror();socket.onclose({code:1006});
 assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage:'protocol',code:'server_error_observed',serverErrorId:errorId}]);
 assert.equal(h.element('error-title').textContent,'Связь прервалась');
 assert.equal(h.element('retry').hidden,false);
 assert.equal(h.element('error-diagnostics').hidden,false);
 assert.equal(h.element('error-code').textContent,'Код ошибки: '+errorId);
 assert.doesNotMatch(h.element('error-text').textContent,new RegExp(errorId));
 h.state.lesson.status='cancelled';await h.connect();
 assert.equal(h.element('error-diagnostics').hidden,true);
 assert.equal(h.element('error-code').textContent,'');
 assert.equal(h.element('error-help').hidden,true);
 assert.equal(h.element('retry').hidden,true);
});


test('voice distinguishes invalid control messages and socket close codes',async()=>{
 const stages=[
  ['protocol','invalid_message',socket=>socket.onmessage({data:'RAW_INVALID_MESSAGE'}),{}],
  ['socket','connection_lost',socket=>socket.onclose({code:1006}),{closeCode:1006}],
  ['socket','connection_lost',socket=>socket.onerror(),{}],
 ];
 for(const [stage,code,fail,extra] of stages){
  const h=await harness();h.state.serverNow=h.state.lesson.starts_at;await h.connect();
  const socket=h.sockets[0];
  await fail(socket);socket.onerror();socket.onclose({code:1006});
  assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage,code,...extra}]);
  assert.equal(h.element('retry').hidden,false);
 }
});
