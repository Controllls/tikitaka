"use strict";
const $ = id => document.getElementById(id);
const state = {csrf:"", configured:false, catalog:[], file:null, upload:null, preview:null, active:null, busy:false, runs:[], selected:null, tab:"overview", recorder:null};
Object.assign(state,{maxFileBytes:10000000000,maxSeconds:21600,maxVideoSeconds:600,fileDuration:null,uploadRequest:null});
const empty = $("result-content").firstElementChild.cloneNode(true);
const dependencies = {vad:["vad"],laughter:["laughter"],stt:["vad","stt"],diarization:["vad","diarization"],voice:["vad","diarization","voice"],topics:["vad","laughter","stt","topics"]};
const seconds = ms => `${(ms/1000).toFixed(2)}초`;
const clock = ms => `${ms>=3600000?Math.floor(ms/3600000)+":":""}${Math.floor(ms/60000%60).toString().padStart(2,"0")}:${Math.floor(ms/1000%60).toString().padStart(2,"0")}.${Math.floor(ms%1000/100)}`;
const fileSize = bytes => bytes>=1e9?`${(bytes/1e9).toFixed(2)} GB`:`${(bytes/1e6).toFixed(2)} MB`;
function el(tag, cls, text) {const n=document.createElement(tag); if(cls)n.className=cls;if(text!=null)n.textContent=text;return n;}
function notice(text,type="") {$("notice").textContent=text;$("notice").className=`notice ${type}`;$("notice").hidden=!text;}
async function api(path,options={}) {
  let response;try {response=await fetch(path,{...options,headers:{"Content-Type":"application/json","X-Lab-Token":state.csrf,...options.headers}});}
  catch {throw new Error("로컬 테스트 서버에 연결할 수 없습니다. 실행 중인 터미널을 확인하세요.");}
  const data=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(typeof data.detail==="string"?data.detail:`요청 실패 (HTTP ${response.status})`);
  return data;
}
async function session() {
  const data=await api("/api/session");state.csrf=data.csrf;state.configured=data.configured;
  $("connection-status").textContent=data.configured?"API 키 설정됨":"OpenAI 키 연결";
  $("connection-dot").classList.toggle("connected",data.configured);$("disconnect").hidden=!data.configured;
  if(typeof refreshClaudeSession==="function")await refreshClaudeSession();
}
function openConnection(){ $("connection-error").hidden=true;$("connection-dialog").showModal();$("api-key").focus(); }
$("connection-button").onclick=openConnection;
$("close-dialog").onclick=()=>$("connection-dialog").close();
$("connection-dialog").addEventListener("close",()=>{$("api-key").value="";});
$("connection-form").onsubmit=async event=>{
  event.preventDefault();$("connect").disabled=true;$("connection-error").hidden=true;
  try {
    await api("/api/connection",{method:"POST",body:JSON.stringify({api_key:$("api-key").value,project_id:$("project-id").value.trim(),organization_id:$("org-id").value.trim()})});
    $("api-key").value="";await session();$("connection-dialog").close();
    try {await models();notice("키를 연결했습니다. OpenAI 모델을 선택한 기능에 적용됩니다.","success");}
    catch(error){notice(`키는 설정했습니다. ${error.message} 주제 모델 ID를 직접 입력할 수도 있습니다.`,"error");}
  } catch(error){$("connection-error").textContent=error.message;$("connection-error").hidden=false;}
  finally{$("connect").disabled=false;}
};
$("disconnect").onclick=async()=>{try{await api("/api/connection",{method:"DELETE"});await session();$("connection-dialog").close();notice("OpenAI 연결을 해제했습니다.");}catch(error){notice(error.message,"error");}};
async function models(){
  const current=$("gpt-model").value;const data=await api("/api/models");
  $("gpt-model").replaceChildren(new Option("OpenAI 주제 모델 선택",""));
  data.models.forEach(m=>$("gpt-model").add(new Option(m,m)));$("gpt-model").add(new Option("모델 ID 직접 입력","__custom"));
  if([...$("gpt-model").options].some(o=>o.value===current))$("gpt-model").value=current;
  $("gpt-custom").hidden=$("gpt-model").value!=="__custom";
}
$("refresh-models").onclick=async()=>{if(!state.configured)return openConnection();try{await models();notice("모델 목록을 갱신했습니다.","success");}catch(error){notice(error.message,"error");}};
$("gpt-model").onchange=()=>{$("gpt-custom").hidden=$("gpt-model").value!=="__custom";};
function activeStages(mode=$("mode").value){
  if($("pipeline-mode").value==="segments")return ["vad","laughter","diarization","voice","stt","topics"];
  const chosen=new Set(mode==="all"?state.catalog.map(s=>s.id):dependencies[mode]);
  if($("pipeline-mode").value==="app"){
    if(["laughter","stt","diarization","voice"].some(s=>chosen.has(s))){chosen.add("vad");chosen.add("laughter");}
    return ["vad","laughter","diarization","stt","voice","topics"].filter(s=>chosen.has(s));
  }
  return [...chosen];
}
function laughterThreshold(){
  const input=$("threshold");
  if(!input.checkValidity()){input.reportValidity();throw new Error("웃음 판정 기준을 0.001부터 1 사이에서 입력해 주세요.");}
  return Number(input.value);
}
function sensitivityName(threshold){return threshold>=.2?"낮음":threshold>=.07?"기본":threshold>=.02?"높음":"매우 높음";}
function syncSensitivity(updateSlider=true){
  const input=$("threshold"),valid=input.checkValidity(),threshold=Number(input.value);
  document.querySelectorAll("[data-laughter-threshold]").forEach(button=>button.setAttribute("aria-pressed",String(valid&&Number(button.dataset.laughterThreshold)===threshold)));
  if(!valid){$("sensitivity-value").textContent="0.001 ~ 1 입력";return;}
  // A logarithmic slider keeps small thresholds usable; the existing default is centered.
  const exponent=-Math.log10(threshold),level=exponent<=1?exponent*50:50+(exponent-1)*25;
  if(updateSlider)$("sensitivity-slider").value=level;
  const label=`${sensitivityName(threshold)} · 기준 ${threshold.toFixed(3)}`;
  $("sensitivity-value").textContent=label;$("sensitivity-slider").setAttribute("aria-valuetext",label);
  $("sensitivity-note").textContent=threshold<.03?"매우 높은 감도입니다. 말소리·기침까지 웃음으로 잡힐 수 있으니 원본과 비교하세요.":"감도를 높이면 작은 웃음도 더 잡지만, 말소리·기침이 함께 검출될 수 있습니다.";
}
function setLaughterThreshold(value,updateSlider=true){$("threshold").value=Number(value).toFixed(3);syncSensitivity(updateSlider);}
$("threshold").addEventListener("input",()=>syncSensitivity());
$("sensitivity-slider").addEventListener("input",event=>{
  const level=Number(event.target.value),exponent=level<=50?level/50:1+(level-50)/25;
  setLaughterThreshold(10**-exponent,false);
});
document.querySelectorAll("[data-laughter-threshold]").forEach(button=>button.onclick=()=>setLaughterThreshold(button.dataset.laughterThreshold));
syncSensitivity();
$("pipeline-note").insertAdjacentElement("beforebegin",$("laughter-sensitivity"));
function modelSettings(mode=$("mode").value){
  const config={};state.catalog.forEach(s=>{config[s.id]=$(`model-${s.id}`).value;});
  if(config.topics==="openai:custom"){
    const model=$("gpt-model").value==="__custom"?$("gpt-custom").value.trim():$("gpt-model").value;
    if(!model&&activeStages(mode).includes("topics"))throw new Error("주제 분석용 OpenAI 모델을 선택하거나 ID를 입력해 주세요.");
    config.topics=model?`openai:${model}`:"qwen-local";
  }
  if(!$("max-clips").checkValidity()){$("max-clips").reportValidity();throw new Error("최대 클립 수는 1부터 10 사이의 정수로 입력해 주세요.");}
  return {...config,mode,pipeline:$("pipeline-mode").value,speaker_review:$("pipeline-mode").value==="segments"?speakerReviewSettings():{mode:"local"},segment_separation:$("segment-separation").checked,laughter_captions:$("laughter-captions").checked,threshold:laughterThreshold(),max_clips:Number($("max-clips").value),
    speaker_count:Number($("speaker-count").value),max_output_tokens:Number($("output-tokens").value),qwen_thinking:$("qwen-thinking").checked};
}
function refreshSettings(){
  const active=activeStages();
  const segments=$("pipeline-mode").value==="segments",app=$("pipeline-mode").value!=="experiment";
  state.catalog.forEach(s=>{const option=s.options.find(o=>o.id===$(`model-${s.id}`).value);$(`note-${s.id}`).textContent=option?.note||"";$(`card-${s.id}`).classList.toggle("inactive",!active.includes(s.id));});
  state.catalog.forEach(s=>{$(`model-${s.id}`).disabled=state.busy||(app&&s.id!=="topics");});
  $("laughter-sensitivity").hidden=$("pipeline-mode").value==="app";
  $("segment-options").hidden=!segments;
  [...$("mode").options].forEach(option=>{option.disabled=segments&&!["all","topics"].includes(option.value);});
  $("pipeline-note").textContent=app?"앱과 같은 웃음 판정·웃는 사람 재검증·자막 처리를 사용합니다. 주제 모델은 별도로 선택할 수 있습니다.":"기능별 모델과 웃음 감도를 바꿔 비교합니다. 앱 기본 분석과 결과가 다를 수 있습니다.";
  $("highlight-help").textContent=app?"앱과 같은 소리 판정·자막 처리 후 선택한 주제 모델로 하이라이트를 추출합니다. ‘원본 하이라이트’ 탭에서 재생·다운로드할 수 있습니다.":"아래 웃음 감도와 기능별 모델 설정을 적용합니다. 음성 구간 → 웃음 감지 → 전사 → 주제 분석 후 결과를 확인하세요.";
  if(segments){$("pipeline-note").textContent="각 캐릭터의 말하기·웃음·음량을 따로 처리합니다. ‘캐릭터·구간’ 탭에서 듣기·화자 수정·구간 재분석을 할 수 있습니다.";$("highlight-help").textContent="구간별 분석을 원본 시간순으로 모아 하이라이트를 선정합니다. 긴 원본도 화자 기준과 대화 순서를 유지합니다.";}
  $("gpt-options").hidden=$("model-topics").value!=="openai:custom";
  $("mode-note").textContent=`실행: ${active.map(id=>state.catalog.find(s=>s.id===id)?.label||id).join(" → ")}. 필요한 선행 단계가 함께 실행됩니다.`;
  $("sensitivity-scope").textContent=active.includes("laughter")?`${$("model-laughter").selectedOptions[0].textContent}에 적용됩니다. 설정 후 분석을 다시 실행하세요. 기존 영상은 자동으로 바뀌지 않습니다.`:"현재 실행 범위에는 웃음 감지가 없습니다. ‘웃음 감지만’ 또는 ‘전체 6개 기능’을 선택하면 적용됩니다.";
  if(segments)$("sensitivity-scope").textContent="다음 분석의 웃음 후보·분리 구간·웃음 자막·표정·하이라이트에 함께 적용됩니다. 감도를 바꾼 뒤 ‘분석부터 영상 만들기’ 또는 ‘원본에서 하이라이트 추출’을 실행하세요. 영상만 다시 만들면 기존 감도를 유지합니다.";
}
$("pipeline-mode").onchange=()=>{
  if($("pipeline-mode").value!=="experiment"){
    state.catalog.filter(s=>s.id!=="topics").forEach(s=>{$(`model-${s.id}`).value=s.default;});
    if($("pipeline-mode").value==="app")setLaughterThreshold(.1);
  }
  if($("pipeline-mode").value==="segments"&&!["all","topics"].includes($("mode").value))$("mode").value="all";
  refreshSettings();
};
function buildModels(catalog){
  $("execution-mode").textContent=`STT · Qwen · 앱 모드 화자 분석: ${(catalog.execution_device||"cpu").toUpperCase()} 사용 설정. 실제 실행 장치는 분석 결과에 표시됩니다.`;
  state.maxFileBytes=catalog.max_file_bytes;state.maxSeconds=catalog.max_seconds;state.maxVideoSeconds=catalog.max_video_seconds||600;
  $("source-limits").textContent=`최대 ${fileSize(state.maxFileBytes)} / ${state.maxSeconds/3600}시간 · 소리가 포함된 파일`;
  state.catalog=catalog.stages;$("model-grid").replaceChildren();
  state.catalog.forEach((stage,i)=>{
    const card=el("div","model-card");card.id=`card-${stage.id}`;
    const label=el("label");label.htmlFor=`model-${stage.id}`;label.append(el("span","",String(i+1).padStart(2,"0")),document.createTextNode(stage.label));
    const select=el("select");select.id=`model-${stage.id}`;
    stage.options.forEach(o=>select.add(new Option(o.label,o.id)));select.value=stage.default;select.onchange=refreshSettings;
    const note=el("p");note.id=`note-${stage.id}`;card.append(label,select,note);$("model-grid").append(card);
  });refreshSettings();renderProgress();
}
$("mode").onchange=refreshSettings;
$("reset-models").onclick=()=>{state.catalog.forEach(s=>{$(`model-${s.id}`).value=s.default;});refreshSettings();};
function selectFile(file){
  if(!file)return;if(file.size>state.maxFileBytes){notice(`${fileSize(state.maxFileBytes)} 이하 원본 파일을 선택해 주세요.`,"error");return;}
  state.file=file;state.upload=null;state.fileDuration=null;$("upload-status").hidden=true;if(state.preview)URL.revokeObjectURL(state.preview);state.preview=URL.createObjectURL(file);
  $("source").hidden=false;$("source-name").textContent=file.name;$("source-size").textContent=fileSize(file.size);
  $("source-player").src=state.preview;$("source-note").textContent="파일은 분석을 실행할 때 로컬 서버로 업로드합니다.";notice("");
}
$("source-player").onloadedmetadata=()=>{
  const duration=$("source-player").duration;if(!Number.isFinite(duration))return;state.fileDuration=duration;
  $("source-size").textContent=`${fileSize(state.file.size)} · ${(duration/60).toFixed(1)}분`;
  if(duration>state.maxSeconds)notice(`${state.maxSeconds/3600}시간 이하 원본 파일을 선택해 주세요.`,"error");
  else if(duration>state.maxVideoSeconds&&!state.busy&&$("make-video").checked){
    $("make-video").checked=false;$("mode").value="topics";videoMode();
    notice("긴 원본은 먼저 하이라이트를 추출합니다. 결과에서 10분 이하 클립을 골라 캐릭터 영상으로 제작할 수 있습니다.");
  }
};
function uploadOriginal(){
  return new Promise((resolve,reject)=>{
    const request=new XMLHttpRequest();state.uploadRequest=request;
    $("upload-status").hidden=false;$("upload-progress").value=0;$("upload-label").textContent="원본 업로드 시작";$("cancel").disabled=false;
    request.open("POST",`/api/audio/uploads?filename=${encodeURIComponent(state.file.name)}`);
    request.setRequestHeader("X-Lab-Token",state.csrf);request.setRequestHeader("Content-Type","application/octet-stream");
    request.upload.onprogress=event=>{const percent=event.lengthComputable?Math.round(100*event.loaded/event.total):0;$("upload-progress").value=percent;$("upload-label").textContent=`원본 업로드 ${percent}% · ${fileSize(event.loaded)} / ${fileSize(state.file.size)}`;};
    const finish=()=>{if(state.uploadRequest===request)state.uploadRequest=null;};
    request.onload=()=>{finish();let data;try{data=JSON.parse(request.responseText);}catch{reject(new Error("업로드 응답을 읽을 수 없습니다."));return;}
      if(request.status>=200&&request.status<300){$("upload-progress").value=100;$("upload-label").textContent=`원본 업로드 완료 · ${fileSize(state.file.size)}`;resolve(data);}
      else reject(new Error(typeof data.detail==="string"?data.detail:`업로드 실패 (HTTP ${request.status})`));};
    request.onerror=()=>{finish();reject(new Error("원본 업로드 연결이 끊어졌습니다. 다시 실행하면 처음부터 업로드합니다."));};
    request.onabort=()=>{finish();reject(new Error("원본 업로드를 취소했습니다."));};
    request.send(state.file);
  });
}
$("choose-file").onclick=()=>$("audio-file").click();
$("audio-file").onchange=event=>{selectFile(event.target.files[0]);event.target.value="";};
$("drop-zone").ondragover=event=>{event.preventDefault();if(!state.busy)$("drop-zone").classList.add("dragging");};
$("drop-zone").ondragleave=()=>$("drop-zone").classList.remove("dragging");
$("drop-zone").ondrop=event=>{event.preventDefault();$("drop-zone").classList.remove("dragging");if(!state.busy&&!state.recorder)selectFile(event.dataTransfer.files[0]);};
$("record").onclick=async()=>{
  if(state.recorder){state.recorder.stop();return;}
  let stream;
  try{
    if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)throw new Error("이 브라우저는 녹음을 지원하지 않습니다. 음성 파일을 선택해 주세요.");
    stream=await navigator.mediaDevices.getUserMedia({audio:true});
    const recorder=new MediaRecorder(stream);state.recorder=recorder;const chunks=[];let size=0;
    recorder.ondataavailable=e=>{if(e.data.size){chunks.push(e.data);size+=e.data.size;if(size>=79000000&&recorder.state==="recording")recorder.stop();}};
    recorder.onerror=()=>{notice("녹음 중 오류가 발생했습니다. 마이크 연결을 확인해 주세요.","error");if(recorder.state!=="inactive")recorder.stop();};
    const timer=setTimeout(()=>{if(recorder.state==="recording")recorder.stop();},590000);
    recorder.onstop=()=>{clearTimeout(timer);stream.getTracks().forEach(t=>t.stop());state.recorder=null;$("record").classList.remove("recording");$("record").textContent="● 직접 녹음";$("run").disabled=false;$("extract-highlights").disabled=false;$("choose-file").disabled=false;
      const blob=new Blob(chunks,{type:recorder.mimeType});selectFile(new File([blob],`recording-${Date.now()}.${recorder.mimeType.includes("mp4")?"m4a":"webm"}`,{type:blob.type}));};
    recorder.start(1000);$("record").textContent="■ 녹음 종료";$("record").classList.add("recording");$("run").disabled=true;$("extract-highlights").disabled=true;$("choose-file").disabled=true;
    notice("녹음 중입니다. 녹음 종료를 누르면 이 음성을 분석할 수 있습니다. 최대 9분 50초에 자동 종료합니다.");
  }catch(error){if(stream)stream.getTracks().forEach(t=>t.stop());notice(error.message||"마이크 권한을 확인해 주세요.","error");}
};
function busy(value){
  state.busy=value;document.querySelectorAll(".input-body button,.input-body input,.input-body select,.clip-card button").forEach(n=>{n.disabled=value;});
  $("connection-button").disabled=value;$("cancel").disabled=value&&!state.active;$("cancel").hidden=!value;$("run-label").textContent=value?"선택한 모델로 분석 중…":"선택한 모델로 음성 분석";
  document.querySelectorAll("[data-tab]").forEach(button=>{button.disabled=value;});
  if(value)$("export").disabled=true;
  $("extract-label").textContent=value?"작업 진행 중…":"원본에서 하이라이트 추출";
  videoMode();
  refreshSettings();
}
function renderProgress(status){
  $("progress").replaceChildren();
  const rows=status?.stages||state.catalog.map(s=>({...s,state:"pending"}));
  const names={pending:"대기",running:"실행 중",complete:"완료",failed:"실패",skipped:"선택 안 함"};
  rows.forEach(row=>{const item=el("div",`progress-item ${row.state}`);item.dataset.stage=row.id;const percent=status?.stage_progress?.stage===row.id?` · ${status.stage_progress.percent}%`:"";item.append(el("strong","",row.label),el("span","",row.elapsed_ms!=null?seconds(row.elapsed_ms):(names[row.state]||row.state)+percent));$("progress").append(item);});
  if(status?.speaker_review){const review=status.speaker_review;$("progress").append(el("p","hint",`${review.mode==="claude"?"Claude 문맥 검토":"화자 임시 배정"} · ${names[review.state]||review.state}`));}
  if(status?.diagnostic_message)$("progress").append(el("p","hint",status.diagnostic_message));
}
function summary(stage){
  const d=stage.data;
  if(stage.id==="stt"&&d.processed_groups!=null)return `${d.processed_groups}개 구간 분석 · ${d.reused_groups}개 재사용 · ${d.utterances.length}개 발화 · 실패 ${d.failed_segments}개`;
  if(stage.id==="vad")return `${d.spans.length}개 음성 구간 · 발화 ${seconds(d.speechMs)}`;
  if(stage.id==="laughter")return `${d.windows.length}개 분석 창 · 웃음 이벤트 ${d.events.length}개 · 해상도 ${d.resolution}`;
  if(stage.id==="stt")return `${d.utterances.length}개 발화 · ${d.text.length}자 · API 호출 ${d.api_calls}회`;
  if(stage.id==="diarization")return `화자 ${d.speaker_count}명 · ${d.segments.length}개 구간`;
  if(stage.id==="voice")return `${d.signatures.length}명 벡터 추출 · ${d.signatures[0]?.dimensions||"—"}차원`;
  return `주제 ${d.topics.length}개 · 하이라이트 ${d.clip_count}개`;
}
function fileLink(run,name,label){const a=el("a","",label);a.href=`/api/audio/jobs/${run.id}/files/${encodeURIComponent(name)}`;a.download=name;return a;}
function laughterResult(run){
  if(run.config.pipeline==="segments")return `감도 ${sensitivityName(run.config.threshold)} · 적용 기준 ${run.config.threshold.toFixed(3)} · 캐릭터별 웃음 ${run.characters?.laughter_events.length||0}개 · 화자 미확정 구간 ${run.characters?.unresolved_segments||0}개`;
  const stage=run.stages?.laughter;if(!stage)return "웃음 감지 실행 안 함";
  if(stage.data.policy==="app-backend-v1"){
    const recheck=run.stages.diarization?.data.laughter_recheck;
    return `앱과 동일한 웃음 판정 · 검출 ${stage.data.events.length}개${recheck?` · 웃는 사람 확정 ${seconds(recheck.assigned_ms)} / 미확정 ${seconds(recheck.unknown_ms)}`:""}`;
  }
  const threshold=run.config.threshold,peak=Math.max(0,...stage.data.windows.map(w=>w.laughter));
  return `감도 ${sensitivityName(threshold)} · 적용 기준 ${threshold.toFixed(3)} · 최고 웃음 점수 ${peak.toFixed(4)} · 검출 ${stage.data.events.length}개`;
}
function renderOverview(run,root){
  state.catalog.forEach(s=>{
    const stage=run.stages[s.id];if(!stage)return;
    const card=el("article","stage-summary");card.append(el("h4","",`${s.label} · ${seconds(stage.elapsed_ms)}`),el("span","model-id",stage.model),el("p","",summary(stage)));
    if(stage.data.execution)card.append(el("p","execution-runtime",`실행 장치 · ${executionLabel(stage.data.execution)}`));
    if(s.id==="laughter"){
      card.append(el("p","laughter-result",laughterResult(run)));
      if(!stage.data.events.length)card.append(el("p","hint",run.config.pipeline==="segments"?"웃음이 검출되지 않았습니다. 캐릭터·구간 탭에서 원본과 분석 구간을 듣고 확인해 주세요.":"웃음이 검출되지 않았습니다. 모델별 실험에서 감도를 조절해 비교할 수 있습니다."));
    }
    if(stage.data.note)card.append(el("p","hint",stage.data.note));
    const details=el("details");details.append(el("summary","","상세 출력 보기"),el("pre","",JSON.stringify(stage.data,null,2)));card.append(details);root.append(card);
  });
}
function renderTranscript(run,root){
  const links=el("div","download-links");(run.artifacts||[]).filter(n=>n.startsWith("transcript.")).forEach(n=>links.append(fileLink(run,n,n)));root.append(links);
  const rows=run.characters?.captions||run.utterances||[];
  if(!rows.length){root.append(el("p","result-note","전사가 없거나 음성 인식 단계를 실행하지 않았습니다."));}
  for(const u of rows){const row=el("article","subtitle-row");row.append(el("div","times",`${clock(u.startMs)}\n${clock(u.endMs)}`));const text=el("div","text");(u.speakers||[]).forEach(s=>text.append(el("span","speaker-tag",s)));if(u.requires_review)text.append(el("span","assignment-badge",u.speakers?.length?"추정 · 확인 필요":"화자 미확정"));text.append(el("p","",u.text));row.append(text);root.append(row);}
  const diar=run.stages.diarization;
  if(diar){const detail=el("details","stage-summary");detail.append(el("summary","","화자 구간 전체 보기"),el("pre","",JSON.stringify(diar.data.segments,null,2)));root.append(detail);}
}
function executionLabel(info){
  if(!info)return "기록 없음";
  if(info.device==="api")return "OpenAI API";
  if(info.device==="not-run")return "음성 구간 없음 · 모델 실행 안 함";
  if(info.device==="cpu")return "CPU";
  return `GPU · ${info.device==="cuda"?"CUDA":info.backend||info.device}${info.name?" · "+info.name:""}${info.compute_type?" · "+info.compute_type:""}`;
}
function renderClips(run,root){
  root.append(el("h3","compare-title",`원본 하이라이트 · ${run.partial&&!run.clips?.length?"생성 중단":`${(run.clips||[]).length}개`}`));
  const runtimes=["stt","topics"].filter(key=>run.stages[key]?.data.execution).map(key=>`${key==="stt"?"STT":"주제 분석"}: ${executionLabel(run.stages[key].data.execution)}`);
  if(runtimes.length)root.append(el("p","hint execution-runtime",runtimes.join(" / ")));
  if(run.stages.laughter)root.append(el("p","laughter-result",laughterResult(run)));
  if(!(run.clips||[]).length){root.append(el("p","result-note",run.partial?"분석이 중단되어 하이라이트를 만들지 못했습니다. 위의 실패 단계와 오류 내용을 확인해 주세요.":run.stages.topics?"현재 기준에 맞는 하이라이트가 없습니다. 웃음이 감지되고 전사에서 대화 구간이 확인되어야 추출됩니다. ‘기능별 결과’와 아래 주제 목록을 확인하고 감도·모델을 조절해 다시 실행하세요.":"왼쪽 ‘원본에서 하이라이트 추출’을 실행하면 선택된 구간을 여기서 재생하고 내려받을 수 있습니다."));}
  for(const clip of run.clips||[]){
    const card=el("article","topic-card clip-card");card.append(el("h4","",clip.title),el("p","card-reason",clip.reason),el("span","range",`${clock(clip.startMs)} – ${clock(clip.endMs)} · ${seconds(clip.durationMs)}`));
    if(clip.audio_file&&!run.partial){
      const player=el(clip.source_video_file?"video":"audio");player.controls=true;player.preload="metadata";
      player.src=`/api/audio/jobs/${run.id}/files/${clip.source_video_file||clip.audio_file}`;
      if(clip.source_video_file){player.playsInline=true;player.setAttribute("aria-label",`${clip.title} 원본 영상`);}
      card.append(player);const links=el("div","download-links");
      if(clip.source_video_file)links.append(fileLink(run,clip.source_video_file,"원본 MP4 다운로드"));
      links.append(fileLink(run,clip.audio_file,"M4A 음성 다운로드"));
      if(clip.subtitle_file)links.append(fileLink(run,clip.subtitle_file,"SRT 자막 다운로드"));
      card.append(links);
      if(clip.durationMs<=state.maxVideoSeconds*1000){
        const use=el("button","secondary use-clip","이 클립으로 영상 만들기");use.disabled=state.busy;
        use.onclick=async()=>{if(state.busy||state.recorder)return;try{
          busy(true);const response=await fetch(`/api/audio/jobs/${run.id}/files/${clip.audio_file}`);
          if(!response.ok)throw new Error("클립 음성을 불러올 수 없습니다.");
          selectFile(new File([await response.blob()],`${clip.title}.m4a`,{type:"audio/mp4"}));
          $("make-video").checked=true;$("mode").value="all";selectResultTab("video");render();
          notice("추출한 클립을 입력으로 선택했습니다. 배경·캐릭터·모델을 정하고 ‘분석부터 영상 만들기’를 누르세요.");
          $("scene-settings").scrollIntoView({behavior:"smooth",block:"start"});
        }catch(error){notice(error.message,"error");}finally{busy(false);}};
        card.append(use);
      }else card.append(el("p","hint","이 클립은 10분을 넘습니다. 원본 파일 다운로드는 가능하며, 캐릭터 영상 제작은 10분 이하 클립을 사용하세요."));
    }
    root.append(card);
  }
  if(run.topics?.length){root.append(el("h3","compare-title","전체 주제"));run.topics.forEach(p=>{const item=el("article","topic-card");item.append(el("h4","",p.title),el("p","card-reason",p.reason),el("span","range",`${clock(p.startMs)} – ${clock(p.endMs)}`));root.append(item);});}
}
function renderCompare(root){
  if(state.runs.length<2){root.append(el("p","result-note","같은 음성으로 모델을 바꿔 두 번 이상 실행하면 비교할 수 있습니다."));return;}
  const controls=el("div","compare-controls"),body=el("div"),warning=el("p","compare-warning");
  const selectors=[0,1].map((_,i)=>{const wrap=el("div"),label=el("label","",i?"실험 B":"실험 A"),select=el("select");select.id=`compare-${i}`;label.htmlFor=select.id;state.runs.forEach((r,j)=>select.add(new Option(`${state.runs.length-j}. ${r.input.name} · ${r.requested_mode}`,r.id)));select.value=state.runs[i?0:1].id;wrap.append(label,select);controls.append(wrap);return select;});
  const update=()=>{
    const runs=selectors.map(s=>state.runs.find(r=>r.id===s.value));const same=runs[0].input.sha256===runs[1].input.sha256;
    const settingsDiffer=["pipeline","mode","threshold","max_clips","speaker_count","max_output_tokens","qwen_thinking"].some(key=>runs[0].config[key]!==runs[1].config[key]);
    warning.hidden=same&&!settingsDiffer&&runs[0].id!==runs[1].id;
    warning.textContent=runs[0].id===runs[1].id?"같은 실험을 선택했습니다.":!same?"서로 다른 음성 파일입니다. 입력 차이도 결과에 영향을 줍니다.":"음성은 같지만 실행 범위 또는 분석 설정이 다릅니다. 결과 JSON에서 설정을 함께 확인하세요.";
    body.replaceChildren();const table=el("table","audio-compare"),head=el("tr");["기능","실험 A","실험 B"].forEach(t=>head.append(el("th","",t)));const thead=el("thead");thead.append(head);table.append(thead);const tbody=el("tbody");
    for(const stage of state.catalog){const row=el("tr",runs[0].config[stage.id]!==runs[1].config[stage.id]?"compare-stage-diff":"");row.append(el("td","",stage.label));for(const run of runs){const s=run.stages[stage.id];const cell=el("td");cell.append(el("strong","",run.config[stage.id]),el("p","hint",s?`${seconds(s.elapsed_ms)} · ${summary(s)}`:"실행 안 함"));row.append(cell);}tbody.append(row);}
    const sensitivity=el("tr",runs[0].config.threshold!==runs[1].config.threshold?"compare-stage-diff":"");sensitivity.append(el("td","","웃음 감도"),...runs.map(r=>el("td","",laughterResult(r))));tbody.append(sensitivity);
    const total=el("tr");total.append(el("td","","전체 소요 시간"),...runs.map(r=>el("td","",seconds(r.elapsed_ms||0))));tbody.append(total);table.append(tbody);const wrap=el("div","audio-compare");wrap.append(table);body.append(wrap);
    const topics=el("div","compare-grid");runs.forEach((r,i)=>{const column=el("div","compare-column");column.append(el("h3","compare-title",`실험 ${i?"B":"A"} · 주제 경계`));(r.topics||[]).forEach(p=>{const card=el("article","topic-card");card.append(el("h4","",p.title),el("p","range",`${clock(p.startMs)} – ${clock(p.endMs)}`));column.append(card);});topics.append(column);});body.append(topics);compareVideos(runs,body);
  };selectors.forEach(s=>s.onchange=update);root.append(controls,warning,body);update();
}
function render(){
  const run=state.runs.find(r=>r.id===state.selected),root=$("result-content");root.replaceChildren();$("export").disabled=!run;
  if(!state.busy){$("result-state").textContent=run?(run.partial?"ANALYSIS FAILED":run.video?"VIDEO COMPLETE":"ANALYSIS COMPLETE"):"READY TO CREATE";$("result-name").textContent=run?run.input.name:"음성과 소스로 영상을 만들어 보세요.";$("result-time").textContent=run?seconds(run.elapsed_ms||0):"—";renderProgress(run?{stages:run.stage_status||[...(run.executed_stages?run.executed_stages.map(id=>state.catalog.find(s=>s.id===id)):state.catalog).map(s=>run.stages[s.id]||{...s,state:"skipped"}),...(run.video?[{id:"video",label:"영상 제작",state:"complete",elapsed_ms:run.video.render_ms}]:[])]}:null);}
  if(state.tab==="compare")return renderCompare(root);if(!run)return root.append(empty.cloneNode(true));
  root.append(el("p","hint",run.config?.pipeline==="segments"?"캐릭터별 구간 분석 · 독립 자막·웃음·입 움직임":run.config?.pipeline==="app"?"앱과 동일 · 웃음·화자·자막 처리":"모델별 실험 · 앱 기본 분석과 다를 수 있음"));
  if(run.analysis_incomplete)root.append(el("p","partial-banner","일부 구간 분석이 실패했습니다. 캐릭터·구간 탭에서 해당 구간을 재분석하세요."));
  if(run.speaker_review?.failures?.length&&state.tab!=="characters")root.append(el("p","partial-banner","Claude 화자 검토가 일부 또는 전부 완료되지 않았습니다. 임시 배정 결과를 유지했습니다. ‘캐릭터·구간’ 탭에서 이유를 확인하고 다시 검토하세요."));
  if(run.config?.pipeline==="segments"&&(run.partial||run.analysis_incomplete)&&run.stages.voice)resumeSegmentButton(run,root);
  if(run.partial)root.append(el("p","partial-banner",`분석 중단: ${run.error}`));
  if(state.tab==="characters")renderCharacters(run,root);else if(state.tab==="video")renderVideo(run,root);else if(state.tab==="overview")renderOverview(run,root);else if(state.tab==="transcript")renderTranscript(run,root);else renderClips(run,root);
}
function selectResultTab(tab){state.tab=tab;document.querySelectorAll("[data-tab]").forEach(b=>{b.classList.toggle("active",b.dataset.tab===tab);b.setAttribute("aria-selected",String(b.dataset.tab===tab));});}
document.querySelectorAll("[data-tab]").forEach(button=>{button.onclick=()=>{selectResultTab(button.dataset.tab);render();};});
function history(){
  $("history-count").textContent=String(state.runs.length);$("history").replaceChildren();
  if(!state.runs.length){$("history").append(el("p","history-empty","실행 기록은 서버 종료까지 보관합니다. 결과를 내려받아 비교 자료로 남기세요."));return;}
  const wrap=el("div","history-list"),table=el("table"),tbody=el("tbody");
  state.runs.forEach(r=>{const row=el("tr",r.id===state.selected?"selected-row":""),cell=el("td"),button=el("button","history-select",r.input.name);button.disabled=state.busy;button.onclick=()=>{state.selected=r.id;render();history();};cell.append(button);row.append(cell,el("td","",r.requested_mode),el("td","",r.partial?"실패":seconds(r.elapsed_ms)),el("td","",r.partial&&!r.clips?.length?"생성 안 됨":`${r.clips?.length||0}개 클립`),el("td","",new Date(r.created_at).toLocaleTimeString("ko-KR")));tbody.append(row);});table.append(tbody);wrap.append(table);$("history").append(wrap);
}
function remember(result){state.runs=state.runs.filter(r=>r.id!==result.id);state.runs.unshift(result);state.runs=state.runs.slice(0,30);state.selected=result.id;}
async function poll(identity){
  if(state.active!==identity)return;
  try{
    const job=await api(`/api/audio/jobs/${identity}`);renderProgress(job);if(job.video_progress){const v=job.video_progress;$("run-label").textContent=`MP4 ${v.video}/${v.count} (${v.percent}%)`;}$("result-time").textContent=seconds(job.elapsed_ms||0);
    $("result-state").textContent=job.state.toUpperCase();$("result-name").textContent=job.stage==="video"?`MP4 ${job.video_progress?.percent||0}%`:job.stage==="packaging"?"ZIP":job.stage==="decoding"?"오디오를 읽는 중…":job.stage==="exporting"?"원본 하이라이트 파일 저장 중…":`${state.catalog.find(s=>s.id===job.stage)?.label||"모델 준비"} ${job.state==="running"?"실행 중…":""}`;
    if(job.state==="running"){setTimeout(()=>poll(identity),2000);return;}
    if(job.result)remember(job.result);state.active=null;busy(false);render();history();
    notice(job.state==="complete"&&job.result.video?`MP4 ${job.result.video.items.length} / ${seconds(job.result.video.render_ms)}`:job.state==="complete"?`분석 완료 · 원본 하이라이트 ${job.result.clips.length}개${job.result.clips.some(c=>c.source_video_file)?" (MP4 · M4A · SRT)":""}`:job.error||"분석이 종료되었습니다.",job.state==="complete"?"success":"error");
  }catch(error){notice(`${error.message} 진행 중인 작업을 다시 조회합니다.`,"error");setTimeout(()=>poll(identity),5000);}
}
async function startAnalysis(highlightsOnly=false){
  if(state.busy||state.recorder)return;try{
    if(!state.file)throw new Error("원본 음성·영상 파일을 선택하거나 직접 녹음해 주세요.");
    if(state.fileDuration>state.maxSeconds)throw new Error(`${state.maxSeconds/3600}시간 이하 원본 파일을 선택해 주세요.`);
    if($("pipeline-mode").value==="segments"&&$("speaker-review-mode").value==="claude"&&!state.claudeConfigured){openClaudeConnection();return;}
    const config=modelSettings(highlightsOnly?"topics":$("mode").value);if(activeStages(config.mode).some(s=>config[s].startsWith("openai:"))&&!state.configured){openConnection();return;}
    const scene=highlightsOnly?null:sceneSettings();
    if(scene&&state.fileDuration>state.maxVideoSeconds)throw new Error("긴 원본은 ‘원본에서 하이라이트 추출’을 먼저 실행하고, 결과에서 10분 이하 클립을 선택해 주세요.");
    busy(true);notice("원본을 업로드하고 모델을 준비합니다. 긴 파일은 단계별 진행률을 확인하세요. 로컬 분석 시간과 API 사용량은 녹음 길이에 따라 늘어납니다.");
    if(!state.upload)state.upload=await uploadOriginal();
    const job=await api("/api/audio/jobs",{method:"POST",body:JSON.stringify({upload_id:state.upload.upload_id,config,scene})});
    selectResultTab(scene?"video":config.mode==="topics"?"clips":"overview");
    state.active=job.id;$("cancel").disabled=false;$("result-content").replaceChildren(el("p","result-note","선택한 모델들이 실제 음성을 분석하고 있습니다. 위의 단계별 진행 상황을 확인하세요."));$("source-note").textContent="로컬 서버 업로드 완료 · 같은 파일은 다시 업로드하지 않습니다.";history();poll(job.id);
  }catch(error){busy(false);notice(error.message,"error");}
}
$("run").onclick=()=>startAnalysis();
$("extract-highlights").onclick=()=>startAnalysis(true);
$("cancel").onclick=async()=>{if(state.uploadRequest){state.uploadRequest.abort();return;}if(!state.active)return;$("cancel").disabled=true;try{await api(`/api/audio/jobs/${state.active}`,{method:"DELETE"});}catch(error){notice(error.message,"error");$("cancel").disabled=false;}};
$("export").onclick=()=>{
  const run=state.runs.find(r=>r.id===state.selected);if(!run)return;
  const url=URL.createObjectURL(new Blob([JSON.stringify(run,null,2)],{type:"application/json;charset=utf-8"})),link=el("a");link.href=url;link.download=`audio-lab-${run.id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
(async()=>{try{
  await session();buildModels(await api("/api/audio/catalog"));
  const data=await api("/api/audio/jobs");for(const job of data.jobs)if(job.result)remember(job.result);
  const active=data.jobs.find(j=>j.state==="running");await initializeVideo(!active);
  if((active?.requested_mode||state.runs[0]?.requested_mode)==="topics")selectResultTab("clips");
  render();history();if(active){state.active=active.id;busy(true);poll(active.id);}
  if(state.configured)models().catch(error=>notice(error.message,"error"));
}catch(error){notice(error.message,"error");}})();
