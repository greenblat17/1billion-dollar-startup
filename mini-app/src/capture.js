class Capture extends AudioWorkletProcessor {
  constructor(){super();this.buffer=[];this.phase=0;this.muted=false;this.port.onmessage=e=>{this.muted=e.data.muted;this.buffer=[];};}
  process(inputs){
    const input=inputs[0]?.[0];if(!input||this.muted)return true;
    const ratio=sampleRate/24000;
    for(let i=0;i<input.length;i++){
      this.phase+=1;if(this.phase>=ratio){this.phase-=ratio;this.buffer.push(Math.max(-32768,Math.min(32767,Math.round(input[i]*32767))));}
      if(this.buffer.length===480){const data=new Int16Array(this.buffer);this.buffer=[];this.port.postMessage(data.buffer,[data.buffer]);}
    }return true;
  }
}registerProcessor('capture',Capture);
