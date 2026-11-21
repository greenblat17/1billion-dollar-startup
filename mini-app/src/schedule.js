import {api,reportDiagnostic,tg} from './api.js';
import {$,focusScreen,lessonTiming,renderFailure,setLobbyMode,view} from './screen.js';

let reported=false;
function showScheduleFailure(error){
 if(!reported&&tg?.initData){
  reported=true;
  reportDiagnostic(crypto.randomUUID(),'connect',error.message);
 }
 renderFailure(error.message,error.errorId);
}

function lessonTimeElements(lesson){
 const timing=lessonTiming(lesson);
 const time=document.createElement('p');time.className='lesson-clock';time.textContent=timing.time;
 const date=document.createElement('p');date.className='lesson-date';date.textContent=timing.date;
 return [time,date];
}
export async function showMeetings(openLesson){
 view('lobby');setLobbyMode('meetings');$('meetings').replaceChildren();$('schedules').replaceChildren();$('lobby-title').textContent='Ваши встречи';$('lobby-note').textContent='Загружаем встречи…';try{const data=await api('list');
 $('lobby-note').textContent=data.lessons.length?'Договориться о времени можно с Сашей в Telegram.':'Встреч пока нет. Договоритесь о времени с Сашей в Telegram.';$('meetings').replaceChildren();$('schedules').replaceChildren();
 for(const lesson of data.lessons){const card=document.createElement('article');card.className='meeting-card';const title=document.createElement('h2');title.textContent=lesson.kind==='trial'?'Пробный урок':lesson.launch_mode==='executable'?'Урок английского':'Предварительная встреча';const [time,date]=lessonTimeElements(lesson);const button=document.createElement('button');button.className='lesson-action';button.textContent=lesson.launch_mode==='placeholder'?'Подключение пока недоступно':'Открыть урок';button.disabled=lesson.launch_mode!=='executable'||!['scheduled','in_progress'].includes(lesson.status);
 if(button.disabled)button.textContent=({completed:'Урок завершён',cancelled:'Урок отменён'})[lesson.status]||(lesson.launch_mode==='placeholder'?'Подключение пока недоступно':'Урок недоступен');
 button.onclick=async()=>{await openLesson(lesson.id);focusScreen();};card.append(title,time,date,button);$('meetings').append(card);}
 for(const schedule of data.schedules){if(!schedule.agreement.demo||schedule.agreement.cancelled)continue;const card=document.createElement('article');const text=document.createElement('p');text.textContent=(schedule.agreement.executable?'Согласованное расписание: ':'Предварительное расписание: ')+schedule.agreement.localTime+' · '+schedule.timezone;const note=document.createElement('small');note.textContent='Демонстрация оплаты. Деньги не списываются, подписка не включается.'+(schedule.agreement.executable?'':' Будущие звонки пока недоступны.');const button=document.createElement('button');const paid=data.demoReceipts.some(r=>r.schedule_id===schedule.id);button.textContent=paid?'Тестовая оплата подтверждена':'Оплатить · демо';button.disabled=paid;button.onclick=async()=>{button.disabled=true;try{await api('demo_pay',{scheduleId:schedule.id});button.textContent='Тестовая оплата подтверждена';}catch{button.disabled=false;button.textContent='Повторить демо-оплату';}};card.append(text,note,button);$('schedules').append(card);}
 }catch(error){showScheduleFailure(error);}}
export async function showCalendar(lessonId){
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
 }catch(error){showScheduleFailure(error);}
}
function downloadCalendar(file){const url=URL.createObjectURL(file);const link=document.createElement('a');link.href=url;link.download=file.name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}
