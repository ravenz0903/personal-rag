const { test } = require('node:test');
const assert = require('node:assert/strict');
const { paths, backendArgs } = require('../runtime.cjs');
test('desktop data and bundled resources are separate; arguments preserve paths with spaces', () => {
  const files = paths('C:/Program Files/Folio/resources', 'C:/Users/Example/AppData/Roaming/Folio');
  assert.match(files.data, /Roaming.*Folio.*data/);
  const args = backendArgs(files, 49123);
  assert.ok(args.includes('--spring.profiles.active=desktop'));
  assert.ok(args.includes('--server.port=49123'));
  assert.ok(args.find(a => a.startsWith('--spring.web.resources.static-locations=file:')));
  assert.equal(args.filter(a => a === files.jar).length, 1);
});
