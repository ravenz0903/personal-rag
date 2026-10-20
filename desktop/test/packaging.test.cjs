const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
test('every local dependency imported by packaged launcher modules is included in the build',()=>{
  const root=path.resolve(__dirname,'..'),config=require('../package.json').build;
  const exact=new Set(config.files.filter(name=>!name.includes('*')));
  for(const name of [...exact].filter(name=>name.endsWith('.cjs'))){
    const source=fs.readFileSync(path.join(root,name),'utf8');
    for(const match of source.matchAll(/require\(['"](\.[^'"]+)['"]\)/g)){
      const dependency=path.relative(root,path.resolve(root,path.dirname(name),match[1])).replaceAll('\\','/');
      assert.ok(exact.has(dependency),`${name} imports ${dependency}, which must ship with the application`);
      assert.ok(fs.existsSync(path.join(root,dependency)),`Missing dependency ${dependency}`);
    }
  }
});
