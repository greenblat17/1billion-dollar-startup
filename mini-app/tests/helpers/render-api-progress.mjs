// Feed a real HTTP response into the production view. No browser or visual acceptance is implied.
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const data=JSON.parse(readFileSync(0,'utf8'));
const elements=new Map();
const element=()=>({children:[],setAttribute(){},append(...items){this.children.push(...items);},replaceChildren(){this.children=[];}});
const get=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
const context=vm.createContext({window:{addEventListener(){}},document:{getElementById:get,createElement:element},
 location:{search:'?view=progress'},URLSearchParams,crypto:{randomUUID:()=> 'render-check'},
 fetch:async()=>({ok:true,json:async()=>data})});
vm.runInContext(readFileSync(new URL('../../src/app.js',import.meta.url),'utf8'),context);
await new Promise(resolve=>setImmediate(resolve));
assert.equal(get('progress').hidden,false);
assert.equal(get('progress-goal').textContent,data.goal.value);
assert.equal(get('progress-steps').children.length,data.steps.length);
data.steps.forEach((step,index)=>{
 const row=get('progress-steps').children[index];
 assert.equal(row.className,'roadmap-step '+step.status);
 assert.equal(row.children[1].children[1].textContent,step.outcome);
});
