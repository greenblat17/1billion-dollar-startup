const tg=window.Telegram?.WebApp;
tg?.ready();tg?.expand();
const $=id=>document.getElementById(id);
const progressMode=new URLSearchParams(location.search).get('view')==='progress'||tg?.initDataUnsafe?.start_param==='progress';
const calendarMode=new URLSearchParams(location.search).get('view')==='calendar';
let lessonId=new URLSearchParams(location.search).get('lesson')||tg?.initDataUnsafe?.start_param;
let ws,ctx,mic,worklet,pinger,ticker,muted=false,closing=false,started=0,cursor=0,current,carry=null;
let waitingTimer,connectionGeneration=0;
let attemptId=crypto.randomUUID(),diagnosticStage='connect',reported=false;
function reportFailure(code,closeCode){
 if(reported||!tg?.initData)return;reported=true;
 fetch('/miniapp/diagnostics',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json'},
  body:JSON.stringify({initData:tg.initData,attemptId,stage:diagnosticStage,code,closeCode})}).catch(()=>{});
}
let capturedFrames=0,sentFrames=0,turnCompletedAt=0;
function playedMilliseconds(item){return item.segments.reduce((total,segment)=>total+Math.max(0,Math.min(segment.duration,ctx.currentTime-segment.start))*1000,0);}
const playing=new Map();
const errors={microphone_denied:'Нет доступа к микрофону. Разрешите микрофон для Telegram в настройках устройства (или для сайта в браузере), затем нажмите «Повторить подключение».',microphone_missing:'Микрофон не найден. Подключите его и повторите подключение.',microphone_busy:'Микрофон недоступен. Закройте приложения, которые используют его, и повторите подключение.',plan_failed:'Не удалось подготовить занятие. Напишите Саше в Telegram, чтобы перенести встречу.',analysis_failed:'Не удалось разобрать прошлый урок. Напишите Саше в Telegram, чтобы перенести встречу.',roadmap_failed:'Не удалось подготовить учебный маршрут. Напишите Саше в Telegram, чтобы перенести встречу.',subscription_required:'Подписка не активна. Напишите Саше в Telegram, чтобы продлить её.',plan_not_ready:'Саша ещё готовит занятие. Попробуйте подключиться чуть позже.',analysis_not_ready:'Саша ещё разбирает прошлый урок. Попробуйте подключиться чуть позже.',not_ready:'Подготовка урока ещё не завершена.',client_update_required:'Обновите Mini App: закройте его и откройте встречу заново из Telegram.',too_early:'Встреча ещё не началась. Вернитесь в согласованное время.',lesson_expired:'Время этой встречи прошло. Напишите Саше, чтобы договориться заново.',lesson_unavailable:'Эта встреча недоступна для звонка. Проверьте договорённость в Telegram.',active_episode:'Звонок уже открыт на другом устройстве. Завершите его там или дождитесь восстановления связи.',telegram_auth_expired:'Откройте Mini App заново из Telegram.',invalid_telegram_auth:'Откройте встречу из Telegram.',trial_not_configured:'Подключение к урокам пока недоступно.',voice_unavailable:'Не удалось получить голосовой ответ. Попробуйте подключиться снова.',connection_lost:'Связь прервалась. Можно вернуться в тот же урок.'};
function renderMicrophone(){
 $('mic-status').hidden=$('mute').disabled;
 $('mute').setAttribute('aria-pressed',String(muted));
 $('mute').setAttribute('aria-label',muted?'Включить микрофон':'Выключить микрофон');
 $('mic-action').textContent=muted?'Включить':'Выключить';
 $('mic-status').dataset.muted=String(muted);
 $('mic-status').textContent=$('mute').disabled?'Подключаем микрофон…':muted?'Микрофон выключен — Саша вас не слышит':'Микрофон включён — можно говорить';
}
function view(id){for(const name of ['call','lobby','error','progress'])$(name).hidden=name!==id;}
function focusScreen(){
 for(const id of ['call','lobby','error','progress']){
  if(!$(id).hidden){$(id).querySelector?.('h1')?.focus({preventScroll:true});break;}
 }
}
function send(data){if(ws?.readyState===WebSocket.OPEN)ws.send(JSON.stringify(data));}
function stopAudio(report=true){
 for(const [id,item] of playing){for(const source of item.sources){try{source.stop();}catch{}}clearTimeout(item.timer);if(report)send({type:'played',id,complete:false,playedMs:playedMilliseconds(item)});}
 playing.clear();cursor=ctx?.currentTime||0;current=null;carry=null;
}
function acknowledgePlayback(id,end){
 const item=playing.get(id);if(!item)return;
 const remaining=end-ctx.currentTime;
 if(ctx.state!=='running'||remaining>.005){item.timer=setTimeout(()=>acknowledgePlayback(id,end),Math.max(100,remaining*1000));return;}
 playing.delete(id);send({type:'played',id,complete:true,playedMs:item.duration*1000});
}
function cleanup(){connectionGeneration++;clearTimeout(waitingTimer);clearInterval(pinger);clearInterval(ticker);stopAudio();worklet?.disconnect();mic?.getTracks().forEach(t=>t.stop());ctx?.close().catch(()=>{});ws?.close();}
function renderError(title,message,retry=false,errorId='',help=''){
 view('error');
 $('error-title').textContent=title;$('error-text').textContent=message;
 $('retry').hidden=!retry;$('retry').textContent='Повторить подключение';
 $('error-symbol').hidden=false;
 $('error-help').hidden=!help;$('error-help').open=false;$('error-help-text').textContent=help;
 $('error-diagnostics').hidden=!errorId;$('error-diagnostics').open=false;
 $('error-code').textContent=errorId?'Код ошибки: '+errorId:'';
}
function leave(){closing=true;stopAudio();send({type:'end'});cleanup();tg?.close();if(!tg?.initData){renderError('Звонок завершён','Вернитесь в Telegram.');$('error-symbol').hidden=true;}}
function failure(code,errorId){
 if(closing)return;
 reportFailure(code);closing=true;cleanup();
 const titles={
  microphone_denied:'Нужен доступ к микрофону',microphone_missing:'Микрофон не найден',microphone_busy:'Микрофон недоступен',
  lesson_unavailable:'Урок недоступен',lesson_expired:'Время встречи прошло',
  connection_lost:'Связь прервалась',active_episode:'Урок уже открыт',
  invalid_telegram_auth:'Откройте урок из Telegram',telegram_auth_expired:'Нужно открыть урок заново',
  subscription_required:'Подписка не активна',client_update_required:'Нужно обновить приложение',
  plan_not_ready:'Урок ещё готовится',analysis_not_ready:'Саша разбирает прошлый урок',
  not_ready:'Урок ещё готовится',trial_not_configured:'Подключение пока недоступно',
 };
 const descriptions={
  lesson_unavailable:'Проверьте договорённость с Сашей в Telegram.',
  lesson_expired:'Напишите Саше в Telegram, чтобы договориться о новой встрече.',
  connection_lost:'Можно вернуться в тот же урок. Попробуйте подключиться снова.',
  microphone_denied:'Разрешите микрофон, чтобы поговорить с Сашей.',
  subscription_required:'Напишите Саше в Telegram, чтобы продлить подписку.',
  invalid_telegram_auth:'Вернитесь в чат с Сашей и нажмите кнопку входа в урок.',
  telegram_auth_expired:'Вернитесь в чат с Сашей и снова откройте урок.',
 };
 const terminal=['invalid_telegram_auth','telegram_auth_expired','lesson_expired','lesson_unavailable','client_update_required','subscription_required','plan_failed','analysis_failed','roadmap_failed','trial_not_configured'];
 renderError(titles[code]||'Не удалось подключиться',
  descriptions[code]||errors[code]||'Проверьте сеть и доступ к микрофону. Затем попробуйте снова.',
  !terminal.includes(code),errorId,
  code==='microphone_denied'?'В настройках устройства разрешите Telegram доступ к микрофону. Если урок открыт в браузере, проверьте разрешения сайта. Затем нажмите «Повторить подключение».':'');
}
async function api(action,extra={}){const response=await fetch('/miniapp/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,initData:tg?.initData||'',...extra})});const data=await response.json();if(!response.ok)throw Error(data.detail||'connection_lost');return data;}
function lessonTiming(lesson){
 const fixed=/^UTC([+-])(\d{2}):(\d{2})$/.exec(lesson.timezone);
 const minutes=fixed?(Number(fixed[2])*60+Number(fixed[3]))*(fixed[1]==='+'?1:-1):0;
 const date=new Date(new Date(lesson.starts_at).getTime()+minutes*60000);
 const timeZone=fixed?'UTC':lesson.timezone;
 return {
  time:new Intl.DateTimeFormat('ru',{timeStyle:'short',timeZone}).format(date),
  date:new Intl.DateTimeFormat('ru',{dateStyle:'medium',timeZone}).format(date)+' · '+lesson.timezone,
 };
}
function lessonTimeElements(lesson){
 const timing=lessonTiming(lesson);
 const time=document.createElement('p');time.className='lesson-clock';time.textContent=timing.time;
 const date=document.createElement('p');date.className='lesson-date';date.textContent=timing.date;
 return [time,date];
}
function setLobbyMode(mode){
 $('lobby').className='lobby-'+mode;
 $('lobby-avatar').hidden=mode!=='waiting';$('lobby-symbol').hidden=mode!=='calendar';
 $('lobby-appointment').hidden=mode!=='waiting';$('lobby-time').hidden=mode!=='waiting';
}
function renderWaiting(lesson){
 view('lobby');setLobbyMode('waiting');
 const timing=lessonTiming(lesson);
 $('lobby-title').textContent='Ждём начала занятия';
 $('lobby-time').textContent=timing.time;$('lobby-date').textContent=timing.date;
 $('lobby-note').textContent='Подключимся автоматически, когда начнётся урок. Разрешите доступ к микрофону, если появится запрос.';
 $('meetings').replaceChildren();$('schedules').replaceChildren();
}
async function list(){
 view('lobby');setLobbyMode('meetings');$('meetings').replaceChildren();$('schedules').replaceChildren();$('lobby-title').textContent='Ваши встречи';$('lobby-note').textContent='Загружаем встречи…';try{const data=await api('list');
 $('lobby-note').textContent=data.lessons.length?'Договориться о времени можно с Сашей в Telegram.':'Встреч пока нет. Договоритесь о времени с Сашей в Telegram.';$('meetings').replaceChildren();$('schedules').replaceChildren();
 for(const lesson of data.lessons){const card=document.createElement('article');card.className='meeting-card';const title=document.createElement('h2');title.textContent=lesson.kind==='trial'?'Пробный урок':lesson.launch_mode==='executable'?'Урок английского':'Предварительная встреча';const [time,date]=lessonTimeElements(lesson);const button=document.createElement('button');button.className='lesson-action';button.textContent=lesson.launch_mode==='placeholder'?'Подключение пока недоступно':'Открыть урок';button.disabled=lesson.launch_mode!=='executable'||!['scheduled','in_progress'].includes(lesson.status);
 if(button.disabled)button.textContent=({completed:'Урок завершён',cancelled:'Урок отменён'})[lesson.status]||(lesson.launch_mode==='placeholder'?'Подключение пока недоступно':'Урок недоступен');
 button.onclick=async()=>{lessonId=lesson.id;await connect();focusScreen();};card.append(title,time,date,button);$('meetings').append(card);}
 for(const schedule of data.schedules){if(!schedule.agreement.demo||schedule.agreement.cancelled)continue;const card=document.createElement('article');const text=document.createElement('p');text.textContent=(schedule.agreement.executable?'Согласованное расписание: ':'Предварительное расписание: ')+schedule.agreement.localTime+' · '+schedule.timezone;const note=document.createElement('small');note.textContent='Демонстрация оплаты. Деньги не списываются, подписка не включается.'+(schedule.agreement.executable?'':' Будущие звонки пока недоступны.');const button=document.createElement('button');const paid=data.demoReceipts.some(r=>r.schedule_id===schedule.id);button.textContent=paid?'Тестовая оплата подтверждена':'Оплатить · демо';button.disabled=paid;button.onclick=async()=>{button.disabled=true;try{await api('demo_pay',{scheduleId:schedule.id});button.textContent='Тестовая оплата подтверждена';}catch{button.disabled=false;button.textContent='Повторить демо-оплату';}};card.append(text,note,button);$('schedules').append(card);}
 }catch(error){failure(error.message);}}
async function connect(continueFrom=0){
 clearTimeout(waitingTimer);const generation=++connectionGeneration;
 attemptId=crypto.randomUUID();reported=false;diagnosticStage='connect';
 $('mute').disabled=true;
 const joining=performance.now();capturedFrames=0;sentFrames=0;closing=false;muted=false;view('call');$('notice').hidden=false;$('notice').textContent=continueFrom?'Восстанавливаем соединение…':'Подключаемся…';renderMicrophone();
 try{
  if(!tg?.initData)throw Error('invalid_telegram_auth');
  const status=await api('lesson_status',{lessonId});
  if(generation!==connectionGeneration||closing)return;
  const lesson=status.lesson;
  if(lesson.launch_mode!=='executable'||!['scheduled','in_progress'].includes(lesson.status))throw Error('lesson_unavailable');
  const until=Date.parse(lesson.starts_at)-Date.parse(status.serverNow);
  if(!Number.isFinite(until))throw Error('connection_lost');
  if(until>0){
   renderWaiting(lesson);
   waitingTimer=setTimeout(()=>{if(generation===connectionGeneration&&!closing)return connect(continueFrom);},Math.min(until,15000));return;
  }
  diagnosticStage='microphone';
  if(!navigator.mediaDevices?.getUserMedia)throw Error('microphone_missing');
  const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
  if(generation!==connectionGeneration||closing){stream.getTracks().forEach(t=>t.stop());return;}
  mic=stream;
  mic.getAudioTracks().forEach(track=>{track.enabled=true;});
  diagnosticStage='audio_context';ctx=new AudioContext();diagnosticStage='worklet';await ctx.audioWorklet.addModule('/miniapp/assets/capture.js');await Promise.race([ctx.resume(),new Promise(resolve=>setTimeout(resolve,700))]);
  if(ctx.state!=='running'){
   $('notice').replaceChildren();const button=document.createElement('button');button.textContent='Включить звук';$('notice').append(button);
   await new Promise((resolve,reject)=>button.onclick=async()=>{try{await ctx.resume();if(ctx.state==='running')resolve();}catch(error){reject(error);}});
  }
  if(generation!==connectionGeneration||closing)return;
  diagnosticStage='socket';ws=new WebSocket(location.origin.replace(/^http/,'ws')+'/miniapp/voice');ws.binaryType='arraybuffer';const connection=ws;
  ws.onopen=()=>send({initData:tg.initData,lessonId,attemptId,protocol:'realtime-pcm-v1'});
  ws.onclose=event=>{if(ws===connection&&!closing){reportFailure('connection_lost',event.code);failure('connection_lost');}};ws.onerror=()=>{if(ws===connection)failure('connection_lost');};
  ws.onmessage=async event=>{
   if(ws!==connection)return;
   try{
   if(typeof event.data!=='string'){play(event.data);return;}
   const message=JSON.parse(event.data);
   if(message.type==='connected'){
    if(message.protocol!=='realtime-pcm-v1'||message.inputSampleRate!==24000){failure('client_update_required');return;}
    $('mute').disabled=false;renderMicrophone();
    send({type:'metric',name:'join',milliseconds:performance.now()-joining});$('notice').hidden=true;started=continueFrom||Date.now();ticker=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);$('timer').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');},1000);
    const source=ctx.createMediaStreamSource(mic);worklet=new AudioWorkletNode(ctx,'capture');source.connect(worklet);const silent=ctx.createGain();silent.gain.value=0;worklet.connect(silent).connect(ctx.destination);
    worklet.port.onmessage=e=>{capturedFrames++;if(ws.readyState===WebSocket.OPEN&&!muted){if(ws.bufferedAmount>128000){diagnosticStage='buffer';failure('buffer_overflow');return;}ws.send(e.data);sentFrames++;}};
    pinger=setInterval(()=>{const track=mic?.getAudioTracks()[0];send({type:'ping',audio:{capturedFrames,sentFrames,muted,trackEnabled:track?.enabled===true,trackMuted:track?.muted===true,trackEnded:track?.readyState==='ended',contextRunning:ctx?.state==='running',pageHidden:document.hidden,playing:playing.size}});},5000);
   }else if(message.type==='audio_start'){current=message.id;playing.set(current,{sources:[],segments:[],start:Math.max(cursor,ctx.currentTime+.02),duration:0,timer:null,first:true});}
   else if(message.type==='audio_end'){const item=playing.get(message.id);if(item){const end=cursor;item.timer=setTimeout(()=>acknowledgePlayback(message.id,end),Math.max(0,(end-ctx.currentTime)*1000));}}
   else if(message.type==='user_turn_complete'){turnCompletedAt=performance.now();}
   else if(message.type==='interrupt'){stopAudio();}
   else if(message.type==='error'){failure(message.code,message.errorId);}
   else if(message.type==='reconnect'){const callStarted=started;closing=true;cleanup();await connect(callStarted);}
   else if(message.type==='ended'&&message.reason==='memory_forgotten'){closing=true;cleanup();renderError('Память обновлена','Сведения удалены. Напишите Саше в Telegram, чтобы продолжить.');$('error-symbol').hidden=true;}
   else if(message.type==='finished'){closing=true;cleanup();tg.close();}
   }catch(error){diagnosticStage='playback';reportFailure(error.name);failure('connection_lost');}
  };
 }catch(error){if(generation!==connectionGeneration||closing)return;reportFailure(error.name);failure(({NotAllowedError:'microphone_denied',SecurityError:'microphone_denied',NotFoundError:'microphone_missing',NotReadableError:'microphone_busy'})[error.name]||error.message);}
}
function play(bytes){
 if(!current||!playing.has(current))return;
 let data=new Uint8Array(bytes);if(carry!==null){const joined=new Uint8Array(data.length+1);joined[0]=carry;joined.set(data,1);data=joined;carry=null;}if(data.length%2){carry=data[data.length-1];data=data.slice(0,-1);}if(!data.length)return;
 const pcm=new DataView(data.buffer,data.byteOffset,data.length);const buffer=ctx.createBuffer(1,data.length/2,24000);const out=buffer.getChannelData(0);for(let i=0;i<out.length;i++)out[i]=pcm.getInt16(i*2,true)/32768;
 const source=ctx.createBufferSource();source.buffer=buffer;source.connect(ctx.destination);cursor=Math.max(cursor,ctx.currentTime+.02);const item=playing.get(current);if(item.first){item.start=cursor;item.first=false;if(turnCompletedAt){send({type:'metric',name:'first_audio',milliseconds:Math.max(0,performance.now()-turnCompletedAt+(cursor-ctx.currentTime)*1000)});turnCompletedAt=0;}}item.segments.push({start:cursor,duration:buffer.duration});source.start(cursor);cursor+=buffer.duration;item.duration+=buffer.duration;item.sources.push(source);
}

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
async function progress(){
 view('progress');renderProgressState('loading');$('progress-footer').hidden=true;$('progress-content').hidden=true;$('progress-refresh').disabled=true;
 try{renderProgress(await api('progress'));}
 catch(error){
  const authExpired=['invalid_telegram_auth','telegram_auth_expired'].includes(error.message);
  renderProgressState(authExpired?'auth':'network');
 }
 finally{$('progress-refresh').disabled=false;}
}
$('progress-refresh').onclick=async()=>{await progress();focusScreen();};
$('progress-back').onclick=()=>tg?.close();

$('mute').onclick=()=>{muted=!muted;mic?.getAudioTracks().forEach(t=>t.enabled=!muted);worklet?.port.postMessage({muted});send({type:'mute',value:muted});renderMicrophone();};
$('end').onclick=leave;$('return').onclick=()=>tg?.close();$('back').onclick=()=>tg?.close();$('retry').onclick=async()=>{closing=false;await (calendarMode?calendar():lessonId?connect():list());focusScreen();};
window.addEventListener('pagehide',()=>{closing=true;cleanup();});

async function calendar(){
 view('lobby');setLobbyMode('calendar');$('lobby-title').textContent='Ваше занятие';$('lobby-note').textContent='Сохраните время в удобном календаре.';$('meetings').replaceChildren();$('schedules').replaceChildren();
 try{
  const data=await api('calendar',{lessonId});
  const card=document.createElement('article');
  card.className='calendar-card';
  const title=document.createElement('h2');title.textContent=data.lesson.status==='cancelled'?'Занятие отменено':'Встреча с Сашей';
  const [time,date]=lessonTimeElements(data.lesson);
  const duration=document.createElement('p');duration.className='lesson-duration';duration.textContent=data.lesson.duration_minutes+' минут';
  const note=document.createElement('p');note.textContent=data.lesson.status==='cancelled'?'Удалите занятие из своего календаря.':'Необязательно. После переноса или отмены обновите событие в своём календаре. Саша напомнит о занятии в Telegram.';
  card.append(title,time,date,duration);
  if(data.googleUrl){const google=document.createElement('button');google.textContent='Google Calendar';google.onclick=()=>tg?.openLink?tg.openLink(data.googleUrl):window.open(data.googleUrl,'_blank','noopener');card.append(google);}
  const download=document.createElement('button');download.className=data.googleUrl?'calendar-secondary':'';download.textContent='Apple / другой календарь · .ics';
  download.onclick=async()=>{
   const file=new File([data.ics],data.filename,{type:'text/calendar;charset=utf-8'});
   if(navigator.canShare?.({files:[file]})){try{await navigator.share({files:[file],title:'Занятие с Сашей'});}catch(error){if(error.name!=='AbortError')downloadCalendar(file);} }
   else downloadCalendar(file);
  };
  card.append(download,note);$('meetings').append(card);
 }catch(error){failure(error.message);}
}
function downloadCalendar(file){const url=URL.createObjectURL(file);const link=document.createElement('a');link.href=url;link.download=file.name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}

calendarMode?calendar():progressMode?progress():lessonId?connect():list();
