import {api,tg} from './api.js';
import {$,focusScreen,view} from './screen.js';

const progressStates={
 loading:{title:'Загружаем маршрут',message:'Получаем ваш прогресс.',icon:'clock'},
 no_roadmap:{title:'Маршрут ещё не составлен',message:'Обсудите с Сашей цель и следующий шаг в Telegram.',icon:'route',back:true},
 preparing:{title:'Саша готовит маршрут',message:'Обновите экран чуть позже.',icon:'clock'},
 insufficient_evidence:{title:'Нужно больше практики',message:'Пока недостаточно данных для маршрута. Саша продолжит знакомиться с вашей речью на занятиях.',icon:'route',back:true},
 error:{title:'Маршрут пока недоступен',message:'Не удалось подготовить маршрут. Напишите Саше в Telegram.',icon:'alert',back:true},
 network:{title:'Не удалось загрузить прогресс',message:'Проверьте сеть и попробуйте ещё раз.',icon:'alert'},
 auth:{title:'Откройте прогресс из Telegram',message:'Вернитесь в чат с Сашей и откройте «Мой прогресс» заново.',icon:'alert',back:true},
};
function renderProgressState(status){
 const state=progressStates[status];
 $('progress-shell').className=state?'progress-shell support-state':'progress-shell';
 $('progress-symbol').hidden=!state;$('progress-symbol').className='support-symbol is-'+(state?.icon||'route');
 $('progress-title').textContent=state?.title||'Мой прогресс';
 $('progress-state').textContent=state?.message||'';
 $('progress-refresh').hidden=status==='auth';$('progress-refresh').className=state&&!state.back?'primary-action':'';
 $('progress-refresh').textContent=status==='network'?'Повторить загрузку':'Обновить';
 $('progress-back').className=state?.back?'primary-action':'';
 const buttons=state?.back?[$('progress-back'),$('progress-refresh')]:[$('progress-refresh'),$('progress-back')];
 if($('progress-actions').children[0]!==buttons[0])$('progress-actions').replaceChildren(...buttons);
}
function renderProgress(data){
 $('progress-content').hidden=data.status!=='ready';
 $('progress-footer').hidden=data.status!=='ready';
 renderProgressState(data.status==='ready'?'ready':progressStates[data.status]?data.status:'error');
 if(data.status!=='ready')return;
 const goal=data.goal?.value||data.goal?.text;
 $('progress-goal').textContent=typeof goal==='string'?goal:'Обсудите вашу цель с Сашей';
 const allDone=data.steps.length>0&&data.steps.every(step=>step.status==='completed');
 $('progress-note').textContent=allDone?'Все этапы этого маршрута пройдены. Обсудите следующий шаг с Сашей.':data.evidenceStatus==='insufficient_evidence'?'Маршрут готов. Для подтверждения освоенных навыков пока недостаточно данных.':'';
 $('progress-note').hidden=!$('progress-note').textContent;
 $('progress-steps').replaceChildren();
 const labels={completed:'Пройдено',current:'Сейчас в фокусе',upcoming:'Впереди',needs_review:'Нужно проверить ещё раз'};
 for(const step of data.steps){
  const row=document.createElement('li');row.className='roadmap-step '+step.status;
  if(step.status==='current')row.setAttribute('aria-current','step');
  const marker=document.createElement('span');marker.className='step-marker';marker.textContent=step.status==='completed'?'✓':'•';marker.setAttribute('aria-hidden','true');
  const body=document.createElement('div');const label=document.createElement('p');label.className='step-label';label.textContent=labels[step.status]||'Впереди';
  const title=document.createElement('h2');title.textContent=step.outcome;
  const detail=document.createElement('p');detail.className='step-detail';detail.textContent=step.status==='needs_review'?'Ранее этот этап был пройден. После уточнения прежних наблюдений Саша проверит навык в разговоре.':step.tactic;
  body.append(label,title,detail);row.append(marker,body);$('progress-steps').append(row);
 }
}
export async function showProgress(){
 view('progress');renderProgressState('loading');$('progress-footer').hidden=true;$('progress-content').hidden=true;$('progress-refresh').disabled=true;
 try{renderProgress(await api('progress'));}
 catch(error){
  const authExpired=['invalid_telegram_auth','telegram_auth_expired'].includes(error.message);
  renderProgressState(authExpired?'auth':'network');
 }
 finally{$('progress-refresh').disabled=false;}
}
export function bindProgressControls(){
 $('progress-refresh').onclick=async()=>{await showProgress();focusScreen();};
 $('progress-back').onclick=()=>tg?.close();
}
