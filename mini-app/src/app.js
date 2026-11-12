const tg=window.Telegram?.WebApp;
tg?.ready();tg?.expand();
const $=id=>document.getElementById(id);
let lessonId=new URLSearchParams(location.search).get('lesson')||tg?.initDataUnsafe?.start_param;
let ws,ctx,mic,worklet,pinger,ticker,muted=false,closing=false,started=0,cursor=0,current,carry=null;
let attemptId=crypto.randomUUID(),diagnosticStage='connect',reported=false;
function reportFailure(code,closeCode){
 if(reported||!tg?.initData)return;reported=true;
 fetch('/miniapp/diagnostics',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json'},
  body:JSON.stringify({initData:tg.initData,attemptId,stage:diagnosticStage,code,closeCode})}).catch(()=>{});
}
let capturedFrames=0,sentFrames=0;
const playing=new Map();
const errors={too_early:'Встреча ещё не началась. Вернитесь в согласованное время.',lesson_expired:'Время этой встречи прошло. Напишите Саше, чтобы договориться заново.',lesson_unavailable:'Эта встреча недоступна для звонка. Проверьте договорённость в Telegram.',active_episode:'Звонок уже открыт на другом устройстве. Завершите его там или дождитесь восстановления связи.',telegram_auth_expired:'Откройте Mini App заново из Telegram.',invalid_telegram_auth:'Откройте встречу из Telegram.',trial_not_configured:'Подключение к урокам пока недоступно.',voice_unavailable:'Не удалось получить голосовой ответ. Попробуйте подключиться снова.',connection_lost:'Связь прервалась. Можно вернуться в тот же урок.'};
function renderMicrophone(){
 $('mute').setAttribute('aria-pressed',String(muted));
 $('mute').setAttribute('aria-label',muted?'Включить микрофон':'Выключить микрофон');
 $('mic-action').textContent=muted?'Включить':'Выключить';
 $('mic-status').dataset.muted=String(muted);
 $('mic-status').textContent=$('mute').disabled?'Подключаем микрофон…':muted?'Микрофон выключен — Саша вас не слышит':'Микрофон включён — можно говорить';
}
function view(id){for(const name of ['call','lobby','error'])$(name).hidden=name!==id;}
function send(data){if(ws?.readyState===WebSocket.OPEN)ws.send(JSON.stringify(data));}
function stopAudio(report=true){
 for(const [id,item] of playing){for(const source of item.sources){try{source.stop();}catch{}}clearTimeout(item.timer);if(report)send({type:'played',id,complete:false,playedMs:Math.max(0,(ctx.currentTime-item.start)*1000)});}
 playing.clear();cursor=ctx?.currentTime||0;current=null;carry=null;
}
function acknowledgePlayback(id,end){
 const item=playing.get(id);if(!item)return;
 const remaining=end-ctx.currentTime;
 if(ctx.state!=='running'||remaining>.005){item.timer=setTimeout(()=>acknowledgePlayback(id,end),Math.max(100,remaining*1000));return;}
 playing.delete(id);send({type:'played',id,complete:true,playedMs:Math.max(0,(end-item.start)*1000)});
}
function cleanup(){clearInterval(pinger);clearInterval(ticker);stopAudio();worklet?.disconnect();mic?.getTracks().forEach(t=>t.stop());ctx?.close().catch(()=>{});ws?.close();}
function leave(){closing=true;send({type:'end'});cleanup();tg?.close();if(!tg?.initData){view('error');$('error-title').textContent='Звонок завершён';$('error-text').textContent='Вернитесь в Telegram.';$('retry').hidden=true;}}
function failure(code,errorId){if(closing)return;reportFailure(code);closing=true;cleanup();view('error');$('error-text').textContent=errors[code]||'Не удалось подключиться. Проверьте сеть и доступ к микрофону.';if(errorId)$('error-text').textContent+=' Код ошибки: '+errorId;$('retry').hidden=false;}
async function api(action,extra={}){const response=await fetch('/miniapp/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,initData:tg?.initData||'',...extra})});const data=await response.json();if(!response.ok)throw Error(data.detail||'connection_lost');return data;}
function label(lesson){return new Intl.DateTimeFormat('ru',{dateStyle:'medium',timeStyle:'short',timeZone:lesson.timezone}).format(new Date(lesson.starts_at))+' · '+lesson.timezone;}
async function list(){
 view('lobby');try{const data=await api('list');$('meetings').replaceChildren();$('schedules').replaceChildren();
 for(const lesson of data.lessons){const card=document.createElement('article');const title=document.createElement('h2');title.textContent=lesson.kind==='trial'?'Пробный урок':'Предварительная встреча';const time=document.createElement('p');time.textContent=label(lesson);const button=document.createElement('button');button.textContent=lesson.launch_mode==='placeholder'?'Подключение пока недоступно':'Открыть урок';button.disabled=lesson.launch_mode!=='executable'||!['scheduled','in_progress'].includes(lesson.status);button.onclick=()=>{lessonId=lesson.id;connect();};card.append(title,time,button);$('meetings').append(card);}
 for(const schedule of data.schedules){if(!schedule.agreement.demo||schedule.agreement.cancelled)continue;const card=document.createElement('article');const text=document.createElement('p');text.textContent='Предварительное расписание: '+schedule.agreement.localTime+' · '+schedule.timezone;const note=document.createElement('small');note.textContent='Демонстрация оплаты. Деньги не списываются, подписка не включается. Будущие звонки пока недоступны.';const button=document.createElement('button');const paid=data.demoReceipts.some(r=>r.schedule_id===schedule.id);button.textContent=paid?'Тестовая оплата подтверждена':'Оплатить · демо';button.disabled=paid;button.onclick=async()=>{button.disabled=true;try{await api('demo_pay',{scheduleId:schedule.id});button.textContent='Тестовая оплата подтверждена';}catch{button.disabled=false;button.textContent='Повторить демо-оплату';}};card.append(text,note,button);$('schedules').append(card);}
 }catch(error){failure(error.message);}}
async function connect(){
 attemptId=crypto.randomUUID();reported=false;diagnosticStage='connect';
 $('mute').disabled=true;
 const joining=performance.now();capturedFrames=0;sentFrames=0;closing=false;muted=false;view('call');$('notice').hidden=false;$('notice').textContent='Подключаемся…';renderMicrophone();
 try{
  if(!tg?.initData)throw Error('invalid_telegram_auth');
  diagnosticStage='microphone';
  mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
  mic.getAudioTracks().forEach(track=>{track.enabled=true;});
  diagnosticStage='audio_context';ctx=new AudioContext();diagnosticStage='worklet';await ctx.audioWorklet.addModule('/miniapp/assets/capture.js');await ctx.resume();
  if(ctx.state!=='running'){
   $('notice').replaceChildren();const button=document.createElement('button');button.textContent='Включить звук';$('notice').append(button);
   await new Promise(resolve=>button.onclick=async()=>{await ctx.resume();resolve();});
  }
  diagnosticStage='socket';ws=new WebSocket(location.origin.replace(/^http/,'ws')+'/miniapp/voice');ws.binaryType='arraybuffer';const connection=ws;
  ws.onopen=()=>send({initData:tg.initData,lessonId,attemptId});
  ws.onclose=event=>{if(ws===connection&&!closing){reportFailure('connection_lost',event.code);failure('connection_lost');}};ws.onerror=()=>{if(ws===connection)failure('connection_lost');};
  ws.onmessage=async event=>{
   if(ws!==connection)return;
   try{
   if(typeof event.data!=='string'){play(event.data);return;}
   const message=JSON.parse(event.data);
   if(message.type==='connected'){
    $('mute').disabled=false;renderMicrophone();
    send({type:'metric',name:'join',milliseconds:performance.now()-joining});$('notice').hidden=true;started=Date.now();ticker=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);$('timer').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');},1000);
    const source=ctx.createMediaStreamSource(mic);worklet=new AudioWorkletNode(ctx,'capture');source.connect(worklet);const silent=ctx.createGain();silent.gain.value=0;worklet.connect(silent).connect(ctx.destination);
    worklet.port.onmessage=e=>{capturedFrames++;if(ws.readyState===WebSocket.OPEN&&!muted){if(ws.bufferedAmount>128000){diagnosticStage='buffer';failure('buffer_overflow');return;}ws.send(e.data);sentFrames++;}};
    pinger=setInterval(()=>{const track=mic?.getAudioTracks()[0];send({type:'ping',audio:{capturedFrames,sentFrames,muted,trackEnabled:track?.enabled===true,trackMuted:track?.muted===true,trackEnded:track?.readyState==='ended',contextRunning:ctx?.state==='running',pageHidden:document.hidden,playing:playing.size}});},5000);
   }else if(message.type==='audio_start'){current=message.id;playing.set(current,{sources:[],start:Math.max(cursor,ctx.currentTime),timer:null});}
   else if(message.type==='audio_end'){const item=playing.get(message.id);if(item){const end=cursor;item.timer=setTimeout(()=>acknowledgePlayback(message.id,end),Math.max(0,(end-ctx.currentTime)*1000));}}
   else if(message.type==='interrupt'){stopAudio();}
   else if(message.type==='error'){failure(message.code,message.errorId);}
   else if(message.type==='finished'){closing=true;cleanup();tg.close();}
   }catch(error){diagnosticStage='playback';reportFailure(error.name);failure('connection_lost');}
  };
 }catch(error){reportFailure(error.name);failure(error.name==='NotAllowedError'?'microphone_denied':error.message);}
}
function play(bytes){
 if(!current||!playing.has(current))return;
 let data=new Uint8Array(bytes);if(carry!==null){const joined=new Uint8Array(data.length+1);joined[0]=carry;joined.set(data,1);data=joined;carry=null;}if(data.length%2){carry=data[data.length-1];data=data.slice(0,-1);}if(!data.length)return;
 const pcm=new DataView(data.buffer,data.byteOffset,data.length);const buffer=ctx.createBuffer(1,data.length/2,24000);const out=buffer.getChannelData(0);for(let i=0;i<out.length;i++)out[i]=pcm.getInt16(i*2,true)/32768;
 const source=ctx.createBufferSource();source.buffer=buffer;source.connect(ctx.destination);cursor=Math.max(cursor,ctx.currentTime+.02);source.start(cursor);cursor+=buffer.duration;playing.get(current).sources.push(source);
}
$('mute').onclick=()=>{muted=!muted;mic?.getAudioTracks().forEach(t=>t.enabled=!muted);worklet?.port.postMessage({muted});send({type:'mute',value:muted});renderMicrophone();};
$('end').onclick=leave;$('return').onclick=()=>tg?.close();$('back').onclick=()=>tg?.close();$('retry').onclick=()=>{closing=false;lessonId?connect():list();};
window.addEventListener('pagehide',()=>{closing=true;cleanup();});
lessonId?connect():list();
