import {api,MiniAppApiError,reportDiagnostic,tg} from './api.js';
import {$,lessonTiming,renderError,renderFailure,setLobbyMode,view} from './screen.js';

let lessonId;
let ws,ctx,mic,worklet,pinger,ticker,muted=false,closing=false,started=0,cursor=0,current,carry=null;
let waitingTimer,connectionGeneration=0;
let attemptId;
const microphoneErrors=new Map([
 ['NotAllowedError','microphone_denied'],['SecurityError','microphone_denied'],
 ['NotFoundError','microphone_missing'],['NotReadableError','microphone_busy'],
]);
function reportVoice(diagnostic){
 reportDiagnostic({action:'voice',attemptId,...diagnostic});
}
let capturedFrames=0,sentFrames=0,turnCompletedAt=0;
function playedMilliseconds(item){return item.segments.reduce((total,segment)=>total+Math.max(0,Math.min(segment.duration,ctx.currentTime-segment.start))*1000,0);}
const playing=new Map();
function renderMicrophone(){
 $('mic-status').hidden=$('mute').disabled;
 $('mute').setAttribute('aria-pressed',String(muted));
 $('mute').setAttribute('aria-label',muted?'Включить микрофон':'Выключить микрофон');
 $('mic-action').textContent=muted?'Включить':'Выключить';
 $('mic-status').dataset.muted=String(muted);
 $('mic-status').textContent=$('mute').disabled?'Подключаем микрофон…':muted?'Микрофон выключен — Саша вас не слышит':'Микрофон включён — можно говорить';
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
function cleanup(){connectionGeneration++;clearTimeout(waitingTimer);clearInterval(pinger);clearInterval(ticker);stopAudio();worklet?.disconnect();mic?.getTracks().forEach(t=>t.stop());ctx?.close().catch(()=>{});const socket=ws;ws=undefined;socket?.close();}
function leave(){closing=true;stopAudio();send({type:'end'});cleanup();tg?.close();if(!tg?.initData){renderError('Звонок завершён','Вернитесь в Telegram.');$('error-symbol').hidden=true;}}
function failure(uiCode,diagnostic){
 if(closing)return;
 closing=true;reportVoice(diagnostic);cleanup();
 renderFailure(uiCode,diagnostic.serverErrorId);
}
function renderWaiting(lesson){
 view('lobby');setLobbyMode('waiting');
 const timing=lessonTiming(lesson);
 $('lobby-title').textContent='Ждём начала занятия';
 $('lobby-time').textContent=timing.time;$('lobby-date').textContent=timing.date+(lesson.kind==='trial'?' · '+lesson.duration_minutes+' минут':'');
 $('lobby-note').textContent='Подключимся автоматически, когда начнётся урок. Разрешите доступ к микрофону, если появится запрос.';
 $('meetings').replaceChildren();$('schedules').replaceChildren();
}
async function connect(continueFrom=0){
 clearTimeout(waitingTimer);const generation=++connectionGeneration;
 attemptId=crypto.randomUUID();
 let setupStage='parse';
 $('mute').disabled=true;
 const joining=performance.now();capturedFrames=0;sentFrames=0;closing=false;muted=false;view('call');$('notice').hidden=false;$('notice').textContent=continueFrom?'Восстанавливаем соединение…':'Подключаемся…';renderMicrophone();
 try{
  if(!tg?.initData){
   failure('invalid_telegram_auth',{stage:'auth',code:'auth_missing'});
   return;
  }
  const status=await api('lesson_status',{lessonId});
  if(generation!==connectionGeneration||closing)return;
  const lesson=status.lesson;
  if(!lesson||typeof lesson.launch_mode!=='string'||typeof lesson.status!=='string'){
   failure('connection_lost',{stage:'parse',code:'invalid_response'});
   return;
  }
  if(lesson.launch_mode!=='executable'||!['scheduled','in_progress'].includes(lesson.status)){
   failure('lesson_unavailable',{stage:'connect',code:'lesson_unavailable'});
   return;
  }
  const until=Date.parse(lesson.starts_at)-Date.parse(status.serverNow);
  if(!Number.isFinite(until)){
   failure('connection_lost',{stage:'connect',code:'invalid_lesson_time'});
   return;
  }
  if(until>0){
   setupStage='render';
   renderWaiting(lesson);
   waitingTimer=setTimeout(()=>{if(generation===connectionGeneration&&!closing)return connect(continueFrom);},Math.min(until,15000));return;
  }
  setupStage='microphone';
  if(!navigator.mediaDevices?.getUserMedia){
   failure('microphone_missing',{stage:'microphone',code:'microphone_missing'});
   return;
  }
  const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
  if(generation!==connectionGeneration||closing){stream.getTracks().forEach(t=>t.stop());return;}
  mic=stream;
  mic.getAudioTracks().forEach(track=>{track.enabled=true;});
  setupStage='audio_context';ctx=new AudioContext();
  setupStage='worklet';await ctx.audioWorklet.addModule('/miniapp/assets/capture.js');
  setupStage='audio_context';await Promise.race([ctx.resume(),new Promise(resolve=>setTimeout(resolve,700))]);
  if(ctx.state!=='running'){
   reportVoice({stage:'audio_context',code:'audio_activation_required'});
   $('notice').replaceChildren();const button=document.createElement('button');button.textContent='Включить звук';$('notice').append(button);
   await new Promise((resolve,reject)=>button.onclick=async()=>{try{await ctx.resume();if(ctx.state==='running')resolve();}catch(error){reject(error);}});
  }
  if(generation!==connectionGeneration||closing)return;
  setupStage='socket';ws=new WebSocket(location.origin.replace(/^http/,'ws')+'/miniapp/voice');ws.binaryType='arraybuffer';const connection=ws;
  ws.onopen=()=>{
   if(ws!==connection||closing)return;
   try{send({initData:tg.initData,lessonId,attemptId,protocol:'realtime-pcm-v1'});}
   catch{failure('connection_lost',{stage:'socket',code:'connection_lost'});}
  };
  ws.onclose=event=>{if(ws===connection)failure('connection_lost',{stage:'socket',code:'connection_lost',closeCode:event.code});};
  ws.onerror=()=>{if(ws===connection)failure('connection_lost',{stage:'socket',code:'connection_lost'});};
  ws.onmessage=async event=>{
   if(ws!==connection||closing)return;
   if(typeof event.data!=='string'){
    try{play(event.data);}
    catch{failure('connection_lost',{stage:'playback',code:'playback_failed'});}
    return;
   }
   let message;
   try{
    message=JSON.parse(event.data);
    if(!message||typeof message.type!=='string')throw Error();
   }catch{
    failure('connection_lost',{stage:'protocol',code:'invalid_message'});
    return;
   }
   let messageStage='protocol';
   try{
   if(message.type==='connected'){
    if(message.protocol!=='realtime-pcm-v1'||message.inputSampleRate!==24000){failure('client_update_required',{stage:'protocol',code:'client_update_required'});return;}
    messageStage='render';
    $('mute').disabled=false;renderMicrophone();$('notice').hidden=true;
    messageStage='socket';
    send({type:'metric',name:'join',milliseconds:performance.now()-joining});
    messageStage='protocol';started=message.trialExecution?Date.now()-(message.trialExecution.connectedMs??message.trialExecution.connectedSeconds*1000):(continueFrom||Date.now());ticker=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);$('timer').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');},1000);
    messageStage='worklet';
    const source=ctx.createMediaStreamSource(mic);worklet=new AudioWorkletNode(ctx,'capture');source.connect(worklet);const silent=ctx.createGain();silent.gain.value=0;worklet.connect(silent).connect(ctx.destination);
    worklet.port.onmessage=e=>{
     if(ws!==connection||closing)return;
     capturedFrames++;
     if(connection.readyState!==WebSocket.OPEN||muted)return;
     if(connection.bufferedAmount>128000){
      failure('buffer_overflow',{stage:'buffer',code:'buffer_overflow'});
      return;
     }
     try{connection.send(e.data);sentFrames++;}
     catch{failure('connection_lost',{stage:'socket',code:'connection_lost'});}
    };
    pinger=setInterval(()=>{const track=mic?.getAudioTracks()[0];send({type:'ping',audio:{capturedFrames,sentFrames,muted,trackEnabled:track?.enabled===true,trackMuted:track?.muted===true,trackEnded:track?.readyState==='ended',contextRunning:ctx?.state==='running',pageHidden:document.hidden,playing:playing.size}});},5000);
   }else if(message.type==='trial_clock'){started=Date.now()-(message.trialExecution.connectedMs??message.trialExecution.connectedSeconds*1000);
   }else if(message.type==='audio_start'){current=message.id;playing.set(current,{sources:[],segments:[],start:Math.max(cursor,ctx.currentTime+.02),duration:0,timer:null,first:true});}
   else if(message.type==='audio_end'){const item=playing.get(message.id);if(item){const end=cursor;item.timer=setTimeout(()=>acknowledgePlayback(message.id,end),Math.max(0,(end-ctx.currentTime)*1000));}}
   else if(message.type==='user_turn_complete'){turnCompletedAt=performance.now();}
   else if(message.type==='interrupt'){stopAudio();}
   else if(message.type==='error'){failure(message.code,{stage:'protocol',code:'server_error_observed',serverErrorId:message.errorId});}
   else if(message.type==='reconnect'){reportVoice({stage:'socket',code:'reconnecting'});const callStarted=started;closing=true;cleanup();await connect(callStarted);}
   else if(message.type==='ended'&&message.reason==='memory_forgotten'){closing=true;cleanup();renderError('Память обновлена','Сведения удалены. Напишите Саше в Telegram, чтобы продолжить.');$('error-symbol').hidden=true;}
   else if(message.type==='finished'){closing=true;cleanup();tg.close();}
   }catch{
    const code={protocol:'invalid_message',worklet:'worklet_failed',render:'render_failed',socket:'connection_lost'}[messageStage];
    failure('connection_lost',{stage:messageStage,code});
   }
  };
 }catch(error){
  if(generation!==connectionGeneration||closing)return;
  if(error instanceof MiniAppApiError){
   failure(error.message,error.diagnostic);
   return;
  }
  const micCode=microphoneErrors.get(error?.name);
  const setupCode={
   parse:'invalid_response',render:'render_failed',microphone:micCode||'microphone_failed',
   audio_context:'audio_context_failed',worklet:'worklet_failed',socket:'connection_lost',
  }[setupStage];
  failure(setupStage==='microphone'&&micCode?micCode:'connection_lost',{stage:setupStage,code:setupCode});
 }
}
function play(bytes){
 if(!current||!playing.has(current))return;
 let data=new Uint8Array(bytes);if(carry!==null){const joined=new Uint8Array(data.length+1);joined[0]=carry;joined.set(data,1);data=joined;carry=null;}if(data.length%2){carry=data[data.length-1];data=data.slice(0,-1);}if(!data.length)return;
 const pcm=new DataView(data.buffer,data.byteOffset,data.length);const buffer=ctx.createBuffer(1,data.length/2,24000);const out=buffer.getChannelData(0);for(let i=0;i<out.length;i++)out[i]=pcm.getInt16(i*2,true)/32768;
 const source=ctx.createBufferSource();source.buffer=buffer;source.connect(ctx.destination);cursor=Math.max(cursor,ctx.currentTime+.02);const item=playing.get(current);if(item.first){item.start=cursor;item.first=false;if(turnCompletedAt){send({type:'metric',name:'first_audio',milliseconds:Math.max(0,performance.now()-turnCompletedAt+(cursor-ctx.currentTime)*1000)});turnCompletedAt=0;}}item.segments.push({start:cursor,duration:buffer.duration});source.start(cursor);cursor+=buffer.duration;item.duration+=buffer.duration;item.sources.push(source);
}

export function startCall(id){
 lessonId=id;
 return connect();
}

export function bindCallControls(){
 $('mute').onclick=()=>{muted=!muted;mic?.getAudioTracks().forEach(t=>t.enabled=!muted);worklet?.port.postMessage({muted});send({type:'mute',value:muted});renderMicrophone();};
 $('end').onclick=leave;
 window.addEventListener('pagehide',()=>{closing=true;cleanup();});
}
