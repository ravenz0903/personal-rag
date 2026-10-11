const fs=require('node:fs/promises'),path=require('node:path');
const {cases,system,score}=require('../ai/evaluation.cjs');
async function run(){
  const model=process.argv[2];if(!model)throw Error('Specify an installed evaluation model');
  const cpu=process.argv.includes('--cpu');const results=[];const runtimeVersion=await fetch('http://127.0.0.1:11434/api/version').then(r=>r.json());
  const tags=await fetch('http://127.0.0.1:11434/api/tags').then(r=>r.json());
  const identity=tags.models.find(m=>m.name===model);if(!identity)throw Error('Model is not installed');
  try { for(const test of cases){
    const start=performance.now();let result;
    try{
      const response=await fetch('http://127.0.0.1:11434/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},signal:AbortSignal.timeout(180000),body:JSON.stringify({model,stream:false,...(model.startsWith('qwen3')?{think:false}:{}),format:'json',keep_alive:'1m',options:{temperature:0,seed:42,num_ctx:4096,num_predict:384,...(cpu?{num_gpu:0}:{})},messages:[{role:'system',content:system},{role:'user',content:test.prompt}]})});
      if(!response.ok)throw Error('Runtime HTTP '+response.status);
      const body=await response.json();let proposal;try{proposal=JSON.parse(body.message.content);}catch{throw Error('Invalid JSON output');}
      const allocations=await fetch('http://127.0.0.1:11434/api/ps').then(r=>r.json());const allocation=allocations.models?.find(m=>m.name===model);const failures=score(test,proposal);if(body.done_reason==='length')failures.push('truncated output');
      result={id:test.id,passed:!failures.length,failures,proposal,seconds:(performance.now()-start)/1000,runtimeReportedBytes:allocation?.size,runtimeReportedVramBytes:allocation?.size_vram,loadSeconds:body.load_duration/1e9,outputTokens:body.eval_count,tokensPerSecond:body.eval_duration?body.eval_count/(body.eval_duration/1e9):null};
    }catch(error){result={id:test.id,passed:false,failures:[error.message],seconds:(performance.now()-start)/1000};}
    results.push(result);console.log(test.id+': '+(result.passed?'PASS':'FAIL '+result.failures.join(', '))+' ('+result.seconds.toFixed(1)+'s)');
    const report={model,ollamaVersion:runtimeVersion.version,quantization:identity.details?.quantization_level,digest:identity.digest,bytes:identity.size,runtime:'Ollama exploratory; bundled llama.cpp not yet measured',timing:'first request cold, subsequent requests may reuse model; one-minute idle unload',mode:cpu?'forced CPU':'automatic offload',context:4096,date:new Date().toISOString(),passed:results.filter(r=>r.passed).length,total:cases.length,results};
    const output=path.resolve(__dirname,'../../target/ai-evaluation/'+model.replace(/[^a-z0-9.-]/gi,'_')+(cpu?'-cpu':'-auto')+'.json');await fs.mkdir(path.dirname(output),{recursive:true});await fs.writeFile(output,JSON.stringify(report,null,2)+'\n');
  }} finally { await fetch('http://127.0.0.1:11434/api/generate',{method:'POST',headers:{'Content-Type':'application/json'},signal:AbortSignal.timeout(15000),body:JSON.stringify({model,keep_alive:0})}).catch(()=>{}); }
}
run().catch(error=>{console.error(error.message);process.exitCode=1;});
