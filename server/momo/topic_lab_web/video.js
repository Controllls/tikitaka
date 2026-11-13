"use strict";
const videoState={assets:[],defaults:null,defaultCharacters:[],preview:null};
// The original audio tool remains available through the video toggle; video is the default workflow.
document.querySelector(".nav-active").firstChild.textContent="◉ 영상 제작 실험실 ";
document.querySelector(".sidebar-note").replaceChildren(el("span","eyebrow","FROM VOICE TO VIDEO"),el("p","","음성과 소스를 넣고, 완성된 영상을 비교하세요."));
document.querySelector(".topbar>div").textContent="Playground / Video Lab";
document.querySelector(".intro .eyebrow").textContent="VIDEO PRODUCTION LAB";
document.querySelector(".intro h1").textContent="내 소스와 모델로, 영상을 끝까지 만드세요.";
document.querySelector(".intro p").textContent="원본에서 하이라이트를 추출하거나, 6개 AI 분석과 배경·캐릭터 합성으로 영상을 만드세요. 앱 밖에서 결과를 비교하고 적용할 설정을 남기세요.";
document.querySelector('[data-tab="clips"]').textContent="원본 하이라이트";
document.querySelector('#mode option[value="topics"]').textContent="원본 하이라이트 추출";
document.querySelector(".panel-heading h2").textContent="음성 · 소스 · 모델 설정";
document.querySelector(".page-footer>span").textContent="MOMO / VIDEO LAB";
const videoTab=el("button","tab active","완성 영상");videoTab.dataset.tab="video";videoTab.setAttribute("role","tab");videoTab.setAttribute("aria-selected","true");
document.querySelectorAll("[data-tab]").forEach(n=>{n.classList.remove("active");n.setAttribute("aria-selected","false");});
document.querySelector(".audio-tabs").prepend(videoTab);
videoTab.onclick=()=>{state.tab="video";document.querySelectorAll("[data-tab]").forEach(n=>{n.classList.toggle("active",n===videoTab);n.setAttribute("aria-selected",String(n===videoTab));});render();};
state.tab="video";
empty.replaceChildren(el("h3","","소스를 고르고, 완성 영상을 확인하세요."),el("p","","기본 배경·캐릭터·차량 소스가 준비되어 있습니다. 음성을 넣고 ‘분석부터 영상 만들기’를 누르세요."));
const previewImage=el("img","scene-preview");previewImage.id="scene-preview-image";previewImage.alt="현재 소스로 구성한 캐릭터 장면";
empty.append(previewImage);

