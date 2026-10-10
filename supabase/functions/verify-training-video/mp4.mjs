// Accept bounded, nonfragmented MP4 sample timelines. Never trust filename or client duration.
export function durationMs(bytes) {
 const data=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
 function boxes(start,end){const out=[];while(start<end){
  if(end-start<8)throw Error('Truncated MP4 box');
  let size=data.getUint32(start),header=8;
  const type=String.fromCharCode(...bytes.subarray(start+4,start+8));
  if(size===1){if(end-start<16)throw Error('Truncated box');size=Number(data.getBigUint64(start+8));header=16;}
  if(size===0)size=end-start;
  if(!Number.isSafeInteger(size)||size<header||start+size>end)throw Error('Invalid MP4 size');
  out.push({type,start:start+header,end:start+size});start+=size;
 }return out;}
 const top=boxes(0,bytes.length);
 if(!top.some(b=>b.type==='ftyp')||!top.some(b=>b.type==='mdat')||top.some(b=>b.type==='moof'))throw Error('Use a nonfragmented MP4 clip');
 const movies=top.filter(b=>b.type==='moov');if(movies.length!==1)throw Error('Missing movie');
 const movie=boxes(movies[0].start,movies[0].end);
 if(movie.some(b=>b.type==='mvex'))throw Error('Fragmented MP4 is unsupported');
 const one=(list,type)=>{const found=list.filter(b=>b.type===type);if(found.length!==1)throw Error('Missing or duplicate '+type);return found[0];};
 function timing(box){const version=data.getUint8(box.start);if(version>1)throw Error('Unsupported time header');
  const offset=version===1?20:12, count=version===1?8:4;
  if(box.start+offset+4+count>box.end)throw Error('Truncated time header');
  const scale=data.getUint32(box.start+offset),duration=version===1?Number(data.getBigUint64(box.start+offset+4)):data.getUint32(box.start+offset+4);
  if(!scale||!Number.isSafeInteger(duration)||duration<=0)throw Error('Invalid duration');return {scale,duration};
 }
 const mt=timing(one(movie,'mvhd'));let maximum=mt.duration/mt.scale*1000;let video=false;
 const tracks=movie.filter(b=>b.type==='trak');if(!tracks.length||tracks.length>16)throw Error('Invalid tracks');
 for(const track of tracks){
  const trackBoxes=boxes(track.start,track.end);
  const edit=trackBoxes.find(b=>b.type==='edts');
  if(edit){const list=one(boxes(edit.start,edit.end),'elst');
   const v=data.getUint8(list.start),count=data.getUint32(list.start+4),stride=v===1?20:12;
   if(v>1||count>1000||list.start+8+count*stride!==list.end)throw Error('Invalid edit list');
   let sum=0;for(let i=0;i<count;i++){const off=list.start+8+i*stride;sum+=v===1?Number(data.getBigUint64(off)):data.getUint32(off);
    const rate=off+(v===1?16:8);if(data.getInt16(rate)!==1||data.getInt16(rate+2)!==0)throw Error('Unsupported playback rate');}
   maximum=Math.max(maximum,sum/mt.scale*1000);
  }
  const media=one(trackBoxes,'mdia'),children=boxes(media.start,media.end),header=timing(one(children,'mdhd'));
  maximum=Math.max(maximum,header.duration/header.scale*1000);
  const handler=one(children,'hdlr');if(handler.start+12>handler.end)throw Error('Truncated handler');
  video ||= String.fromCharCode(...bytes.subarray(handler.start+8,handler.start+12))==='vide';
  const minf=one(children,'minf'),stbl=one(boxes(minf.start,minf.end),'stbl'),tables=boxes(stbl.start,stbl.end);
  const stts=one(tables,'stts');if(stts.start+8>stts.end)throw Error('Truncated sample timing');
  const count=data.getUint32(stts.start+4);if(count>100000||stts.start+8+count*8!==stts.end)throw Error('Invalid sample timing');
  let total=0,samples=0;for(let i=0;i<count;i++){const off=stts.start+8+i*8,n=data.getUint32(off),delta=data.getUint32(off+4);
   if(!n||!delta)throw Error('Invalid sample duration');samples+=n;total+=n*delta;}
  if(!samples||!Number.isSafeInteger(total))throw Error('Invalid sample count');
  maximum=Math.max(maximum,total/header.scale*1000);
  // Composition offsets can extend presentation beyond the decode timeline.
  const ctts=tables.find(b=>b.type==='ctts');if(ctts){if(ctts.start+8>ctts.end)throw Error('Invalid offsets');
   const v=data.getUint8(ctts.start),n=data.getUint32(ctts.start+4);if(v>1||n>100000||ctts.start+8+n*8!==ctts.end)throw Error('Invalid offsets');
   let offset=0,seen=0;for(let j=0;j<n;j++){const off=ctts.start+8+j*8;seen+=data.getUint32(off);offset=Math.max(offset,v===1?data.getInt32(off+4):data.getUint32(off+4));}
   if(seen!==samples)throw Error('Sample counts disagree');maximum=Math.max(maximum,(total+offset)/header.scale*1000);
  }
 }
 if(!video||!Number.isFinite(maximum)||maximum<=0||maximum>10000)throw Error('Video must be no longer than 10 seconds');
 return Math.ceil(maximum);
}
