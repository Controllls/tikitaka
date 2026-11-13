"use strict";
// One review player, a sparse lane per character, and bounded segment lists for long recordings.
const characterTab=el("button","tab","캐릭터·구간");
characterTab.dataset.tab="characters";characterTab.setAttribute("role","tab");characterTab.setAttribute("aria-selected","false");
characterTab.onclick=()=>{selectResultTab("characters");render();};
document.querySelector(".audio-tabs").append(characterTab);

function characterName(run,speaker){
  const people=run.video?.scene?.people||readPeople();
  const person=people.find(p=>p.speaker===speaker);
  return speaker?`${speaker}${person?` · ${person.name}`:""}`:"화자 미확정";
}

async function segmentAction(run,segment,rerun,speaker){
  if(state.busy)return;
  try{
    if(rerun&&run.config.topics.startsWith("openai:")&&!state.configured){openConnection();return;}
    if(rerun&&run.config.speaker_review?.mode==="claude"&&!state.claudeConfigured){openClaudeConnection();return;}
    busy(true);notice(rerun?"선택한 시간 구간을 다시 분석합니다. 함께 분리된 소리와 하이라이트도 다시 계산합니다.":"화자 수정을 새 기록으로 저장합니다. 음성 모델은 다시 실행하지 않습니다.");
    const job=await api("/api/audio/jobs",{method:"POST",body:JSON.stringify({reuse_job_id:run.id,segment_action:{segment_id:segment.id,rerun,speaker_id:speaker||null}})});
    state.active=job.id;$("cancel").disabled=false;selectResultTab("characters");history();poll(job.id);
  }catch(error){busy(false);notice(error.message,"error");render();}
}

