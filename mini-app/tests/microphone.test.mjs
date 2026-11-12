import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('joining and reconnecting enable microphone; toggle shows state and action',async()=>{
 const elements=new Map();
 const element=id=>{if(!elements.has(id))elements.set(id,{dataset:{},setAttribute(name,value){this[name]=value;}});return elements.get(id);};
 const track={enabled:false},sockets=[];
 class Socket{static OPEN=1;constructor(){this.readyState=1;sockets.push(this);}send(){}close(){}}
 class AudioContext{state='running';audioWorklet={addModule:async()=>{}};resume=async()=>{};createMediaStreamSource(){return{connect(){}};}createGain(){return{gain:{},connect(){}};}}
 class AudioWorkletNode{port={postMessage(){}};connect(node){return node;}}
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'test',ready(){},expand(){}}},addEventListener(){}},
 document:{getElementById:element},location:{search:'?lesson=test',origin:'https://example.test'},
 URLSearchParams,crypto:{randomUUID:()=> 'test'},performance:{now:()=>0},
 navigator:{mediaDevices:{getUserMedia:async()=>({getAudioTracks:()=>[track]})}},
 AudioContext,AudioWorkletNode,WebSocket:Socket,setInterval:()=>1,clearInterval(){},setTimeout,clearTimeout});
 const source=(await readFile(new URL('../src/app.js',import.meta.url),'utf8')).replace(/lessonId\?connect\(\):list\(\);\s*$/, '');
 vm.runInContext(source,context);
 await vm.runInContext('connect()',context);
 assert.equal(track.enabled,true);
 assert.equal(element('mute').disabled,true);
 await sockets.at(-1).onmessage({data:JSON.stringify({type:'connected'})});
 assert.equal(element('mute').disabled,false);
 assert.equal(element('mic-status').textContent,'Микрофон включён — можно говорить');
 assert.equal(element('mic-action').textContent,'Выключить');
 element('mute').onclick();
 assert.equal(track.enabled,false);
 assert.equal(element('mute')['aria-pressed'],'true');
 assert.equal(element('mic-action').textContent,'Включить');
 assert.equal(element('mic-status').textContent,'Микрофон выключен — Саша вас не слышит');
 await vm.runInContext('connect()',context);
 await sockets.at(-1).onmessage({data:JSON.stringify({type:'connected'})});
 assert.equal(track.enabled,true);
 assert.equal(element('mute')['aria-pressed'],'false');
 assert.equal(element('mic-action').textContent,'Выключить');
});
