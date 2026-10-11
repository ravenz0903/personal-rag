const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),{createHash}=require('node:crypto');
const {verifyAssets}=require('../ai/assets.cjs');
test('offline AI preflight verifies all roles and rejects corrupt, floating, incomplete, or escaping assets',async()=>{
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'folio-ai-assets-'));
  try{
    const files=[];for(const role of ['runtime','chat','embedding','license']){const data=Buffer.from('synthetic '+role);await fs.writeFile(path.join(directory,role),data);files.push({role,path:role,bytes:data.length,sha256:createHash('sha256').update(data).digest('hex'),revision:'fixture-v1'});}
    assert.equal((await verifyAssets(directory,{version:1,files})).length,4);
    await assert.rejects(verifyAssets(directory,{version:1,files:files.slice(0,3)}),/Incomplete/);
    await assert.rejects(verifyAssets(directory,{version:1,files:files.map(f=>({...f,revision:'latest'}))}),/pinned/);
    await assert.rejects(verifyAssets(directory,{version:1,files:[{...files[0],path:'../outside'},...files.slice(1)]}),/Unsafe/);
    await fs.writeFile(path.join(directory,'chat'),Buffer.from('synthetic CHAT'));
    await assert.rejects(verifyAssets(directory,{version:1,files}),/checksum/);
  }finally{await fs.rm(directory,{recursive:true,force:true});}
});
