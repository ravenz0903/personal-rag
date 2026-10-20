const fs=require('node:fs'),path=require('node:path'),{randomUUID}=require('node:crypto');
const MAX_BYTES=8*1024*1024;
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
class NoteDrafts {
  constructor(root){this.root=path.resolve(root);this.folder=this.inside(path.join(this.root,'note-drafts'));fs.mkdirSync(this.folder,{recursive:true});}
  inside(file){const absolute=path.resolve(file);if(!absolute.startsWith(this.root+path.sep))throw Error('Invalid draft location');return absolute;}
  file(id){if(typeof id!=='string'||!UUID.test(id))throw Error('Invalid note draft ID');return this.inside(path.join(this.folder,id.toLowerCase()+'.json'));}
  valid(id,value){if(typeof value!=='string'||Buffer.byteLength(value)>MAX_BYTES)return false;try{const note=JSON.parse(value);return note&&typeof note.id==='string'&&note.id.toLowerCase()===id.toLowerCase()&&['ACADEMICS','CAREER','PERSONAL'].includes(note.domain)&&typeof note.title==='string'&&Array.isArray(note.tags)&&note.tags.every(tag=>typeof tag==='string')&&Number.isInteger(note.revision)&&note.revision>=0&&note.content?.type==='doc';}catch{return false;}}
  get(id){const file=this.file(id);try{if(fs.statSync(file).size>MAX_BYTES)return null;const value=fs.readFileSync(file,'utf8');return this.valid(id,value)?value:null;}catch(error){if(error.code==='ENOENT')return null;throw error;}}
  set(id,value){const file=this.file(id);if(!this.valid(id,value))throw Error('Invalid or oversized recovery draft');fs.mkdirSync(this.folder,{recursive:true});const temporary=this.inside(path.join(this.folder,id+'.'+randomUUID()+'.partial'));let fd;try{fd=fs.openSync(temporary,'wx',0o600);fs.writeFileSync(fd,value,'utf8');fs.fsyncSync(fd);fs.closeSync(fd);fd=undefined;fs.renameSync(temporary,file);}finally{if(fd!==undefined)fs.closeSync(fd);fs.rmSync(temporary,{force:true});}return true;}
  remove(id,expected){const file=this.file(id);if(expected!==undefined&&this.get(id)!==expected)return false;fs.rmSync(file,{force:true});return true;}
  archiveForRestore(){if(!fs.readdirSync(this.folder).length)return null;const name='note-drafts-before-restore-'+randomUUID();const destination=this.inside(path.join(this.root,name));fs.renameSync(this.folder,destination);fs.mkdirSync(this.folder);return name;}
  restoreArchive(name){if(typeof name!=='string'||!/^note-drafts-before-restore-[a-f0-9-]{36}$/.test(name))throw Error('Invalid draft recovery archive');const source=this.inside(path.join(this.root,name));if(fs.existsSync(this.folder))fs.renameSync(this.folder,this.inside(path.join(this.root,'note-drafts-after-failed-restore-'+randomUUID())));fs.renameSync(source,this.folder);}
  dispatch(operation,id,value,expected){switch(operation){case 'get':return this.get(id);case 'set':return this.set(id,value);case 'remove':return this.remove(id,expected);default:throw Error('Unknown draft operation');}}
}
module.exports={NoteDrafts};
