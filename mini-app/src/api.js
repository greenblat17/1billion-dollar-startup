export const tg=window.Telegram?.WebApp;

export async function api(action,extra={}){const response=await fetch('/miniapp/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,initData:tg?.initData||'',...extra})});const data=await response.json();if(!response.ok){
 const error=Error(typeof data.detail==='string'?data.detail:'connection_lost');
 const errorId=response.headers?.get('X-Error-ID');
 if(/^[a-f0-9]{32}$/.test(errorId||''))error.errorId=errorId;
 throw error;
}return data;}

export function reportDiagnostic(attemptId,stage,code,closeCode){
 fetch('/miniapp/diagnostics',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json'},
  body:JSON.stringify({initData:tg.initData,attemptId,stage,code,closeCode})}).catch(()=>{});
}