function optionsFor(select,kind,current,optional=false){
  select.replaceChildren();if(optional)select.add(new Option("사용 안 함",""));
  videoState.assets.filter(a=>a.kind===kind).forEach(a=>select.add(new Option(a.name,a.id)));
  if([...select.options].some(o=>o.value===current))select.value=current;
}
function syncPoseOptions(index){
  const asset=videoState.assets.find(a=>a.id===$(`person-${index}-character`).value),select=$(`person-${index}-pose`),current=select.value;
  const labels={front:"정면",left:"왼쪽",right:"오른쪽",down:"아래"};
  select.replaceChildren();(asset?.poses||Object.keys(labels)).forEach(p=>select.add(new Option(labels[p]||p,p)));
  select.value=[...select.options].some(o=>o.value===current)?current:"front";
}
function personRows(people){
  $("scene-people").replaceChildren();
  const seats=[["driver","운전석"],["front_passenger","조수석"],["rear_left","뒷좌석 왼쪽"],["rear_right","뒷좌석 오른쪽"]];
  for(let i=0;i<Number($("person-count").value);i++){
    const defaultCharacter=videoState.defaultCharacters[i%Math.max(1,videoState.defaultCharacters.length)]||`builtin-character-${i%2+1}`;
    const p=people[i]||{name:`${i+1}번째 목소리`,seat:seats[i][0],character:defaultCharacter,speaker:`S${i+1}`,pose:"front"};
    const card=el("div","person-card two-fields");card.dataset.person=String(i);
    const fields=[["name","이름"],["character","캐릭터"],["seat","좌석"],["speaker","연결할 화자"],["pose","포즈"]];
    for(const [key,label] of fields){const wrap=el("div"),lab=el("label","",`${i+1}. ${label}`);const input=el(key==="name"?"input":"select");input.id=`person-${i}-${key}`;lab.htmlFor=input.id;
      if(key==="name"){input.maxLength=30;input.value=p.name;}
      else if(key==="character")optionsFor(input,"character",p.character);
      else {const choices=key==="seat"?seats:key==="speaker"?Array.from({length:8},(_,n)=>[`S${n+1}`,`화자 S${n+1}`]):[["front","정면"],["left","왼쪽"],["right","오른쪽"],["down","아래"]];choices.forEach(([v,t])=>input.add(new Option(t,v)));input.value=p[key];}
      wrap.append(lab,input);card.append(wrap);
    }$("scene-people").append(card);syncPoseOptions(i);$(`person-${i}-character`).onchange=()=>syncPoseOptions(i);
  }
}
function readPeople(){return [...document.querySelectorAll("[data-person]")].map(card=>{const i=card.dataset.person,p={person_id:`P${Number(i)+1}`};for(const key of ["name","character","seat","speaker","pose"])p[key]=$(`person-${i}-${key}`).value.trim();return p;});}
function sceneSettings(){
  if(!$("make-video").checked)return null;
  if(!videoState.defaults)throw new Error("장면 소스를 불러오는 중입니다.");
  const scene={title:$("scene-title").value.trim(),output:$("scene-output").value,auto_gaze:$("auto-gaze").checked,laughter_debug:$("laughter-debug").checked,people:readPeople(),layout:{}};
  for(const key of ["background","vehicle","seatbelts","steering_wheel"])scene[key]=$(`scene-${key}`).value||null;
  for(const key of Object.keys(videoState.defaults.layout))scene.layout[key]=Number($(`layout-${key}`).value);
  if(!scene.title||scene.people.some(p=>!p.name))throw new Error("영상 제목과 캐릭터 이름을 입력하세요.");
  for(const key of ["seat","speaker"])if(new Set(scene.people.map(p=>p[key])).size!==scene.people.length)throw new Error("캐릭터별 좌석과 화자를 중복 없이 지정하세요.");
  return scene;
}
function videoMode(){
  if($("make-video").checked)$("mode").value="all";
  $("mode").disabled=state.busy||$("make-video").checked;
  $("run-label").textContent=state.busy?"분석·제작 진행 중…":$("make-video").checked?"분석부터 영상 만들기":"선택한 모델로 음성 분석";
  if(state.catalog.length)refreshSettings();
}
$("make-video").onchange=videoMode;
$("person-count").onchange=()=>personRows(readPeople());
// Folder selection supplies relative paths; package them without a third-party script.
const zipCrcTable=Uint32Array.from({length:256},(_,n)=>{let c=n;for(let j=0;j<8;j++)c=c&1?0xedb88320^(c>>>1):c>>>1;return c>>>0;});
async function folderZip(files){
  if(files.length>400||files.reduce((sum,f)=>sum+f.size,0)>79500000)throw new Error("폴더는 400개 파일·약 80 MB까지 선택할 수 있습니다.");
  const local=[],central=[];let offset=0,centralSize=0;
  for(const file of files){
    const name=new TextEncoder().encode(file.webkitRelativePath||file.name),data=new Uint8Array(await file.arrayBuffer());
    let crc=0xffffffff;for(const b of data)crc=zipCrcTable[(crc^b)&255]^(crc>>>8);crc=(crc^0xffffffff)>>>0;
    const header=new Uint8Array(30+name.length),h=new DataView(header.buffer);
    h.setUint32(0,0x04034b50,true);h.setUint16(4,20,true);h.setUint16(6,0x800,true);h.setUint16(12,33,true);
    h.setUint32(14,crc,true);h.setUint32(18,data.length,true);h.setUint32(22,data.length,true);h.setUint16(26,name.length,true);header.set(name,30);
    const entry=new Uint8Array(46+name.length),e=new DataView(entry.buffer);
    e.setUint32(0,0x02014b50,true);e.setUint16(4,20,true);e.setUint16(6,20,true);e.setUint16(8,0x800,true);e.setUint16(14,33,true);
    e.setUint32(16,crc,true);e.setUint32(20,data.length,true);e.setUint32(24,data.length,true);e.setUint16(28,name.length,true);e.setUint32(42,offset,true);entry.set(name,46);
    local.push(header,data);central.push(entry);offset+=header.length+data.length;centralSize+=entry.length;
  }
  const end=new Uint8Array(22),e=new DataView(end.buffer);e.setUint32(0,0x06054b50,true);e.setUint16(8,files.length,true);e.setUint16(10,files.length,true);e.setUint32(12,centralSize,true);e.setUint32(16,offset,true);
  return new Blob([...local,...central,end],{type:"application/zip"});
}
function applySources(assets){
  videoState.assets.push(...assets);
  for(const kind of ["background","vehicle","seatbelts","steering_wheel"]){
    const found=assets.find(a=>a.kind===kind),select=$(`scene-${kind}`);
    const value=found?.id||(assets.some(a=>a.kind==="vehicle")&&["seatbelts","steering_wheel"].includes(kind)?"":select.value);
    optionsFor(select,kind,value,["seatbelts","steering_wheel"].includes(kind));
  }
  const layout=assets.find(a=>a.kind==="vehicle")?.suggested_layout;
  if(layout)for(const [key,value] of Object.entries(layout)){const input=$(`layout-${key}`);if(input)input.value=value;}
  const characters=assets.filter(a=>a.kind==="character");
  if(characters.length){
    const people=readPeople();
    personRows(people);characters.slice(0,people.length).forEach((a,i)=>{$(`person-${i}-character`).value=a.id;syncPoseOptions(i);$(`person-${i}-pose`).value="front";});
  }
}
async function importSources(category,selected,folder=false){
  if(state.busy||state.recorder||!selected.length)return;
  const status=$(`${category}-status`);
  try{
    busy(true);notice("");status.classList.remove("error");status.textContent="소스를 읽고 있습니다…";
    let files=Array.from(selected);const extensions=category==="background"?/\.(mp4|webm|mov|png|jpe?g|webp)$/i:category==="character"?/\.(json|png)$/i:/\.png$/i;
    if(folder)files=files.filter(f=>extensions.test(f.name)&&!f.name.startsWith("."));
    if(!files.length)throw new Error("선택한 폴더에 지원하는 소스 파일이 없습니다.");
    const zipped=files.some(f=>/\.zip$/i.test(f.name));
    if(zipped&&files.length!==1)throw new Error("ZIP은 한 개씩 선택하세요. 여러 파일은 폴더로 선택할 수 있습니다.");
    const single=!folder&&!zipped&&files.length===1&&category!=="character";
    let assets;
    if(single){
      const file=files[0],kind=category==="vehicles"?$("vehicle-upload-role").value:"background";
      if(file.size>80000000)throw new Error("80 MB 이하 파일을 선택하세요.");
      status.textContent="파일 업로드·형식 확인 중…";
      assets=[await api(`/api/video/assets?kind=${kind}&filename=${encodeURIComponent(file.name)}`,{method:"POST",body:file,headers:{"Content-Type":"application/octet-stream"}})];
    }else{
      const body=zipped?files[0]:await folderZip(files);
      if(body.size>80000000)throw new Error("소스 묶음은 한 번에 80 MB 이하여야 합니다.");
      status.textContent="폴더·ZIP 업로드 및 파츠 확인 중…";
      assets=(await api(`/api/video/packages?category=${category}`,{method:"POST",body,headers:{"Content-Type":"application/zip"}})).assets;
    }
    applySources(assets);
    status.textContent=`추가 완료 · ${assets.length}개: ${assets.map(a=>a.name).join(", ")}`;
    try{await updatePreview();notice("소스를 추가하고 장면에 반영했습니다. 아래에서 캐릭터·좌석을 조정할 수 있습니다.","success");}
    catch(error){notice(`소스는 추가했습니다. 미리보기: ${error.message}`,"error");}
  }catch(error){status.classList.add("error");status.textContent=error.message;notice(error.message,"error");}
  finally{busy(false);render();}
}
for(const category of ["background","character","vehicles"]){
  for(const suffix of ["file","folder"]){const input=$(`${category}-${suffix}`),button=$(`${category}-${suffix==="file"?"pick":"folder-pick"}`);
    button.onclick=()=>input.click();input.onchange=async()=>{try{await importSources(category,input.files,suffix==="folder");}finally{input.value="";}};
  }
  const card=document.querySelector(`[data-source="${category}"]`);
  card.ondragover=event=>{event.preventDefault();if(!state.busy)card.classList.add("dragging");};
  card.ondragleave=event=>{if(!card.contains(event.relatedTarget))card.classList.remove("dragging");};
  card.ondrop=event=>{event.preventDefault();card.classList.remove("dragging");if(state.busy)return;
    if([...event.dataTransfer.items].some(i=>i.webkitGetAsEntry?.()?.isDirectory)){notice("폴더는 해당 영역의 ‘폴더 선택’ 버튼으로 추가하세요.","error");return;}
    importSources(category,event.dataTransfer.files);
  };
}
async function updatePreview(){
  const enabled=$("make-video").checked;$("make-video").checked=true;
  let scene;try{scene=sceneSettings();}finally{$("make-video").checked=enabled;}
  const response=await fetch("/api/video/preview",{method:"POST",headers:{"Content-Type":"application/json","X-Lab-Token":state.csrf},body:JSON.stringify(scene)});
  if(!response.ok){const error=await response.json();throw new Error(error.detail||"미리보기 실패");}
  if(videoState.preview)URL.revokeObjectURL(videoState.preview);
  videoState.preview=URL.createObjectURL(await response.blob());
  empty.querySelector("img").src=videoState.preview;
  // Preview always stays reachable even when an existing experiment is selected.
  let image=$("live-scene-preview");if(!image){image=el("img","scene-preview small-preview");image.id="live-scene-preview";image.alt="수정한 장면 미리보기";$("preview-scene").after(image);}image.src=videoState.preview;
  render();
}
$("preview-scene").onclick=async()=>{try{busy(true);await updatePreview();notice("현재 소스로 장면 미리보기를 갱신했습니다.","success");}catch(error){notice(error.message,"error");}finally{busy(false);render();}};
async function initializeVideo(showPreview=true){
  const data=await api("/api/video/assets");videoState.assets=data.assets;videoState.defaults=data.defaults;
  videoState.defaultCharacters=data.default_characters||[];
  if(data.default_source){
    const source=data.default_source;
    $("background-status").textContent=`기본 적용 · ${source.label} · 배경 ${source.background}개`;
    $("character-status").textContent=`기본 적용 · ${source.label} · 캐릭터 ${source.character}종 · 아래에서 선택할 수 있습니다.`;
    $("vehicles-status").textContent=`기본 적용 · ${source.label} · 차량 ${source.vehicle_layers}개 레이어`;
  }
  for(const key of ["background","vehicle","seatbelts","steering_wheel"])optionsFor($(`scene-${key}`),key,data.defaults[key],["seatbelts","steering_wheel"].includes(key));
  personRows(data.defaults.people);
  const labels={car_left:"차량 X",car_top:"차량 Y",car_width:"차량 너비",front_height:"앞 캐릭터 높이",rear_height:"뒤 캐릭터 높이",front_root_y:"앞 캐릭터 기준 Y",rear_root_y:"뒤 캐릭터 기준 Y"};
  for(const [key,value] of Object.entries(data.defaults.layout)){const wrap=el("div"),label=el("label","",labels[key]),input=el("input");input.type="number";input.id=`layout-${key}`;input.value=value;label.htmlFor=input.id;wrap.append(label,input);$("scene-layout").append(wrap);}
  videoMode();if(showPreview)await updatePreview();
}
function renderVideo(run,root){
  const video=run.video;
  if(video){
    const summary=el("div","video-summary");summary.append(el("strong","",`${video.items.length}개 영상 · 합성 ${seconds(video.render_ms)}`),el("p","hint",video.note));
    summary.append(el("p","laughter-result",laughterResult(run)));
    if(video.scene?.laughter_debug)summary.append(el("p","hint","웃음 진단 수치가 영상에 포함되어 있습니다. 일시 정지해 원점수·판정 기준·화자별 표정 상태를 비교하세요."));
    if(run.laughter_debug?.backfilled_lanes||run.laughter_debug?.backfilled_original)summary.append(el("p","hint",`저장된 전사·화자를 유지하고 웃음 원점수만 추가 계산했습니다: ${run.laughter_debug.backfilled_original?"원본 + ":""}${run.laughter_debug.backfilled_lanes}개 구간.`));
    if(run.reused_analysis_id)summary.append(el("p","hint","이 실험은 기존 분석을 재사용했습니다. 표시된 AI 단계 시간은 원래 분석의 측정값입니다."));
    root.append(summary);
    for(const item of video.items){const card=el("article","video-card"),player=el("video");player.controls=true;player.preload="metadata";player.playsInline=true;player.src=`/api/audio/jobs/${run.id}/files/${item.file}`;player.poster=`/api/audio/jobs/${run.id}/files/${item.poster}`;
      card.append(el("h3","",item.title),el("p","hint",`${clock(item.startMs)} – ${clock(item.endMs)} · 540 × 960 / 30 fps`),player);const links=el("div","download-links");links.append(fileLink(run,item.file,"MP4 내려받기 ↓"));card.append(links);root.append(card);}
    const links=el("div","download-links bundle-download");if(!run.partial)links.append(fileLink(run,video.bundle,"앱 적용 자료 ZIP ↓"),fileLink(run,run.characters?"character-result.json":"app-result.json","분석 JSON ↓"),fileLink(run,run.characters?"character-tracks.json":"app-tracks.json","움직임 트랙 ↓"));root.append(links);
    root.append(el("p","hint","ZIP에는 원본 음성·선택한 소스·장면 배치·모델 설정·분석 결과가 들어갑니다. 앱 자동 적용은 별도 연결이 필요합니다."));
    const mapping=el("p","hint",`감지 화자: ${video.speakers.map(s=>s.speaker_id).join(", ")||"없음"} · 원래 모델 ID → 영상 화자: ${Object.entries(video.speaker_mapping).map(([a,b])=>`${a} → ${b}`).join(", ")||"없음"}`);root.append(mapping);
    for(const warning of video.warnings||[])root.append(el("p","result-note",warning.message));
  }else root.append(el("p","result-note","이 기록에는 완성 영상이 없습니다. 전체 6개 기능 분석을 완료했다면 현재 장면으로 영상을 만들 수 있습니다."));
  if(!run.partial&&run.requested_mode==="all"){
    const rerender=el("button","primary rerender","현재 소스로 영상만 다시 만들기");rerender.disabled=state.busy;
    rerender.onclick=async()=>{try{if(state.busy)return;$("make-video").checked=true;videoMode();const scene=sceneSettings();busy(true);const job=await api("/api/audio/jobs",{method:"POST",body:JSON.stringify({reuse_job_id:run.id,scene})});state.active=job.id;$("cancel").disabled=false;history();notice(scene.laughter_debug?"저장된 전사·화자 분석을 재사용합니다. 웃음 원점수가 없으면 해당 점수만 추가 계산해 영상에 표시합니다.":"저장된 분석을 재사용해 영상을 제작합니다. AI 모델을 다시 호출하지 않습니다.");poll(job.id);}catch(error){busy(false);notice(error.message,"error");}};
    root.append(rerender,el("p","hint",run.characters?"저장된 화자·자막·웃음 판정과 감도를 사용합니다. 수치 표시를 켜면 누락된 웃음 원점수만 보충하며 전사·화자를 다시 계산하지 않습니다.":run.config.pipeline==="app"?"영상만 다시 만들기는 저장된 앱 분석의 화자·웃음·자막을 그대로 사용합니다. 기존 방식에는 원점수가 없어 진단에 ‘기록 없음’으로 표시됩니다.":`영상만 다시 만들기는 저장된 웃음 기준 ${run.config.threshold.toFixed(3)}을 유지합니다. 바꾼 감도를 적용하려면 화면의 분석·제작 버튼으로 다시 실행하세요.`));
  }
}
function compareVideos(runs,root){
  const grid=el("div","compare-grid video-compare");
  for(const [index,run] of runs.entries()){const column=el("div","compare-column");column.append(el("h3","",`실험 ${index?"B":"A"} · 영상`));
    if(run.video){column.append(el("p","hint",`합성 ${seconds(run.video.render_ms)}${run.reused_analysis_id?" · 분석 재사용":""}`));const item=run.video.items[0];if(item){const player=el("video");player.controls=true;player.preload="metadata";player.src=`/api/audio/jobs/${run.id}/files/${item.file}`;column.append(player);}}
    else column.append(el("p","hint","영상 없음"));grid.append(column);
  }root.prepend(grid);
}
