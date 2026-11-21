#!/usr/bin/env python3
"""Standalone target-topic map. No graph edges or curriculum sequence."""
import argparse
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'ai_docs/product/2026-11-15-learning-catalog-map.html'

def render():
    data = json.loads((ROOT / 'learning-catalog/v1/catalog.json').read_text())
    payload = json.dumps(data, ensure_ascii=False).replace('<', '\\u003c')
    return '''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Speaky · Topic Map</title>
<style>body{margin:0;font:14px var(--font-sans,system-ui);color:var(--foreground,#ddd)}h2{margin:12px 0}p{line-height:1.5}nav{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}button,input{padding:9px;font:inherit;border:1px solid var(--border,#777);border-radius:6px;color:inherit;background:var(--card,#252a32)}button{cursor:pointer}button[aria-pressed=true]{border-color:#70aafa;color:#70aafa}input{flex:1;min-width:150px}.skills{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}article{padding:16px;background:var(--card,#252a32);border-radius:8px}article h3{margin-top:0}details{border-top:1px solid var(--border,#666);padding:12px 0}summary{cursor:pointer;line-height:1.5}small{color:var(--muted-foreground,#aaa);overflow-wrap:anywhere}#status{margin:12px 0}</style>
<h2>Topic Map · целевые способности</h2><p>163 темы · 4 навыка · A1–C1. Содержание ожидает приёмки. Выберите уровень, затем раскройте тему.</p><nav id="levels" aria-label="Уровень CEFR"></nav><nav><input id="search" placeholder="Найти тему или ID" aria-label="Поиск темы"></nav><div id="status" aria-live="polite"></div><div id="content"></div><p>Reading и Writing сохранены в Skill Map, наполнение Topics — вне MVP. Зависимости и порядок обучения определит Roadmap Generation.</p>
<script type="application/json" id="data">'''+payload+'''</script><script>
const D=JSON.parse(document.getElementById('data').textContent),levels=['A1','A2','B1','B2','C1'];let current='A1';const $=id=>document.getElementById(id);
function elem(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n}
function draw(){for(const b of $('levels').children)b.setAttribute('aria-pressed',b.textContent===current);const q=$('search').value.trim().toLowerCase();const topics=D.topics.filter(t=>(current==='Все'||t.cefr===current)&&(!q||(t.title+' '+t.id+' '+t.objective).toLowerCase().includes(q)));$('status').textContent=`Показано тем: ${topics.length}`;$('content').replaceChildren();for(const level of levels){const group=topics.filter(t=>t.cefr===level);if(!group.length)continue;$('content').append(elem('h2',level));const grid=elem('div');grid.className='skills';for(const skill of D.skills.filter(s=>s.mvp)){const ts=group.filter(t=>t.skillId===skill.id);if(!ts.length)continue;const a=elem('article');a.append(elem('h3',skill.title+' · '+ts.length));for(const t of ts){const detail=elem('details');detail.append(elem('summary',t.title),elem('p',t.objective),elem('p',t.scope),elem('small',t.id));a.append(detail)}grid.append(a)}$('content').append(grid)}}
for(const level of [...levels,'Все']){const b=elem('button',level);b.onclick=()=>{current=level;draw()};$('levels').append(b)}$('search').oninput=draw;draw();</script></html>'''

if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args();result=render()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text()!=result:raise SystemExit('Topic map is stale')
        print('Topic map matches source')
    else:OUTPUT.write_text(result);print(OUTPUT.relative_to(ROOT))
