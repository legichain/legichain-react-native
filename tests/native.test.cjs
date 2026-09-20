const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const code=fs.readFileSync(require.resolve('../dist/index.js'),'utf8');
function sdk(platform,start) {
  const module={exports:{}};
  vm.runInNewContext(code,{module,exports:module.exports,require(name) {
    assert.equal(name,'react-native');return {Platform:{OS:platform},NativeModules:{LegichainKyc:{start}}};
  },setTimeout,clearTimeout,URL,AbortController,Uint8Array,ArrayBuffer,TextEncoder,TextDecoder});
  return module.exports;
}
test('native entry configures one token, Turkish defaults and host completion',async()=>{
  let sent;
  const api=sdk('android',async options=>{sent=options;return {status:'submitted',application_id:'tr_app'};});
  const result=await api.startKyc({apiToken:'synthetic'});
  assert.equal(sent.language,'tr');assert.equal(sent.baseUrl,'https://api.legichain.com');
  assert.equal(sent.apiToken,'synthetic');assert.equal('clientToken' in sent,false);
  assert.equal(result.status,'submitted');assert.equal(result.application_id,'tr_app');
});
test('English selection and cancellation remain distinct from submission',async()=>{
  const api=sdk('ios',async options=>{assert.equal(options.language,'en');return {status:'cancelled',application_id:'tr_app'};});
  assert.equal((await api.startKyc({apiToken:'synthetic',language:'en'})).status,'cancelled');
});
test('unsupported platform and missing token fail before native launch',async()=>{
  const launch=()=>{throw new Error('must not run');};
  await assert.rejects(sdk('web',launch).startKyc({apiToken:'synthetic'}),/requires iOS or Android/);
  await assert.rejects(sdk('android',launch).startKyc({apiToken:''}),/apiToken is required/);
});
