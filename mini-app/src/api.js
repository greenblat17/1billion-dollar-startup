export const tg=window.Telegram?.WebApp;

const authErrors=new Set(['invalid_telegram_auth','telegram_auth_expired']);
const errorIdPattern=/^(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/i;

export class MiniAppApiError extends Error{
 constructor(message,stage,code,errorId){
  super(message);
  this.diagnostic={stage,code,serverErrorId:errorId};
  this.errorId=errorId;
 }
}

export async function api(action,extra={}){
 let response;
 try{
  response=await fetch('/miniapp/api',{method:'POST',headers:{'Content-Type':'application/json'},
   body:JSON.stringify({action,initData:tg?.initData||'',...extra})});
 }catch{
  throw new MiniAppApiError('connection_lost','transport','request_failed');
 }
 const header=response.headers?.get('X-Error-ID');
 const errorId=errorIdPattern.test(header||'')?header:undefined;
 let data;
 try{
  data=await response.json();
  if(!data||typeof data!=='object'||Array.isArray(data))throw Error();
 }catch{
  throw new MiniAppApiError('connection_lost','parse','invalid_response',errorId);
 }
 if(!response.ok){
  const message=typeof data.detail==='string'?data.detail:'connection_lost';
  if(authErrors.has(message))throw new MiniAppApiError(message,'auth',tg?.initData?'auth_rejected':'auth_missing',errorId);
  throw new MiniAppApiError(message,'response','response_rejected',errorId);
 }
 return data;
}

export function reportDiagnostic({action,stage,code,attemptId,serverErrorId,closeCode}){
 // An auth rejection cannot authorize a second request with the same credential.
 if(!tg?.initData||stage==='auth')return;
 try{
  fetch('/miniapp/diagnostics',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json'},
   body:JSON.stringify({initData:tg.initData,action,stage,code,attemptId,closeCode,
    serverErrorId:errorIdPattern.test(serverErrorId||'')?serverErrorId:undefined})}).catch(()=>{});
 }catch{}
}
