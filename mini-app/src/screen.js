export const $=id=>document.getElementById(id);

export function view(id){for(const name of ['call','lobby','error','progress'])$(name).hidden=name!==id;}
export function focusScreen(){
 for(const id of ['call','lobby','error','progress']){
  if(!$(id).hidden){$(id).querySelector?.('h1')?.focus({preventScroll:true});break;}
 }
}
export function renderError(title,message,retry=false,errorId='',help=''){
 view('error');
 $('error-title').textContent=title;$('error-text').textContent=message;
 $('retry').hidden=!retry;$('retry').textContent='Повторить подключение';
 $('error-symbol').hidden=false;
 $('error-help').hidden=!help;$('error-help').open=false;$('error-help-text').textContent=help;
 $('error-diagnostics').hidden=!errorId;$('error-diagnostics').open=false;
 $('error-code').textContent=errorId?'Код ошибки: '+errorId:'';
}
const errors={microphone_denied:'Нет доступа к микрофону. Разрешите микрофон для Telegram в настройках устройства (или для сайта в браузере), затем нажмите «Повторить подключение».',microphone_missing:'Микрофон не найден. Подключите его и повторите подключение.',microphone_busy:'Микрофон недоступен. Закройте приложения, которые используют его, и повторите подключение.',plan_failed:'Не удалось подготовить занятие. Напишите Саше в Telegram, чтобы перенести встречу.',analysis_failed:'Не удалось разобрать прошлый урок. Напишите Саше в Telegram, чтобы перенести встречу.',roadmap_failed:'Не удалось подготовить учебный маршрут. Напишите Саше в Telegram, чтобы перенести встречу.',subscription_required:'Подписка не активна. Напишите Саше в Telegram, чтобы продлить её.',plan_not_ready:'Саша ещё готовит занятие. Попробуйте подключиться чуть позже.',analysis_not_ready:'Саша ещё разбирает прошлый урок. Попробуйте подключиться чуть позже.',not_ready:'Подготовка урока ещё не завершена.',client_update_required:'Обновите Mini App: закройте его и откройте встречу заново из Telegram.',too_early:'Встреча ещё не началась. Вернитесь в согласованное время.',lesson_expired:'Время этой встречи прошло. Напишите Саше, чтобы договориться заново.',lesson_unavailable:'Эта встреча недоступна для звонка. Проверьте договорённость в Telegram.',active_episode:'Звонок уже открыт на другом устройстве. Завершите его там или дождитесь восстановления связи.',telegram_auth_expired:'Откройте Mini App заново из Telegram.',invalid_telegram_auth:'Откройте встречу из Telegram.',trial_not_configured:'Подключение к урокам пока недоступно.',voice_unavailable:'Не удалось получить голосовой ответ. Попробуйте подключиться снова.',connection_lost:'Связь прервалась. Можно вернуться в тот же урок.'};
export function renderFailure(code,errorId){
 const titles={
  microphone_denied:'Нужен доступ к микрофону',microphone_missing:'Микрофон не найден',microphone_busy:'Микрофон недоступен',
  lesson_unavailable:'Урок недоступен',lesson_expired:'Время встречи прошло',
  connection_lost:'Связь прервалась',learning_observation_pending:'Нужна проверка урока',active_episode:'Урок уже открыт',
  invalid_telegram_auth:'Откройте урок из Telegram',telegram_auth_expired:'Нужно открыть урок заново',
  subscription_required:'Подписка не активна',client_update_required:'Нужно обновить приложение',
  plan_not_ready:'Урок ещё готовится',analysis_not_ready:'Саша разбирает прошлый урок',
  not_ready:'Урок ещё готовится',trial_not_configured:'Подключение пока недоступно',
 };
 const descriptions={
  lesson_unavailable:'Проверьте договорённость с Сашей в Telegram.',
  lesson_expired:'Напишите Саше в Telegram, чтобы договориться о новой встрече.',
  connection_lost:'Можно вернуться в тот же урок. Попробуйте подключиться снова.',
  learning_observation_pending:'Ваш ответ сохранён, но учебное наблюдение не записалось. Напишите Саше в Telegram и сообщите код ошибки. Прогресс по этому ответу не подтверждён.',
  microphone_denied:'Разрешите микрофон, чтобы поговорить с Сашей.',
  subscription_required:'Напишите Саше в Telegram, чтобы продлить подписку.',
  invalid_telegram_auth:'Вернитесь в чат с Сашей и нажмите кнопку входа в урок.',
  telegram_auth_expired:'Вернитесь в чат с Сашей и снова откройте урок.',
 };
 const terminal=['invalid_telegram_auth','telegram_auth_expired','lesson_expired','lesson_unavailable','client_update_required','subscription_required','plan_failed','analysis_failed','roadmap_failed','trial_not_configured','learning_observation_pending'];
 renderError(titles[code]||'Не удалось подключиться',
  descriptions[code]||errors[code]||'Проверьте сеть и доступ к микрофону. Затем попробуйте снова.',
  !terminal.includes(code),errorId,
  code==='microphone_denied'?'В настройках устройства разрешите Telegram доступ к микрофону. Если урок открыт в браузере, проверьте разрешения сайта. Затем нажмите «Повторить подключение».':'');
}
export function lessonTiming(lesson){
 const fixed=/^UTC([+-])(\d{2}):(\d{2})$/.exec(lesson.timezone);
 const minutes=fixed?(Number(fixed[2])*60+Number(fixed[3]))*(fixed[1]==='+'?1:-1):0;
 const date=new Date(new Date(lesson.starts_at).getTime()+minutes*60000);
 const timeZone=fixed?'UTC':lesson.timezone;
 return {
  time:new Intl.DateTimeFormat('ru',{timeStyle:'short',timeZone}).format(date),
  date:new Intl.DateTimeFormat('ru',{dateStyle:'medium',timeZone}).format(date)+' · '+lesson.timezone,
 };
}
export function setLobbyMode(mode){
 $('lobby').className='lobby-'+mode;
 $('lobby-avatar').hidden=mode!=='waiting';$('lobby-symbol').hidden=mode!=='calendar';
 $('lobby-appointment').hidden=mode!=='waiting';$('lobby-time').hidden=mode!=='waiting';
}
