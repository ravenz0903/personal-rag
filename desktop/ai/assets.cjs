// Build-time/offline preflight. Does not download files or start inference.
const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
async function verifyAssets(root,manifest){
  if(manifest?.version!==1||!Array.isArray(manifest.files)||!manifest.files.length)throw Error('Invalid AI asset manifest');
  const base=await fs.promises.realpath(root),seen=new Set(),verified=[];
  for(const file of manifest.files){
    if(!['runtime','chat','embedding','license'].includes(file.role)||typeof file.path!=='string'||!file.path||file.path.includes('\\')||file.path.split('/').some(p=>!p||p==='.'||p==='..'||p.includes(':'))||path.isAbsolute(file.path)||seen.has(file.path.toLowerCase()))throw Error('Unsafe AI asset path or role');
    seen.add(file.path.toLowerCase());
    if(!Number.isSafeInteger(file.bytes)||file.bytes<=0||!/^[a-f0-9]{64}$/.test(file.sha256)||typeof file.revision!=='string'||!file.revision.trim()||file.revision==='latest')throw Error('AI assets must have pinned revision, size, and checksum');
    const candidate=path.resolve(base,...file.path.split('/')),actual=await fs.promises.realpath(candidate),relative=path.relative(base,actual);
    if(relative.startsWith('..'+path.sep)||relative==='..'||path.isAbsolute(relative))throw Error('AI asset escapes its resource directory');
    if(!(await fs.promises.stat(actual)).isFile()||(await fs.promises.stat(actual)).size!==file.bytes)throw Error('AI asset size mismatch');
    const hash=createHash('sha256');for await(const chunk of fs.createReadStream(actual))hash.update(chunk);
    if(hash.digest('hex')!==file.sha256)throw Error('AI asset checksum mismatch');
    verified.push({...file,absolutePath:actual});
  }
  for(const role of ['runtime','chat','embedding','license'])if(!verified.some(file=>file.role===role))throw Error('Incomplete offline AI assets: '+role);
  return verified;
}
module.exports={verifyAssets};
