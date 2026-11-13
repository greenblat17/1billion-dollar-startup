import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=(await readFile(new URL('../src/app.js',import.meta.url),'utf8')).replace(/calendarMode\?calendar\(\):progressMode\?progress\(\):lessonId\?connect\(\):list\(\);\s*$/, '');
function harness(){
 const elements=new Map(),timers=[],sockets=[],events={};let microphoneCalls=0;
 const element=id=>{if(!elements.has(id))elements.set(id,{dataset:{},setAttribute(){},replaceChildren(){}});return elements.get(id);};
 const track={stop(){this.stopped=true;}};
 const state={serverNow:'2026-11-13T09:00:00Z',lesson:{timezone:'UTC',starts_at:'2026-11-13T10:00:00Z',status:'scheduled',launch_mode:'executable'}};
 class Socket{static OPEN=1;constructor(){sockets.push(this);}close(){}}
 class AudioContext{state='running';audioWorklet={addModule:async()=>{}};async resume(){}async close(){}}
 const media={async getUserMedia(){microphoneCalls++;return{getAudioTracks:()=>[track],getTracks:()=>[track]};}};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'test',ready(){},expand(){}}},addEventListener:(name,fn)=>events[name]=fn},document:{getElementById:element},
 location:{search:'?lesson=test',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'attempt'},performance:{now:()=>0},
 fetch:async()=>({ok:true,json:async()=>state}),navigator:{mediaDevices:media},AudioContext,WebSocket:Socket,
 setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length;},clearTimeout(){},clearInterval(){}});
 vm.runInContext(source,context);
 return {state,media,track,elements,element,timers,sockets,events,context,get microphoneCalls(){return microphoneCalls;},connect:()=>vm.runInContext('connect()',context)};
}
test('early arrival waits using server time, rechecks changes, then automatically connects',async()=>{
 const h=harness();await h.connect();
 assert.equal(h.microphoneCalls,0);assert.equal(h.sockets.length,0);
 assert.equal(h.element('lobby-title').textContent,'Ждём начала занятия');
 assert.equal(h.timers.at(-1).ms,15000);
 h.state.lesson.starts_at='2026-11-13T11:00:00Z';h.state.serverNow='2026-11-13T10:00:00Z';
 await h.timers.at(-1).fn();assert.equal(h.microphoneCalls,0);
 h.state.serverNow='2026-11-13T11:00:00Z';await h.timers.at(-1).fn();
 assert.equal(h.microphoneCalls,1);assert.equal(h.sockets.length,1);
});
test('cancelled lesson never requests microphone or connects',async()=>{
 const h=harness();h.state.lesson.status='cancelled';await h.connect();
 assert.equal(h.microphoneCalls,0);assert.equal(h.sockets.length,0);
 assert.match(h.element('error-text').textContent,/недоступна/);
});
test('denied microphone explains permissions and offers retry',async()=>{
 const h=harness();h.state.serverNow=h.state.lesson.starts_at;
 h.media.getUserMedia=async()=>{const e=new Error();e.name='NotAllowedError';throw e;};
 await h.connect();assert.equal(h.sockets.length,0);
 assert.match(h.element('error-text').textContent,/Разрешите микрофон/);
 assert.equal(h.element('retry').textContent,'Повторить подключение');
});
test('closing during microphone permission request stops late stream and prevents connection',async()=>{
 const h=harness();h.state.serverNow=h.state.lesson.starts_at;let resolve;
 h.media.getUserMedia=()=>new Promise(r=>resolve=r);
 const pending=h.connect();while(!resolve)await Promise.resolve();
 h.events.pagehide();resolve({getTracks:()=>[h.track]});await pending;
 assert.equal(h.track.stopped,true);assert.equal(h.sockets.length,0);
});
