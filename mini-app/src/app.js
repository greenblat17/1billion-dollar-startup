import {tg} from './api.js';
import {bindCallControls,startCall} from './call.js';
import {bindProgressControls,showProgress} from './progress.js';
import {showCalendar,showMeetings} from './schedule.js';
import {$,focusScreen} from './screen.js';

tg?.ready();tg?.expand();
const query=new URLSearchParams(location.search);
const startParam=tg?.initDataUnsafe?.start_param;
const lessonId=query.get('lesson')||startParam;

function openLesson(id){
 openScreen=()=>startCall(id);
 return openScreen();
}

let openScreen;
if(query.get('view')==='calendar')openScreen=()=>showCalendar(lessonId);
else if(query.get('view')==='progress'||startParam==='progress')openScreen=showProgress;
else if(lessonId)openScreen=()=>startCall(lessonId);
else openScreen=()=>showMeetings(openLesson);

bindProgressControls();
bindCallControls();
$('return').onclick=()=>tg?.close();
$('back').onclick=()=>tg?.close();
$('retry').onclick=async()=>{await openScreen();focusScreen();};
openScreen();
