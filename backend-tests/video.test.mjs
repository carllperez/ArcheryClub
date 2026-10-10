import {test} from 'node:test';
import assert from 'node:assert/strict';
import {durationMs} from '../supabase/functions/verify-training-video/mp4.mjs';
const u32=n=>{const b=Buffer.alloc(4);b.writeUInt32BE(n);return b;};
const box=(name,...data)=>{const payload=Buffer.concat(data);return Buffer.concat([u32(payload.length+8),Buffer.from(name),payload]);};
function clip(ms=5000,sampleMs=ms,fragment=false){
 const timing=Buffer.concat([Buffer.alloc(12),u32(1000),u32(ms)]);
 const hdlr=Buffer.concat([Buffer.alloc(8),Buffer.from('vide')]);
 const stts=box('stts',Buffer.alloc(4),u32(1),u32(1),u32(sampleMs));
 return Buffer.concat([box('ftyp',Buffer.from('isom')),box('moov',box('mvhd',timing),box('trak',box('mdia',box('mdhd',timing),box('hdlr',hdlr),box('minf',box('stbl',stts))))),box('mdat',Buffer.alloc(16)),...(fragment?[box('moof')]:[])]);
}
test('video duration reads sample timelines and accepts the exact boundary',()=>{assert.equal(durationMs(clip()),5000);assert.equal(durationMs(clip(10000)),10000);});
test('video rejects long clips even with a false short movie header',()=>{assert.throws(()=>durationMs(clip(10001)));assert.throws(()=>durationMs(clip(5000,20000)));});
test('video rejects truncation, invalid sizes and fragmented inputs',()=>{assert.throws(()=>durationMs(clip().subarray(0,40)));assert.throws(()=>durationMs(clip(5000,5000,true)));assert.throws(()=>durationMs(Buffer.from('not a movie')));});
