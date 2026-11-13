/* YAMNet's published 16kHz, 25ms periodic-Hann, magnitude-mel frontend.
   Keep FFT outside TFJS: its complex FFT kernel is unavailable on WASM. */
var AudioFeatures=(()=>{
  const N=512,window=Float64Array.from({length:400},(_,i)=>.5-.5*Math.cos(2*Math.PI*i/400));
  const mel=f=>1127*Math.log(1+f/700),lo=mel(125),hi=mel(7500),edges=Array.from({length:66},(_,i)=>lo+(hi-lo)*i/65);
  const filters=Array.from({length:64},(_,m)=>Array.from({length:257},(_,k)=>{
    const x=mel(k*16000/N),w=Math.max(0,Math.min((x-edges[m])/(edges[m+1]-edges[m]),(edges[m+2]-x)/(edges[m+2]-edges[m+1])));
    return [k,k===0?0:w];
  }).filter(x=>x[1]>0));
  function spectrum(samples,start){
    const re=new Float64Array(N),im=new Float64Array(N);
    for(let i=0;i<400;i++)re[i]=(samples[start+i]||0)*window[i];
    for(let i=1,j=0;i<N;i++){let bit=N>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)[re[i],re[j]]=[re[j],re[i]]}
    for(let len=2;len<=N;len*=2){const c=Math.cos(-2*Math.PI/len),s=Math.sin(-2*Math.PI/len);for(let i=0;i<N;i+=len){let wr=1,wi=0;for(let j=0;j<len/2;j++){const a=i+j,b=a+len/2,tr=re[b]*wr-im[b]*wi,ti=re[b]*wi+im[b]*wr;re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;const next=wr*c-wi*s;wi=wr*s+wi*c;wr=next}}}
    return Float64Array.from({length:257},(_,i)=>Math.hypot(re[i],im[i]));
  }
  function patch(samples,start){
    const out=new Float32Array(96*64);
    for(let f=0;f<96;f++){const mag=spectrum(samples,start+f*160);for(let m=0;m<64;m++){let v=0;for(const [k,w] of filters[m])v+=mag[k]*w;out[f*64+m]=Math.log(v+.001)}}
    return out;
  }
  function envelope(samples){
    const rms=[],voice=[],step=.02;let prevX=0,prevY=0,low=0;
    // 90 Hz high-pass followed by a 3.5 kHz low-pass: reject rumble and clicks.
    for(let offset=0;offset<samples.length;offset+=320){let sum=0,v=0;const count=Math.min(320,samples.length-offset);for(let i=0;i<count;i++){const x=samples[offset+i],hp=.966*(prevY+x-prevX);prevX=x;prevY=hp;low+=.747*(hp-low);sum+=x*x;v+=low*low}rms.push(Math.sqrt(sum/count));voice.push(Math.sqrt(v/count))}
    return {step,rms,voice};
  }
  function speechBand(samples){
    // Analysis view only: attenuate low rumble without changing playback audio.
    const out=new Float32Array(samples.length);let previous=0,high=0,low=0;
    for(let i=0;i<samples.length;i++){high=.91*(high+samples[i]-previous);previous=samples[i];low+=.70*(high-low);out[i]=low}
    return out;
  }
  return {patch,envelope,speechBand};
})();
if(typeof module!=='undefined')module.exports=AudioFeatures;
