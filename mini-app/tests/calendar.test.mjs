import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('calendar entry reads current owned lesson without opening microphone or voice',async()=>{
 const elements=new Map(),requests=[],links=[];
 const element=()=>({children:[],append(...children){this.children.push(...children);},replaceChildren(){this.children=[];}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',ready(){},expand(){},openLink:url=>links.push(url)}},addEventListener(){}},
  document:{getElementById:get,createElement:element},location:{search:'?view=calendar&lesson=owned'},
  URLSearchParams,crypto:{randomUUID:()=> 'test'},navigator:{mediaDevices:{getUserMedia(){throw Error('must not open microphone');}}},
  fetch:async(url,options)=>{requests.push(JSON.parse(options.body));return {ok:true,json:async()=>({
   lesson:{id:'owned',starts_at:'2026-11-14T16:00:00Z',timezone:'UTC+03:00',status:'scheduled',duration_minutes:15},
   googleUrl:'https://calendar.google.com/calendar/render?action=TEMPLATE',ics:'BEGIN:VCALENDAR',filename:'speaky.ics'})};}});
 const source=await readFile(new URL('../src/app.js',import.meta.url),'utf8');
 vm.runInContext(source,context);
 await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(requests,[{action:'calendar',initData:'signed',lessonId:'owned'}]);
 const card=get('meetings').children[0];
 assert.match(card.children[1].textContent,/19:00/);
 card.children[2].onclick();
 assert.equal(links.length,1);
 assert.match(card.children[4].textContent,/обновите событие/);
});

test('widget state updates safely and ignores older revisions',async()=>{
 const source=await readFile(new URL('../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorBookingDelivery.kt',import.meta.url),'utf8');
 const script=source.match(/<script>([\s\S]*?)<\/script>/)[1];
 const elements=new Map();let change;
 const widget={chatData:JSON.stringify({version:2,state:'Подтверждено',date:'14 ноября',time:'19:00',zone:'UTC',footer:'Бесплатно'}),onEvent:(name,fn)=>change=fn};
 vm.runInNewContext(script,{window:{TelegramWidget:widget},document:{getElementById:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);}}});
 assert.equal(elements.get('time').textContent,'19:00');
 widget.chatData=JSON.stringify({version:3,state:'Отменено',date:'<script>not executable</script>'});change();
 assert.equal(elements.get('state').textContent,'Отменено');
 assert.equal(elements.get('date').textContent,'<script>not executable</script>');
 widget.chatData=JSON.stringify({version:1,state:'Подтверждено'});change();
 assert.equal(elements.get('state').textContent,'Отменено');
});
