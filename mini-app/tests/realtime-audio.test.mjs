import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('capture produces 24 kHz PCM; mute clears residual audio',async()=>{
 let Capture;const frames=[];
 class AudioWorkletProcessor{port={postMessage:data=>frames.push(data)};}
 const context=vm.createContext({AudioWorkletProcessor,sampleRate:48000,registerProcessor:(name,type)=>Capture=type});
 vm.runInContext(await readFile(new URL('../src/capture.js',import.meta.url),'utf8'),context);
 const capture=new Capture();
 for(let i=0;i<15;i++)capture.process([[new Float32Array(128).fill(.5)]]);
 assert.equal(frames.length,2);assert.equal(frames[0].byteLength,960);
 capture.port.onmessage({data:{muted:true}});
 capture.process([[new Float32Array(960).fill(.5)]]);
 assert.equal(frames.length,2);
 capture.port.onmessage({data:{muted:false}});
 capture.process([[new Float32Array(960).fill(.5)]]);
 assert.equal(frames.length,3);
});

test('interrupt reports only bounded playback and stops queued sources before end',async()=>{
 const elements=new Map();const sent=[];
 const context=vm.createContext({window:{addEventListener(){}},document:{getElementById:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);}},
 location:{search:'',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'test'},
 WebSocket:{OPEN:1},clearTimeout(){},setTimeout(){},clearInterval(){}});
 const source=(await readFile(new URL('../src/app.js',import.meta.url),'utf8')).replace(/calendarMode\?calendar\(\):progressMode\?progress\(\):lessonId\?connect\(\):list\(\);\s*$/, '');
 vm.runInContext(source,context);
 context.record=data=>sent.push(JSON.parse(data));
 vm.runInContext(`ws={readyState:1,send:record};ctx={currentTime:10};playing.set('one',{start:8,duration:.3,segments:[{start:8,duration:.1},{start:10.5,duration:.2}],sources:[{stop(){}}]});stopAudio();`,context);
 assert.equal(sent[0].playedMs,100);assert.equal(sent[0].complete,false);
 assert.equal(vm.runInContext('playing.size',context),0);
 vm.runInContext(`playing.set('queued',{start:11,duration:1,segments:[{start:11,duration:1}],sources:[]});stopAudio();`,context);
 assert.equal(sent[1].playedMs,0);
});
