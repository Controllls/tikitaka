function customVisuals(){return p.visualSource==='uploaded'||p.assets.length>0}
function applyUploadedAssets(ids=[]){const result=SceneAssets.apply(p,ids);updateAssetStatus();return result}
function assetFailureMessages(){
  if(!customVisuals())return [];
  const used=new Set([p.background,p.vehicle,p.expression,...p.people.map(x=>x.asset)]);
  for(const person of p.people)for(const k of ['expression','mouth'])SceneAssets.layers(p,person,k).forEach(a=>used.add(a.id));
  for(const person of p.people)for(const a of SceneAssets.rig(p,person,false,false).concat(SceneAssets.rig(p,person,true,true)))used.add(a.id);
  return p.assets.filter(a=>used.has(a.id)&&a.kind!=='motion'&&!images[a.id]).map(a=>'소스를 읽지 못했습니다: '+(a.sourcePath||a.name));
}
function updateAssetStatus(){
  const target=$('#assetStatus');if(!target)return;
  if(!customVisuals()){target.innerHTML='<span class="pill">샘플 에셋 사용 중</span>';return}
  const summary=SceneAssets.summary(p),failures=assetFailureMessages();
  const opened=!!target.querySelector?.('details')?.open;
  target.innerHTML='<details data-fold="applied-assets"'+(opened?' open':'')+'><summary>✓ 업로드한 소스 적용 중 · 연결 확인</summary><ul>'+summary.characters.map(x=>'<li>'+esc(x.name)+': '+esc(x.file||'캐릭터 없음 · 숨김')+'</li>').join('')+'<li>배경: '+esc(summary.background||'없음 · 단색 배경')+'</li><li>차량: '+esc(summary.vehicle||'없음 · 숨김')+'</li></ul></details>'+failures.map(x=>'<p class="error">'+esc(x)+'</p>').join('');
  if(!summary.background)target.innerHTML+='<p class="hint">배경 파일이 아직 없어요. 소스 준비에서 배경이 든 폴더를 선택하면 자동 적용됩니다. 이전에 제외된 파일은 다시 선택해야 읽을 수 있어요.</p>';
}
function fitImage(im,x,y,w,h,mode='contain'){
  const iw=im.videoWidth||im.naturalWidth||im.width,ih=im.videoHeight||im.naturalHeight||im.height;if(!iw||!ih)return;
  const ratio=mode==='cover'?Math.max(w/iw,h/ih):Math.min(w/iw,h/ih);
  g.save();g.beginPath();g.rect(x,y,w,h);g.clip();g.drawImage(im,x+(w-iw*ratio)/2,y+(h-ih*ratio)/2,iw*ratio,ih*ratio);g.restore();
}
var CabinLayout={frame:{x:-120,y:220,width:780,height:438.75},source:{width:1920,height:1080},window:[[170,176],[960,124],[1750,176],[1840,735],[80,735]],seats:{driver:[1328,755],front_passenger:[605,755],rear_left:[1120,690],rear_right:[800,690]},rearScale:1.12,steeringOffsetY:-92.4444};
function cabinPoint(point){const f=CabinLayout.frame;return [f.x+point[0]/1920*f.width,f.y+point[1]/1080*f.height]}
function clipRearWindow(){g.beginPath();CabinLayout.window.forEach((point,i)=>{const [x,y]=cabinPoint(point);i?g.lineTo(x,y):g.moveTo(x,y)});g.closePath();g.clip()}
// Only the two FRONT seats and console occlude rear passengers. The central
// bench belongs behind them, even though all seats share one uploaded PNG.
CabinLayout.frontOccluders=[
  [['M',500,303],['L',710,303],['Q',774,303,774,367],['L',774,444],['Q',774,503,710,503],['L',500,503],['Q',437,503,437,444],['L',437,367],['Q',437,303,500,303],['Z']],
  [['M',480,501],['L',710,501],['Q',811,501,821,601],['L',840,958],['L',796,1041],['L',364,1041],['L',383,601],['Q',390,501,480,501],['Z']],
  [['M',1220,303],['L',1430,303],['Q',1492,303,1492,367],['L',1492,444],['Q',1492,503,1430,503],['L',1220,503],['Q',1158,503,1158,444],['L',1158,367],['Q',1158,303,1220,303],['Z']],
  [['M',1220,501],['L',1430,501],['Q',1538,501,1545,601],['L',1566,1041],['L',1125,1041],['L',1085,958],['L',1104,601],['Q',1111,501,1220,501],['Z']],
  [['M',795,958],['Q',960,927,1125,958],['L',1145,1080],['L',775,1080],['Z']]
];
function clipFrontSeats(){
  g.beginPath();
  for(const path of CabinLayout.frontOccluders)for(const [kind,...points]of path){
    if(kind==='Z'){g.closePath();continue}
    const coordinates=[];for(let i=0;i<points.length;i+=2)coordinates.push(...cabinPoint([points[i],points[i+1]]));
    g[{M:'moveTo',L:'lineTo',Q:'quadraticCurveTo'}[kind]](...coordinates);
  }
  g.clip();
}
function drawDiaryHeader(){txt('MOMO DRIVE DIARY',270,62,16,'#698568');wrap(p.title,270,109,460,28);txt(p.date+' · '+p.place,270,164,17)}
const alphaBounds=new WeakMap();
function partBounds(im){
  if(alphaBounds.has(im))return alphaBounds.get(im);
  let bounds=null;
  try{const c=document.createElement('canvas');c.width=im.naturalWidth;c.height=im.naturalHeight;const cx=c.getContext('2d',{willReadFrequently:true});cx.drawImage(im,0,0);const data=cx.getImageData(0,0,c.width,c.height).data;let x0=c.width,y0=c.height,x1=-1,y1=-1;for(let y=0;y<c.height;y++)for(let x=0;x<c.width;x++)if(data[(y*c.width+x)*4+3]>24){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y)}if(x1>=0)bounds={x:x0,y:y0,w:x1-x0+1,h:y1-y0+1};}catch{}
  alphaBounds.set(im,bounds);return bounds;
}
function alignedBox(im,w,h,b){const ratio=Math.min(w/im.naturalWidth,h/im.naturalHeight);return {x:-w/2+(w-im.naturalWidth*ratio)/2+b.x*ratio,y:-h*.78+(h-im.naturalHeight*ratio)/2+b.y*ratio,w:b.w*ratio,h:b.h*ratio}}
function drawMouthPart(im,w,h,amount){
  const b=partBounds(im);if(!b){fitImage(im,-w/2,-h*.78,w,h);return}
  const r=alignedBox(im,w,h,b),height=r.h*(.3+.7*amount);g.drawImage(im,b.x,b.y,b.w,b.h,r.x,r.y+(r.h-height)/2,r.w,height);
}
function drawLaughSmile(box,amount){
  const x=box.x+box.w/2,y=box.y+box.h/2,r=Math.max(6,box.w*.62),depth=Math.max(5,box.h*(.75+.7*amount));
  g.fillStyle='#744044';g.beginPath();g.moveTo(x-r,y-2);g.quadraticCurveTo(x,y+3,x+r,y-2);g.quadraticCurveTo(x+r*.65,y+depth,x,y+depth);g.quadraticCurveTo(x-r*.65,y+depth,x-r,y-2);g.fill();
  g.fillStyle='#fff9e8';g.beginPath();g.moveTo(x-r*.8,y);g.quadraticCurveTo(x,y+4,x+r*.8,y);g.lineTo(x+r*.57,y+4);g.quadraticCurveTo(x,y+7,x-r*.57,y+4);g.fill();
}
function drawUploadedScene(at,scene){
  g.fillStyle='#f6f3eb';g.fillRect(0,0,540,960);
  const f=CabinLayout.frame,bg=images[p.background],car=images[p.vehicle];
  // Scenery is visible only through the rear window. The ceiling stays opaque.
  if(bg){g.save();clipRearWindow();fitImage(bg,f.x,f.y,f.width,f.height,'cover');g.restore()}
  if(car)fitImage(car,f.x,f.y,f.width,f.height);
  let foregroundDrawn=false;
  const hasRear=p.people.some(person=>person.seat.startsWith('rear')&&images[person.asset]);
  const drawFrontSeats=()=>{if(!foregroundDrawn&&car&&hasRear){g.save();clipFrontSeats();fitImage(car,f.x,f.y,f.width,f.height);g.restore()}foregroundDrawn=true};
  const sorted=[...p.people].sort((a,b)=>Number(b.seat.startsWith('rear'))-Number(a.seat.startsWith('rear')));
  for(const person of sorted){
    if(!person.seat.startsWith('rear'))drawFrontSeats();
    const im=images[person.asset];if(!im)continue;
    const state=characterMotion(person,at,scene),amp=state.mouth,rear=person.seat.startsWith('rear');
    const [x,y]=cabinPoint(CabinLayout.seats[person.seat]||CabinLayout.seats.driver);
    const w=(rear?110*CabinLayout.rearScale:175)*f.width/540,h=(rear?145*CabinLayout.rearScale:230)*f.width/540;
    let dy=0;if(amp>.08&&p.motion!=='still'){
      const motion=p.assets.find(a=>a.id===p.motion&&a.kind==='motion'),m=motion?JSON.parse(motion.data):{amplitude:4,frequency:5};dy=Math.sin(at*m.frequency)*m.amplitude;
    }
    if(state.laughing)dy=state.dy;
    g.save();g.translate(x+state.dx,y+dy);g.rotate(state.tilt);
    const rig=SceneAssets.rig(p,person,amp>.18,at%4.2<.14,state.laughing).filter(a=>images[a.id]);
    const happy=state.laughing&&SceneAssets.expression(p,person,'laughter'),happyImage=happy&&images[happy.id];
    if(rig.length){for(const part of rig){
      const partImage=images[part.id],mouth=/mouth_/i.test(part.name||part.sourcePath);
      if(mouth&&state.laughing&&!happyImage&&!/laugh|smile/i.test(part.name||'')){const bounds=partBounds(partImage);drawLaughSmile(bounds?alignedBox(partImage,w,h,bounds):{x:-w*.07,y:-h*.43,w:w*.14,h:h*.045},amp)}
      else if(mouth&&amp>.18)drawMouthPart(partImage,w,h,amp);
      else fitImage(partImage,-w/2,-h*.78,w,h);
    }}else fitImage(im,-w/2,-h*.78,w,h);
    const expressions=SceneAssets.layers(p,person,'expression'),neutral=SceneAssets.expression(p,person,'neutral')||expressions.find(a=>!/(laugh|smil|happy|웃음|미소|기쁨)/i.test(a.name)),ex=images[neutral?.id]||images[p.expression];
    if(!rig.length&&ex)fitImage(ex,-w*.28,-h*.68,w*.56,h*.32);
    const mouths=SceneAssets.layers(p,person,'mouth').filter(a=>images[a.id]&&!/(laugh|smile|웃음|미소)/i.test(a.name));
    if(!rig.length&&mouths.length){const index=amp<.08?0:Math.min(mouths.length-1,1+Math.floor(amp*(mouths.length-1))),mouth=images[mouths[index].id];if(mouth.naturalWidth===im.naturalWidth&&mouth.naturalHeight===im.naturalHeight)fitImage(mouth,-w/2,-h*.78,w,h);else fitImage(mouth,-w*.14,-h*.43,w*.28,h*.13)}
    if(happyImage){if(happyImage.naturalWidth===im.naturalWidth&&happyImage.naturalHeight===im.naturalHeight)fitImage(happyImage,-w/2,-h*.78,w,h);else fitImage(happyImage,-w*.28,-h*.68,w*.56,h*.32)}
    else if(state.laughing&&!rig.length){
      // Optional normalized face anchor supports flat images without facial layers.
      const anchor=person.faceAnchor||{x:.5,y:.29,width:.28},ratio=Math.min(w/im.naturalWidth,h/im.naturalHeight),iw=im.naturalWidth*ratio,ih=im.naturalHeight*ratio;
      const fx=-w/2+(w-iw)/2+anchor.x*iw,fy=-h*.78+(h-ih)/2+anchor.y*ih,fw=anchor.width*iw;
      g.strokeStyle='#644b47';g.lineWidth=3;for(const sign of [-1,1]){g.beginPath();g.arc(fx+sign*fw*.25,fy-fw*.12,fw*.10,Math.PI*1.1,Math.PI*1.9);g.stroke()}
      drawLaughSmile({x:fx-fw*.2,y:fy+fw*.12,w:fw*.4,h:fw*.12},amp);
    }
    if(state.laughing)txt('하하',w*.35,-h*.58,16,'#b57766');
    const labelY=rear?-h*.78+19:h*.27;
    round(rear?-44:-59,labelY-23,rear?88:118,32,12,amp>.08?'#dae9c7':'#fffdf2');txt(person.name,0,labelY,rear?15:18);g.restore();
  }
  drawFrontSeats();
  if(car)for(const a of p.assets.filter(a=>a.kind==='vehicle'&&(/seatbelts_overlay/i.test(a.name)||(/steering_wheel_optional/i.test(a.name)&&p.people.some(x=>x.seat==='driver'))))){
    if(images[a.id])fitImage(images[a.id],f.x,f.y+(/steering_wheel_optional/i.test(a.name)?CabinLayout.steeringOffsetY:0),f.width,f.height);
  }
  if(!p.people.some(person=>images[person.asset])){round(70,425,400,90,20,'#fffdf2');txt('캐릭터 이미지를 넣어 주세요',270,479,22)}
  drawDiaryHeader();
}
function drawSampleCabin(at,scene){
  g.fillStyle='#f6f3eb';g.fillRect(0,0,540,960);round(-40,220,620,480,68,'#f3e2cc','#a9bba2');
  g.save();clipRearWindow();g.fillStyle='#e3eddc';g.fillRect(0,230,540,360);
  for(let i=-1;i<5;i++){const x=i*180-(at*22%180);g.fillStyle=i%2?'#cbdcb9':'#b9d2b6';g.beginPath();g.moveTo(x-80,560);g.quadraticCurveTo(x+80,260,x+220,560);g.fill()}
  g.fillStyle='#bdcbbb';g.fillRect(0,470,540,150);g.restore();
  const sorted=[...p.people].sort((a,b)=>Number(b.seat.startsWith('rear'))-Number(a.seat.startsWith('rear')));
  for(const person of sorted){
    const i=p.people.indexOf(person),rear=person.seat.startsWith('rear'),[x,y]=cabinPoint(CabinLayout.seats[person.seat]),state=characterMotion(person,at,scene),amp=state.mouth;
    g.save();g.translate(x+state.dx,y+state.dy);g.rotate(state.tilt);if(rear)g.scale(.7*CabinLayout.rearScale,.7*CabinLayout.rearScale);
    round(-65,-20,130,115,40,colors[i]);g.fillStyle='#f4d7bc';g.beginPath();g.ellipse(0,-65,61,70,0,0,7);g.fill();g.fillStyle=['#6b5146','#91735e','#514c4a','#76574c'][i];g.beginPath();g.ellipse(0,-107,62,31,0,Math.PI,2*Math.PI);g.fill();
    g.fillStyle='#495147';g.strokeStyle='#495147';g.lineWidth=4;for(const xx of [-20,20]){g.beginPath();if(state.laughing){g.arc(xx,-60,9,Math.PI*1.15,Math.PI*1.85);g.stroke()}else{g.ellipse(xx,-65,3,5,0,0,7);g.fill()}}
    if(state.laughing){drawLaughSmile({x:-13,y:-38,w:26,h:13},amp);txt('하하',65,-105,18,'#b57766')}
    else{g.fillStyle='#965f56';g.beginPath();g.ellipse(0,-32,amp>.18?8+amp*4:6,amp>.18?3+amp*14:2,0,0,7);g.fill()}
    if(person.seat==='driver'){g.strokeStyle='#687c68';g.lineWidth=9;g.beginPath();g.ellipse(0,48,48,31,0,0,7);g.stroke()}txt(person.name,0,120,18);g.restore();
  }
  drawDiaryHeader();
}
function drawSpeechCard(at,scene){
  const person=p.people.find(x=>x.id===scene?.speaker),names=typeof sceneNames==='function'?sceneNames(scene):person?.name||'',ready=hasSpeechAnalysis();
  round(28,743,484,186,24,'#fffdf4');
  const kind=scene?.kind;
  txt(kind==='environment'?'차 안의 소리':kind==='laughter'?(names?names+'의 웃음':'웃음 · 목소리 확인 필요'):names?names+((scene?.speakers?.length||0)>1?'의 대화':'의 한마디'):kind==='speech'?'대화 · 말한 사람 확인 필요':p.audio?(ready?'잠시 쉬어가는 순간':'목소리 분석 중'):'샘플 대화 순서',270,785,22);
  const text=hasSoundAnalysis()?AudioEvents.caption(p.speech,at):(scene?.text||(p.audio?(ready?'원본 목소리를 그대로 듣고 있어요':'분석이 끝나면 말하는 캐릭터만 움직여요'):'대화 녹음을 넣으면 실제 목소리로 바뀝니다'));
  let line='',lines=[];g.font="600 19px 'Noto Sans KR',sans-serif";for(const c of text){if(g.measureText(line+c).width>420){lines.push(line);line=''}line+=c}lines.push(line);lines.slice(0,2).forEach((l,i)=>txt(l,270,826+i*29,19));
  txt(p.audio?(ready?((kind==='speech'||kind==='laughter')&&!names?'말한 사람 확인 필요 · ':scene?.reviewPending?'다른 목소리 확인 필요 · ':'목소리별 발화 동기화 · ')+fmt(at):'자동 분석 준비 중'):'합성음 샘플',270,904,14,'#82927c');
}

