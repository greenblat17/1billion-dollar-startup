import {mkdir, readFile, writeFile, rm, copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

const root=fileURLToPath(new URL('../',import.meta.url));
export async function build(destination=join(root,'dist')) {
  await rm(destination,{recursive:true,force:true});
  await mkdir(join(destination,'miniapp','assets'),{recursive:true});
  const files=['index.html','app.js','capture.js','style.css','avatar.jpg'];
  const hashes={};
  for(const file of files){
    const source=join(root,'src',file);
    hashes[file]=createHash('sha256').update(await readFile(source)).digest('hex');
    await copyFile(source,join(destination,'miniapp',file==='index.html'?'index.html':`assets/${file}`));
  }
  await writeFile(join(destination,'version.json'),JSON.stringify({revision:process.env.GITHUB_SHA||'local',files:hashes})+'\n');
  return destination;
}
if(process.argv[1]===fileURLToPath(import.meta.url)) await build();
