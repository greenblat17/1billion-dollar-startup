import {readFile} from 'node:fs/promises';
import {SourceTextModule} from 'node:vm';

export async function loadModule(context, entry='app.js', extraSource=''){
 const entryUrl=new URL('../../src/'+entry,import.meta.url);
 const modules=new Map();
 async function read(url){
  const source=await readFile(url,'utf8');
  return new SourceTextModule(source+(url.href===entryUrl.href?extraSource:''),{
   context,identifier:url.href,
  });
 }
 function moduleAt(url){
  if(!modules.has(url.href))modules.set(url.href,read(url));
  return modules.get(url.href);
 }
 const entryModule=await moduleAt(entryUrl);
 await entryModule.link((specifier, parent)=>moduleAt(new URL(specifier,parent.identifier)));
 await entryModule.evaluate();
 return entryModule.namespace;
}
