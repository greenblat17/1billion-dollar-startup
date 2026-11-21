import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';

for(const duration of [15,20,30])test(`calendar shows the saved ${duration}-minute slot without opening voice`,async()=>{
 const elements=new Map(),requests=[],links=[];
 const element=()=>({children:[],append(...children){this.children.push(...children);},replaceChildren(){this.children=[];}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',ready(){},expand(){},openLink:url=>links.push(url)}},addEventListener(){}},
  document:{body:{classList:{toggle(){}}},getElementById:get,createElement:element},location:{search:'?view=calendar&lesson=owned'},
  URLSearchParams,crypto:{randomUUID:()=> 'test'},navigator:{mediaDevices:{getUserMedia(){throw Error('must not open microphone');}}},
  fetch:async(url,options)=>{requests.push(JSON.parse(options.body));return {ok:true,json:async()=>({
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
});
