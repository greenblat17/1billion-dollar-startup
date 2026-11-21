import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {loadModule} from './helpers/load-module.mjs';

async function screen(data,options={}){
 const elements=new Map(),calls=[];
 let nextData=data;
 const element=()=>({children:[],setAttribute(){},append(...children){this.children.push(...children);},replaceChildren(...children){this.children=children;}});
 const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const context=vm.createContext({window:{Telegram:{WebApp:{initData:'signed',initDataUnsafe:{start_param:options.startParam},ready(){},expand(){}}},addEventListener(){}},
 document:{getElementById:get,createElement(){if(options.renderError)throw Error('UNSAFE_DOM_ERROR');return element();}},location:{search:options.search??'?view=progress&lesson=must-not-open',origin:'https://test'},URLSearchParams,
 crypto:{randomUUID:()=> 'test'},fetch:async(url,request)=>{
  calls.push({url,body:JSON.parse(request.body)});
  if(url==='/miniapp/diagnostics')throw Error('diagnostics unavailable');
  if(options.fail)throw Error('UNSAFE_NETWORK_ERROR');
  return {ok:!options.authError&&!options.responseError,
   headers:{get:()=>options.errorId},
   json:async()=>{
    if(options.parseError)throw Error('UNSAFE_RESPONSE_BODY');
    return options.authError?{detail:'telegram_auth_expired'}:options.responseError?{detail:'UNSAFE_SERVER_DETAIL'}:nextData;
   }};
 },
 navigator:{mediaDevices:{getUserMedia(){throw Error('progress must not request microphone');}}},WebSocket:class{constructor(){throw Error('progress must not open voice');}}});
 await loadModule(context);
 await new Promise(resolve=>setImmediate(resolve));
 return {get,calls,setData:value=>{nextData=value;}};
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
 const {get,calls}=await screen(null,{fail:true});
 assert.match(get('progress-title').textContent,/загрузить прогресс/);
 assert.equal(get('progress-refresh').disabled,false);
 await get('progress-refresh').onclick();
 assert.deepEqual(calls.filter(call=>call.url==='/miniapp/diagnostics').map(call=>call.body),[
  {action:'progress',initData:'signed',stage:'transport',code:'request_failed'},
  {action:'progress',initData:'signed',stage:'transport',code:'request_failed'},
 ]);
});

 test('expired Telegram auth offers return instead of an ineffective refresh',async()=>{
 const {get,calls}=await screen(null,{authError:true});
 assert.deepEqual(calls.map(call=>call.url),['/miniapp/api']);
 assert.equal(get('progress-refresh').hidden,true);
 assert.equal(get('progress-back').className,'primary-action');
 assert.equal(get('progress-footer').hidden,true);
 assert.match(get('progress-state').textContent,/заново/);
 const network=await screen(null,{fail:true});
 assert.equal(network.get('progress-refresh').hidden,false);
 assert.equal(network.get('progress-back').className,'');
 });

test('state presentation returns to the ready roadmap and restores action order',async()=>{
 const {get,setData}=await screen({status:'no_roadmap'});
 assert.equal(get('progress-symbol').hidden,false);
 assert.equal(get('progress-actions').children[0],get('progress-back'));
 setData({status:'ready',goal:{value:'Work'},steps:[],evidenceStatus:'reviewed'});
 await get('progress-refresh').onclick();
 assert.equal(get('progress-title').textContent,'Мой прогресс');
 assert.equal(get('progress-shell').className,'progress-shell');
 assert.equal(get('progress-symbol').hidden,true);
 assert.equal(get('progress-content').hidden,false);
 assert.equal(get('progress-footer').hidden,false);
 assert.equal(get('progress-back').className,'');
 assert.equal(get('progress-actions').children[0],get('progress-refresh'));
});


test('progress reports parsing, rendering and HTTP rejection as distinct safe observations',async()=>{
 const errorId='1234567890abcdef1234567890abcdef';
 const examples=[
  [null,{parseError:true,errorId},'parse','invalid_response',errorId],
  [{status:'unknown'},{},'parse','invalid_response',undefined],
  [{status:'ready'},{},'parse','invalid_response',undefined],
  [{status:'ready',steps:[{status:'current'}]},{renderError:true},'render','render_failed',undefined],
  [null,{responseError:true,errorId},'response','response_rejected',errorId],
  [null,{responseError:true,errorId:'UNSAFE_NON_OPAQUE_ID'},'response','response_rejected',undefined],
 ];
 for(const [data,options,stage,code,serverErrorId] of examples){
  const {get,calls}=await screen(data,options);
  const diagnostics=calls.filter(call=>call.url==='/miniapp/diagnostics').map(call=>call.body);
  assert.deepEqual(diagnostics,[{initData:'signed',action:'progress',stage,code,...(serverErrorId?{serverErrorId}:{})}]);
  assert.equal(get('progress-content').hidden,true);
  assert.equal(get('progress-footer').hidden,true);
  assert.equal(get('progress-refresh').disabled,false);
 }
});
