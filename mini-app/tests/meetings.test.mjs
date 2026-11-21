import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';

test('real agreed regular meetings remain joinable without treating demo as subscription',async()=>{
 const elements=new Map(),diagnostics=[];
 let meetingsFailure,emptyMeetings=false,demoAttempts=0;
 const element=()=>({children:[],append(...children){this.children.push(...children);},replaceChildren(){this.children=[];}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const lesson={kind:'regular',starts_at:'2026-11-15T12:00:00Z',timezone:'UTC',status:'scheduled'};
 const schedule={timezone:'UTC',agreement:{demo:true,localTime:'12:00'}};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',ready(){},expand(){}}},addEventListener(){}},document:{getElementById:get,createElement:element},
  location:{search:'',origin:'https://test'},URLSearchParams,crypto:{randomUUID:()=> 'test'},
  fetch:async(url,options)=>{
   const body=JSON.parse(options.body);
   if(url==='/miniapp/diagnostics'){diagnostics.push(body);return {ok:true};}
   if(body.action==='demo_pay'&&++demoAttempts===1)throw Error('UNSAFE_PAYMENT_ERROR');
   if(body.action==='demo_pay')return {ok:true,json:async()=>({success:true})};
   if(meetingsFailure==='transport')throw Error('UNSAFE_MEETINGS_ERROR');
   if(meetingsFailure==='parse')return {ok:true,json:async()=>{throw Error('UNSAFE_MEETINGS_RESPONSE');}};
   if(meetingsFailure==='render')return {ok:true,json:async()=>({lessons:null})};
   if(emptyMeetings)return {ok:true,json:async()=>({lessons:[],schedules:[],demoReceipts:[]})};
   return {ok:true,json:async()=>({lessons:[{...lesson,id:'real',launch_mode:'executable'},
   {...lesson,id:'demo',launch_mode:'placeholder'},
   {...lesson,id:'done',launch_mode:'executable',status:'completed'},
   {...lesson,id:'cancelled',launch_mode:'executable',status:'cancelled'},
   {...lesson,id:'unknown',launch_mode:'executable',status:'unknown'}],schedules:[{...schedule,id:'agreed',agreement:{...schedule.agreement,executable:true}},
   {...schedule,id:'preliminary'}],demoReceipts:[]})};}});
 await loadModule(context);
 await new Promise(resolve=>setImmediate(resolve));
 const [real,placeholder,done,cancelled,unknown]=get('meetings').children;
 const action=card=>card.children.find(child=>child.className==='lesson-action');
 assert.equal(real.children[0].textContent,'Урок английского');
 assert.equal(action(real).disabled,false);
 assert.equal(action(placeholder).disabled,true);
 assert.equal(action(done).textContent,'Урок завершён');
 assert.equal(action(cancelled).textContent,'Урок отменён');
 assert.equal(action(unknown).textContent,'Урок недоступен');
 for(const card of [done,cancelled,unknown])assert.equal(action(card).disabled,true);
 const [agreed,preliminary]=get('schedules').children;
 assert.match(agreed.children[0].textContent,/Согласованное/);
 assert.match(agreed.children[1].textContent,/подписка не включается/);
 assert.doesNotMatch(agreed.children[1].textContent,/недоступны/);
 assert.match(preliminary.children[1].textContent,/недоступны/);
 assert.deepEqual(diagnostics,[]);
 const pay=agreed.children[2];
 await pay.onclick();
 assert.equal(pay.disabled,false);
 assert.equal(pay.textContent,'Повторить демо-оплату');
 await pay.onclick();
 assert.equal(pay.disabled,true);
 assert.equal(pay.textContent,'Тестовая оплата подтверждена');
 emptyMeetings=true;
 await get('retry').onclick();
 assert.deepEqual(get('meetings').children,[]);
 assert.match(get('lobby-note').textContent,/Встреч пока нет/);
 for(const stage of ['transport','parse','render']){
  meetingsFailure=stage;
  await get('retry').onclick();
 }
 assert.deepEqual(diagnostics,[
  {initData:'signed',action:'demo_pay',stage:'transport',code:'request_failed'},
  {initData:'signed',action:'meetings',stage:'transport',code:'request_failed'},
  {initData:'signed',action:'meetings',stage:'parse',code:'invalid_response'},
  {initData:'signed',action:'meetings',stage:'render',code:'render_failed'},
 ]);
});