function isVideoAsset(a){return a.mediaType==='video'||/^data:video\//.test(a.data)}
function decodeVisual(a){
  if(!isVideoAsset(a))return validateImageData(a.data);
  return new Promise((resolve,reject)=>{const v=document.createElement('video');v.muted=true;v.loop=true;v.playsInline=true;v.preload='auto';const timeout=setTimeout(()=>{v.src='';reject(Error('배경 영상을 읽는 시간이 초과됐습니다'))},20000);v.onloadeddata=()=>{clearTimeout(timeout);resolve(v)};v.onerror=()=>{clearTimeout(timeout);reject(Error('브라우저에서 재생할 수 없는 배경 영상입니다'))};v.src=a.data;v.load()});
}
function stopBackground(){for(const a of p.assets.filter(isVideoAsset))images[a.id]?.pause?.()}
async function startBackground(at){const a=p.assets.find(a=>a.id===p.background&&isVideoAsset(a)),v=a&&images[a.id];if(!v)return;v.currentTime=at%v.duration;await v.play()}
function seekBackground(at){const a=p.assets.find(a=>a.id===p.background&&isVideoAsset(a)),v=a&&images[a.id];if(!v)return;v.onseeked=()=>{if(!playing&&!rendering)draw(t)};v.currentTime=at%v.duration}