function renderCharacters(run,root){
  const data=run.characters;
  if(!data){root.append(el("p","result-note","‘캐릭터별 구간 분석’으로 실행하면 화자별 타임라인과 구간 듣기가 표시됩니다."));return;}
  root.append(el("h3","",`캐릭터 ${data.speakers.length}명 · 분석 구간 ${data.groups.length}개`));
  renderSpeakerReview(run,root);
  root.append(el("p","hint",`중복 대사 정리 ${data.duplicate_dialogue?.length||0}개 · 불확실한 동시 발화 정리 ${data.speech_conflicts?.length||0}개 · 질문·말 걸기 시선 전환 ${data.interactions?.length||0}개. 서로 다른 목소리가 확인되지 않은 겹친 대사는 한 캐릭터만 말합니다. 웃음은 별도로 재생합니다.`));
  if(data.speech_conflicts?.length){
    const box=el("details","speech-conflicts");box.append(el("summary","","동시 발화 정리 내용 보기"));
    for(const item of data.speech_conflicts){box.append(el("p","hint",`${clock(item.startMs)}–${clock(item.endMs)} · ${item.segment_id} → ${item.kept_segment_id} · ${item.reason}`));}
    root.append(box);
  }
  if(data.duplicate_dialogue?.length){
    const box=el("details","duplicate-dialogue");box.append(el("summary","","중복 대사 처리 내용 보기"));
    for(const item of data.duplicate_dialogue){
      const row=el("div","stage-summary");row.append(el("p","hint",`${clock(item.startMs)}–${clock(item.endMs)} · ${item.segment_id} → ${item.kept_segment_id} · ${item.reason}`));
      if(item.kept_text)row.append(el("p","",`남긴 대사: ${item.kept_text}`));
      if(item.removed_text)row.append(el("p","hint",`중복 제외: ${item.removed_text}`));
      box.append(row);
    }
    root.append(box);
  }
  if(data.interactions?.length){const turns=el("details","stage-summary");turns.append(el("summary","","질문·말 걸기 판단 보기"));for(const turn of data.interactions.slice(0,200)){turns.append(el("p","hint",`${clock(turn.startMs)}–${clock(turn.endMs)} · ${characterName(run,turn.speaker)} → ${characterName(run,turn.addressee)} · ${turn.intent==="question"?"질문":"말 걸기"} · ${turn.source==="claude"?"Claude 판단":"문장 규칙 추정"}`));}root.append(turns);}
  root.append(el("p","hint",`화자 미확정 구간 ${data.unresolved_segments}개 · 웃음 추정 배정 ${data.provisional_laughter_events||0}건 · 웃는 사람 미확정 ${data.unresolved_laughter_events||0}건 · 실패 ${data.failed_segments}개. 초록색은 발화, 주황색은 웃음이 있는 구간입니다. 구간을 누르면 아래에서 확인할 수 있습니다.`));
  const downloads=el("div","download-links");downloads.append(fileLink(run,"character-result.json","구간·자막 JSON ↓"),fileLink(run,"character-tracks.json","캐릭터 동작 트랙 ↓"));root.append(downloads);
  const player=el("audio","segment-player");player.controls=true;player.preload="none";
  const playerLabel=el("p","hint","구간의 ‘음성 듣기’ 또는 ‘원본 비교’를 선택하세요.");
  const listen=(segment,original)=>{player.pause();player.src=`/api/audio/jobs/${run.id}/segments/${segment.id}/audio?original=${original}`;playerLabel.textContent=`${characterName(run,segment.speaker)} · ${clock(segment.startMs)}–${clock(segment.endMs)} · ${original?"원본 혼합 음성":segment.separated?"분리 음성":"원본 구간"}`;player.play().catch(()=>notice("재생 버튼을 눌러 음성을 확인하세요."));};
  const toolbar=el("div","segment-toolbar"),filter=el("select"),search=el("input"),issues=el("input"),issueLabel=el("label","thinking-option");
  filter.setAttribute("aria-label","화자 필터");filter.add(new Option("모든 캐릭터","all"));
  data.speakers.forEach(s=>filter.add(new Option(characterName(run,s.speaker_id),s.speaker_id)));filter.add(new Option("화자 미확정","unknown"));
  search.type="search";search.placeholder="자막에서 찾기";search.setAttribute("aria-label","구간 자막 검색");
  issues.type="checkbox";issueLabel.append(issues,document.createTextNode("확인 필요한 구간만"));toolbar.append(filter,search,issueLabel);
  const timeline=el("div","character-timeline");
  const list=el("div","segment-list"),pagination=el("div","segment-pagination");let page=0,focus=null;
  const laughingSegments=new Set(data.laughter_events.map(e=>e.segment_id));
  const reasonLabels={separated_voice_unconfirmed:"분리 음성의 화자 근거 부족",speaker_unconfirmed:"화자 근거 부족",speaker_evidence_conflict:"화자 판단 근거 충돌",speaker_turn_boundary:"화자가 바뀌는 경계 구간",separation_leakage:"분리 소리 중복",more_than_two_sources:"세 명 이상 겹침",separation_disabled:"겹친 음성 분리 꺼짐",manual_unknown:"사용자가 미확정으로 지정",segment_failed:"분석 실패"};
  for(const speaker of [...data.speakers.map(s=>s.speaker_id),null]){
    const rows=data.segments.filter(s=>s.speaker===speaker);if(!rows.length)continue;
    const lane=el("div","character-lane"),title=el("button","text-button",characterName(run,speaker)),track=el("div","character-track");
    title.onclick=()=>{filter.value=speaker||"unknown";page=0;draw();};
    for(const segment of rows){const laughing=laughingSegments.has(segment.id);const marker=el("button",`segment-marker${laughing?" has-laugh":""}${segment.state==="failed"?" failed":""}`);marker.style.left=`${segment.startMs/data.durationMs*100}%`;marker.style.width=`${Math.max(.3,(segment.endMs-segment.startMs)/data.durationMs*100)}%`;marker.title=`${clock(segment.startMs)} ${characterName(run,speaker)}`;marker.setAttribute("aria-label",marker.title);marker.onclick=()=>{filter.value=speaker||"unknown";search.value="";issues.checked=false;page=Math.floor(rows.indexOf(segment)/20);focus=segment.id;draw();};track.append(marker);}
    lane.append(title,track);timeline.append(lane);
  }
  root.append(timeline,playerLabel,player,toolbar,list,pagination);
  const provisionalLaughs=data.laughter_events.filter(e=>e.assignment_source==="laughter_provisional");
  const originalLaughs=data.laughter_events.filter(e=>e.origin==="original"&&e.assignment_source!=="laughter_provisional");
  if(provisionalLaughs.length){
    const box=el("details","provisional-laughs");
    const listenerPolicy=data.laughter_assignment_policy==="laughter-listener-random-v2";
    box.append(el("summary","",`웃음 추정 배정 ${provisionalLaughs.length}건`),el("p","hint",listenerPolicy?"미정 웃음은 말하지 않는 캐릭터 중 무작위로 배정합니다. 직전 2초에 말한 사람도 가급적 피하고, 모두 말하면 겹치는 발화 시간이 가장 짧은 쪽을 고릅니다. 같은 원본·구간·화자 설정으로 영상을 다시 만들면 배정이 유지됩니다. 실제 웃은 사람으로 확인된 결과는 아닙니다.":"이전 규칙으로 배정된 웃음입니다. ‘영상 다시 만들기’를 실행하면 말하지 않는 캐릭터를 우선해 무작위 배정합니다. 실제 웃은 사람으로 확인된 결과는 아닙니다."));
    for(const event of provisionalLaughs){
      const row=el("p","hint",`${clock(event.startMs)}–${clock(event.endMs)} → ${characterName(run,event.speaker)} · ${event.origin==="original"?"원본 웃음":"분리 음성 웃음"} · ${event.assignment_reason}`);
      const segment=data.segments.find(s=>event.segment_id?s.id===event.segment_id:s.group_id===event.source_group_id);
      if(segment){const button=el("button","text-button","원본 구간 듣기");button.onclick=()=>listen(segment,true);row.append(button);}box.append(row);
    }
    root.append(box);
  }
  if(originalLaughs.length){const box=el("details","original-laughs");box.append(el("summary","",`원본에서 보존한 웃음 ${originalLaughs.length}건`),el("p","hint","이전 결과에 남은 미확정 웃음은 ‘영상 다시 만들기’를 실행하면 캐릭터에 추정 배정됩니다."));for(const event of originalLaughs){const row=el("p","hint",`${clock(event.startMs)}–${clock(event.endMs)} `),segment=data.segments.find(s=>s.group_id===event.source_group_id);if(segment){const button=el("button","text-button","원본 구간 듣기");button.onclick=()=>listen(segment,true);row.append(button);}box.append(row);}root.append(box);}
  const texts=new Map(data.segments.map(s=>[s.id,""]));for(const caption of data.captions)texts.set(caption.segment_id,`${texts.get(caption.segment_id)||""} ${caption.text}`.trim());
  function draw(){
    player.pause();list.replaceChildren();pagination.replaceChildren();
    const rows=data.segments.filter(s=>(filter.value==="all"||(s.speaker||"unknown")===filter.value)&&(!issues.checked||s.requires_review||!s.speaker||s.state==="failed")&&(!search.value||texts.get(s.id).includes(search.value)));
    page=Math.max(0,Math.min(page,Math.ceil(rows.length/20)-1));
    rows.slice(page*20,page*20+20).forEach(segment=>{
      const card=el("article","segment-card");card.dataset.segmentId=segment.id;
      if(segment.id===focus)card.classList.add("focused");
      card.append(el("h4","",characterName(run,segment.speaker)),el("p","times",`${clock(segment.startMs)}–${clock(segment.endMs)} · ${segment.id}`),el("p","segment-text",texts.get(segment.id)||"인식된 대사 없음"));
      const labels={local_provisional:"임시 배정 · 확인 필요",claude_provisional:"Claude 추정 · 확인 필요",manual:"직접 지정",acoustic:"음성 분석 배정"};
      if(segment.assignment_source)card.append(el("span","assignment-badge",labels[segment.assignment_source]||"미확정"));
      if(segment.assignment_reason)card.append(el("p","hint",segment.assignment_reason));
      if(segment.duplicate_utterance_indices?.length)card.append(el("p","hint",`중복 검출된 대사 ${segment.duplicate_utterance_indices.length}개를 자막·입 움직임에서 제외했습니다. 원본 분석과 웃음 트랙은 보존합니다.`));
      if(segment.claude_review)card.append(el("p","hint",`검토 모델 ${segment.claude_review.model} · 모델 자체 확신 ${segment.claude_review.confidence} (정확도 수치가 아닙니다)`));
      if(Object.hasOwn(segment,"acoustic_speaker"))card.append(el("p","hint",`원래 음성 판단: ${segment.acoustic_speaker||"미확정"} → 적용: ${segment.speaker||"미확정"}`));
      const info=[segment.separated?"분리 음성":"원본 구간",segment.manually_reviewed?"화자 직접 수정":null,...(segment.review_reasons||[]).map(r=>reasonLabels[r]||r)].filter(Boolean);
      card.append(el("p","hint",info.join(" · ")));if(segment.error)card.append(el("p","partial-banner",segment.error));
      const controls=el("div","segment-actions"),play=el("button","secondary","음성 듣기"),original=el("button","secondary","원본 비교"),speaker=el("select"),save=el("button","secondary segment-save","화자 저장"),retry=el("button","secondary segment-retry","이 구간 재분석");
      play.onclick=()=>listen(segment,false);original.onclick=()=>listen(segment,true);
      speaker.setAttribute("aria-label",`${segment.id} 화자 지정`);speaker.add(new Option("미확정",""));data.speakers.forEach(s=>speaker.add(new Option(characterName(run,s.speaker_id),s.speaker_id)));speaker.value=segment.speaker||"";
      save.onclick=()=>segmentAction(run,segment,false,speaker.value);retry.onclick=()=>segmentAction(run,segment,true,null);
      controls.append(play,original,speaker,save,retry);controls.querySelectorAll("button,select").forEach(e=>e.disabled=state.busy);save.disabled=retry.disabled=state.busy||!!run.partial;card.append(controls);list.append(card);
    });
    const previous=el("button","secondary","이전"),next=el("button","secondary","다음");previous.disabled=page===0;next.disabled=(page+1)*20>=rows.length;
    previous.onclick=()=>{page--;draw();};next.onclick=()=>{page++;draw();};pagination.append(previous,el("span","hint",`${rows.length}개 중 ${rows.length?page*20+1:0}–${Math.min(rows.length,(page+1)*20)}`),next);
    if(!rows.length)list.append(el("p","result-note","조건에 맞는 구간이 없습니다."));
  }
  for(const input of [filter,search,issues])input.oninput=()=>{page=0;focus=null;draw();};
  draw();
  root.append(el("p","hint","화자를 수정하면 새 기록에 저장됩니다. ‘완성 영상’ 탭의 영상 다시 만들기로 수정 결과를 반영하세요. 구간 재분석은 같은 시간에 분리된 소리를 함께 처리하며, 선택한 주제 모델도 다시 실행합니다."));
}

function resumeSegmentButton(run,root){
  const button=el("button","primary resume-segments","저장된 구간부터 다시 시도");button.disabled=state.busy;
  button.onclick=async()=>{
    if(state.busy)return;
    try{
      if(run.config.topics.startsWith("openai:")&&!state.configured){openConnection();return;}
      if(run.config.speaker_review?.mode==="claude"&&!state.claudeConfigured){openClaudeConnection();return;}
      const scene=run.requested_mode==="all"?sceneSettings():null;
      busy(true);const job=await api("/api/audio/jobs",{method:"POST",body:JSON.stringify({reuse_job_id:run.id,resume_segments:true,scene})});
      state.active=job.id;$("cancel").disabled=false;notice("완료된 구간은 재사용하고 실패·미완료 구간부터 이어서 처리합니다.");poll(job.id);
    }catch(error){busy(false);notice(error.message,"error");}
  };
  root.append(button);
}
