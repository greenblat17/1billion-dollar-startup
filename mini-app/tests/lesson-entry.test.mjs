import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';
async function harness(){
 const elements=new Map(),timers=[],sockets=[],events={},diagnostics=[];let microphoneCalls=0;
 const element=id=>{if(!elements.has(id))elements.set(id,{dataset:{},setAttribute(){},replaceChildren(){}});return elements.get(id);};
 const track={stop(){this.stopped=true;}};
 const state={serverNow:'2026-11-13T09:00:00Z',lesson:{timezone:'UTC',starts_at:'2026-11-13T10:00:00Z',status:'scheduled',launch_mode:'executable'}};
 class Socket{static OPEN=1;constructor(){sockets.push(this);}close(){}}
 class AudioContext{state='running';audioWorklet={addModule:async()=>{}};async resume(){}async close(){}}
 const media={async getUserMedia(){microphoneCalls++;return{getAudioTracks:()=>[track],getTracks:()=>[track]};}};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'test',ready(){},expand(){}}},addEventListener:(name,fn)=>events[name]=fn},document:{getElementById:element},
 location:{search:'?lesson=test',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'attempt'},performance:{now:()=>0},
 fetch:async(url,options)=>{
  if(url==='/miniapp/diagnostics'){diagnostics.push(JSON.parse(options.body));throw Error('diagnostics unavailable');}
  return {ok:true,json:async()=>state};
 },navigator:{mediaDevices:media},AudioContext,WebSocket:Socket,
 setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length;},clearTimeout(){},clearInterval(){}});
 const call=await loadModule(context,'call.js');
 call.bindCallControls();
 return {state,media,track,elements,element,timers,sockets,events,diagnostics,get microphoneCalls(){return microphoneCalls;},
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
test('closing during microphone permission request stops late stream and prevents connection',async()=>{
 const h=await harness();h.state.serverNow=h.state.lesson.starts_at;let resolve;
 h.media.getUserMedia=()=>new Promise(r=>resolve=r);
 const pending=h.connect();while(!resolve)await Promise.resolve();
 h.events.pagehide();resolve({getTracks:()=>[h.track]});await pending;
 assert.equal(h.track.stopped,true);assert.equal(h.sockets.length,0);
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
 ];
 for(const [stage,code,fail,extra] of stages){
  const h=await harness();h.state.serverNow=h.state.lesson.starts_at;await h.connect();
  const socket=h.sockets[0];
  await fail(socket);socket.onerror();socket.onclose({code:1006});
  assert.deepEqual(h.diagnostics,[{initData:'test',action:'voice',attemptId:'attempt',stage,code,...extra}]);
  assert.equal(h.element('retry').hidden,false);
 }
});
