import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('real agreed regular meetings remain joinable without treating demo as subscription',async()=>{
 const elements=new Map();
 const element=()=>({children:[],append(...children){this.children.push(...children);},replaceChildren(){this.children=[];}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const lesson={kind:'regular',starts_at:'2026-11-15T12:00:00Z',timezone:'UTC',status:'scheduled'};
 const schedule={timezone:'UTC',agreement:{demo:true,localTime:'12:00'}};
 const context=vm.createContext({window:{addEventListener(){}},document:{getElementById:get,createElement:element},
  location:{search:'',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'test'},
  fetch:async()=>({ok:true,json:async()=>({lessons:[{...lesson,id:'real',launch_mode:'executable'},
   {...lesson,id:'demo',launch_mode:'placeholder'}],schedules:[{...schedule,id:'agreed',agreement:{...schedule.agreement,executable:true}},
   {...schedule,id:'preliminary'}],demoReceipts:[]})})});
 const source=(await readFile(new URL('../src/app.js',import.meta.url),'utf8')).replace(/progressMode\?progress\(\):lessonId\?connect\(\):list\(\);\s*$/, '');
 vm.runInContext(source,context);
 await vm.runInContext('list()',context);
 const [real,placeholder]=get('meetings').children;
 assert.equal(real.children[0].textContent,'Урок английского');
 assert.equal(real.children[2].disabled,false);
 assert.equal(placeholder.children[2].disabled,true);
 const [agreed,preliminary]=get('schedules').children;
 assert.match(agreed.children[0].textContent,/Согласованное/);
 assert.match(agreed.children[1].textContent,/подписка не включается/);
 assert.doesNotMatch(agreed.children[1].textContent,/недоступны/);
 assert.match(preliminary.children[1].textContent,/недоступны/);
});
