import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

async function screen(data,options={}){
 const elements=new Map(),calls=[];
 const element=()=>({children:[],setAttribute(){},append(...children){this.children.push(...children);},replaceChildren(...children){this.children=children;}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',initDataUnsafe:{start_param:options.startParam},ready(){},expand(){}}},addEventListener(){}},
 document:{getElementById:get,createElement:element},location:{search:options.search??'?view=progress&lesson=must-not-open',origin:'https://test'},URLSearchParams,
 crypto:{randomUUID:()=> 'test'},fetch:async(url,request)=>{calls.push({url,body:JSON.parse(request.body)});if(options.fail)throw Error('network');return {ok:!options.authError,json:async()=>options.authError?{detail:'telegram_auth_expired'}:data};},
 navigator:{mediaDevices:{getUserMedia(){throw Error('progress must not request microphone');}}},WebSocket:class{constructor(){throw Error('progress must not open voice');}}});
 vm.runInContext(await readFile(new URL('../src/app.js',import.meta.url),'utf8'),context);
 await new Promise(resolve=>setImmediate(resolve));
 return {get,calls,context};
}

test('progress entry uses signed read only, never voice or lesson creation',async()=>{
 const steps=['completed','current','upcoming','needs_review'].map((status,i)=>({id:String(i),outcome:'<script>unsafe</script>',tactic:'Практика',status}));
 const {get,calls}=await screen({status:'ready',goal:{value:'Work'},steps,evidenceStatus:'reviewed'});
 assert.deepEqual(calls,[{url:'/miniapp/api',body:{action:'progress',initData:'signed'}}]);
 assert.equal(get('progress').hidden,false);assert.equal(get('call').hidden,true);
 assert.equal(get('progress-goal').textContent,'Work');
 assert.equal(get('progress-steps').children.length,4);
 assert.equal(get('progress-steps').children[0].children[1].children[1].textContent,'<script>unsafe</script>');
 assert.match(get('progress-steps').children[3].children[1].children[2].textContent,/проверит/);
});

test('empty, preparation, insufficient data and failure remain distinct without fake readiness',async()=>{
 for(const [status,copy] of [['no_roadmap',/ещё не составлен/],['preparing',/готовит/],['insufficient_evidence',/недостаточно данных/],['error',/Не удалось подготовить/]]){
  const {get}=await screen({status},{search:'',startParam:'progress'});
  assert.equal(get('progress-content').hidden,true);assert.match(get('progress-title').textContent+' '+get('progress-state').textContent,copy);
 }
 const {get,context,calls}=await screen(null,{fail:true});
 assert.match(get('progress-title').textContent,/загрузить прогресс/);
 assert.equal(get('progress-refresh').disabled,false);
 await vm.runInContext('progress()',context);assert.equal(calls.length,2);
});

 test('expired Telegram auth offers return instead of an ineffective refresh',async()=>{
 const {get}=await screen(null,{authError:true});
 assert.equal(get('progress-refresh').hidden,true);
 assert.equal(get('progress-back').className,'primary-action');
 assert.equal(get('progress-footer').hidden,true);
 assert.match(get('progress-state').textContent,/заново/);
 const network=await screen(null,{fail:true});
 assert.equal(network.get('progress-refresh').hidden,false);
 assert.equal(network.get('progress-back').className,'');
 });

test('state presentation returns to the ready roadmap and restores action order',async()=>{
 const {get,context}=await screen({status:'no_roadmap'});
 assert.equal(get('progress-symbol').hidden,false);
 assert.equal(get('progress-actions').children[0],get('progress-back'));
 vm.runInContext('renderProgress({status:"ready",goal:{value:"Work"},steps:[],evidenceStatus:"reviewed"})',context);
 assert.equal(get('progress-title').textContent,'Мой прогресс');
 assert.equal(get('progress-shell').className,'progress-shell');
 assert.equal(get('progress-symbol').hidden,true);
 assert.equal(get('progress-content').hidden,false);
 assert.equal(get('progress-footer').hidden,false);
 assert.equal(get('progress-back').className,'');
 assert.equal(get('progress-actions').children[0],get('progress-refresh'));
});
