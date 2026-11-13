/* All decisions use source-audio time. Preview, seeking and export share this
   deterministic event/envelope track; no wall clock, random mouth or RMS bypass. */
var AudioEvents=(()=>{
  const version=5,clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
  const quantile=(xs,q)=>{const a=xs.slice().sort((a,b)=>a-b);return a[Math.floor((a.length-1)*q)]||0};
  const at=(events,time)=>events.find(x=>time>=x.start&&time<x.end)||null;
  const unique=xs=>[...new Set(xs.filter(x=>typeof x==='string'&&x))];
  const voices=event=>unique(Array.isArray(event?.speakers)?event.speakers:event?.speaker?[event.speaker]:[]);
  const manualPeople=event=>Array.isArray(event?.personOverrides)?unique(event.personOverrides):event?.personOverride?[event.personOverride]:null;
  function people(event,analysis){return event&&['speech','laughter'].includes(event.kind)?manualPeople(event)??unique(voices(event).map(id=>analysis?.mapping?.[id])):[]}
  function voiceTrack(acoustic,id){const track=acoustic?.speakerEnvelopes?.[id];return track&&Number.isFinite(track.step)&&track.step>0&&((Array.isArray(track.voice)&&track.voice.length)||(Array.isArray(track.activity)&&track.activity.length))?track:null}
  function needsReview(event,analysis){
    if(!event||!['speech','laughter'].includes(event.kind))return false;
    const manual=manualPeople(event);if(manual!==null)return !manual.length;
    const ids=voices(event);return !ids.length||ids.some(id=>!analysis?.mapping?.[id])||!!event.identityReview||!!event.laughterReview||(ids.length>1&&ids.some(id=>!voiceTrack(analysis?.acoustic,id)));
  }
  function score(frames,time,key){
    let value=0,weight=0;
    const hop=frames.length>1?frames[1].start-frames[0].start:.16,center=Math.floor((time-.4875)/hop),radius=Math.ceil(.7/hop);
    for(let i=Math.max(0,center-radius);i<Math.min(frames.length,center+radius+2);i++){const f=frames[i],distance=Math.abs(time-(f.start+.4875));if(distance>.7)continue;const w=Math.max(0,1-distance/.7);value+=(f[key]||0)*w;weight+=w}
    return weight?value/weight:0;
  }
  function laughterTrack(frames){
    // Use temporal support, not a broad average that erases short chuckles.
    // Coughs, crying, breathing and impacts compete with laughter explicitly.
    const eligible=frames.map(f=>f.laughter>=.05&&f.laughter>=(f.otherVocal||0)*1.15&&f.laughter>=(f.nonVocal||0)*.9);
    const values=frames.map(()=>0);
    for(let start=0;start<frames.length;){
      if(!eligible[start]){start++;continue}
      let end=start+1;while(end<frames.length&&eligible[end])end++;
      const group=frames.slice(start,end),peak=Math.max(...group.map(f=>f.laughter));
      const supported=peak>=.24||(peak>=.12&&group.length>=2)||(group.filter(f=>f.laughter>=.085).length>=3);
      if(supported)for(let i=start;i<end;i++)values[i]=frames[i].laughter;
      start=end;
    }
    // A second, speech-band view may recover quiet laughter under rumble.
    // Require sustained evidence in BOTH views and weak competing sound scores;
    // never lower the ordinary laughter threshold for every environmental sound.
    const quiet=frames.map(f=>{const b=f.band;return b&&f.laughter>=.006&&b.laughter>=.05&&b.laughter>=f.laughter*1.4&&f.otherVocal<.03&&b.otherVocal<.03&&f.nonVocal<.1&&b.nonVocal<.1});
    for(let start=0;start<frames.length;){
      if(!quiet[start]){start++;continue}let end=start+1;while(end<frames.length&&quiet[end])end++;
      const group=frames.slice(start,end),bands=group.map(f=>f.band.laughter);
      if(group.length>=3&&Math.max(...group.map(f=>f.laughter))>=.025&&Math.max(...bands)>=.08&&bands.reduce((a,b)=>a+b,0)/bands.length>=.065)
        for(let i=start;i<end;i++)values[i]=Math.max(values[i],frames[i].band.laughter);
      start=end;
    }
    return values;
  }
  function laughterAt(frames,values,time){
    if(!frames.length)return 0;
    const hop=frames.length>1?frames[1].start-frames[0].start:.16;
    const position=clamp((time-frames[0].start-.4875)/hop,0,frames.length-1),i=Math.floor(position),mix=position-i;
    return values[i]*(1-mix)+(values[Math.min(i+1,values.length-1)]||0)*mix;
  }
  function classify(sound,duration){
    const {step,rms,voice}=sound.envelope,noise=Math.max(.00012,quantile(rms,.1)),track=[],laughs=laughterTrack(sound.frames);
    for(let i=0;i<rms.length;i++){
      const time=(i+.5)*step,speech=score(sound.frames,time,'speech'),laugh=laughterAt(sound.frames,laughs,time);
      const audible=rms[i]>Math.max(.00018,Math.min(.002,noise*.75));
      const vi=Math.floor(time/(sound.vad?.step||.032)),prob=sound.vad?Math.max(...sound.vad.probabilities.slice(Math.max(0,vi-1),vi+2)):1;
      const vocal=prob>=.45;
      const kind=!audible?'silence':laugh>=.05?'laughter':vocal&&speech>=.18?'speech':'environment';
      track.push({kind,speech,laugh,vad:prob,energy:voice[i],rms:rms[i]});
    }
    // A brief low-energy pause must close the mouth, but need not fragment captions.
    for(let i=1;i<track.length-1;i++)if(track[i].kind==='silence'&&track[i-1].kind===track[i+1].kind&&track[i-1].kind!=='silence')track[i].kind=track[i-1].kind;
    const events=[];
    for(let i=0;i<track.length;i++){const f=track[i],last=events.at(-1),end=Math.min(duration,(i+1)*step);if(last?.kind===f.kind){last.end=end;last.confidence=Math.max(last.confidence,f.kind==='laughter'?f.laugh:f.speech)}else events.push({start:i*step,end,kind:f.kind,confidence:f.kind==='laughter'?f.laugh:f.speech})}
    // Single-frame classification flicker is not a meaningful sound event.
    for(let i=events.length-2;i>0;i--)if(events[i].end-events[i].start<.1&&events[i-1].kind===events[i+1].kind){events[i-1].end=events[i+1].end;events.splice(i,2)}
    return {version,step,track,events,envelope:sound.envelope,engine:sound.engine};
  }
  function masked(samples,acoustic){
    const out=samples.slice(),step=acoustic.step;
    for(let i=0;i<out.length;i++){const frame=acoustic.track[Math.min(acoustic.track.length-1,Math.floor(i/16000/step))];if(!frame||!['speech','laughter'].includes(frame.kind))out[i]=0}
    return out;
  }
  function compose(acoustic,turns,chunks=[],duration){
    const events=[];
    for(const sound of acoustic.events){
      const reviews=sound.kind==='speech'?(acoustic.identityReview||[]).filter(r=>r.end>sound.start&&r.start<sound.end):[];
      const laughterCuts=sound.kind==='laughter'?(acoustic.speakerHints||[]).flatMap(hint=>[hint.start,hint.end]):[];
      if(sound.kind==='laughter')for(const track of Object.values(acoustic.speakerEnvelopes||{}))if(Array.isArray(track.laughter)&&Number.isFinite(track.step)&&track.step>0){
        laughterCuts.push(track.offset||0,(track.offset||0)+track.laughter.length*track.step);
        for(let i=1;i<track.laughter.length;i++)if((track.laughter[i]>=.5)!==(track.laughter[i-1]>=.5))laughterCuts.push((track.offset||0)+i*track.step);
      }
      const cuts=[...new Set([sound.start,...[...[...turns,...reviews].flatMap(t=>[t.start,t.end]),...laughterCuts].filter(t=>t>sound.start&&t<sound.end),sound.end])].sort((a,b)=>a-b);
      for(let i=0;i<cuts.length-1;i++){
        const start=cuts[i],end=cuts[i+1],mid=(start+end)/2,vocal=['speech','laughter'].includes(sound.kind),currentReviews=reviews.filter(r=>r.start<=mid&&r.end>mid);
        // An explicit empty scope means an additional unidentified source;
        // accepted known voices continue. An omitted scope remains global.
        const review=currentReviews.length===1?currentReviews[0]:currentReviews.length?{start,end,reason:'multiple-uncertain-sources',items:currentReviews,candidates:currentReviews.flatMap(r=>r.candidates||[]),...(currentReviews.every(r=>Array.isArray(r.speakers))?{speakers:unique(currentReviews.flatMap(r=>r.speakers))}:{})}:null;
        const active=vocal?turns.filter(t=>t.start<=mid&&t.end>mid):[];
        const accepted=active.filter(t=>currentReviews.every(r=>Array.isArray(r.speakers)&&!r.speakers.includes(t.speaker)));
        let speakers=unique(accepted.map(t=>t.speaker)),assignments={},identityScores={},laughterReview=null;
        for(const turn of accepted){assignments[turn.speaker]=turn.assignment||'diarization';if(Number.isFinite(turn.identityScore))identityScores[turn.speaker]=turn.identityScore}
        const assign=(id,reason)=>{speakers=id?[id]:[];assignments=id?{[id]:reason}:{}};
        const hint=sound.kind==='laughter'&&at(acoustic.speakerHints||[],mid);
        // Rechecked laughter is aligned using ordinary speech elsewhere. It can
        // disagree with the tail of the preceding spoken turn without moving that turn.
        if(hint?.speaker)assign(hint.speaker,'acoustic-recheck');
        if(sound.kind==='laughter'&&speakers.length>1){
          const activeSpeakers=speakers.slice();
          if(hint?.speaker&&speakers.includes(hint.speaker))assign(hint.speaker,'acoustic-recheck');
          else{
            const evidence=activeSpeakers.map(id=>{const track=voiceTrack(acoustic,id),index=track?Math.floor((mid-(track.offset||0))/track.step):-1;return {id,known:Array.isArray(track?.laughter)&&index>=0&&index<track.laughter.length,laugh:track?.laughter?.[index]>=.5}});
            speakers=evidence.filter(x=>x.known&&x.laugh).map(x=>x.id);assignments=Object.fromEntries(speakers.map(id=>[id,'per-voice-laughter']));
            if(evidence.some(x=>!x.known)||!speakers.length)laughterReview={reason:'overlap-laughter-identity',activeSpeakers};
          }
        }
        if(sound.kind==='speech'&&!speakers.length&&!review){
          // Only an 80ms outside-edge mismatch may borrow an adjacent identity.
          // Internal gaps and explicitly uncertain intervals never inherit it.
          const tolerance=.08,epsilon=.000001;
          if(end-start<=tolerance+epsilon){
            const adjacent=turns.filter(t=>(Math.abs(start-sound.start)<epsilon&&Math.abs(t.start-end)<epsilon&&t.end>end)||(Math.abs(end-sound.end)<epsilon&&Math.abs(t.end-start)<epsilon&&t.start<start));
            const ids=unique(adjacent.map(t=>t.speaker));if(ids.length===1)assign(ids[0],'boundary-extension');
          }
        }
        if(sound.kind==='laughter'&&!speakers.length&&!laughterReview){
          const candidates=turns.filter(t=>t.end>sound.start&&t.start<sound.end),ids=unique(candidates.map(t=>t.speaker));
          const before=turns.filter(t=>t.end<=mid).sort((a,b)=>a.end-b.end).at(-1),after=turns.filter(t=>t.start>mid).sort((a,b)=>a.start-b.start)[0];
          if(ids.length===1)assign(ids[0],'same-sound-segment');
          else if(before&&after&&before.speaker===after.speaker&&after.start-before.end<=2)assign(before.speaker,'same-voice-gap');
          else{const nearest=[before,after].filter(Boolean).sort((a,b)=>Math.min(Math.abs(mid-a.end),Math.abs(mid-a.start))-Math.min(Math.abs(mid-b.end),Math.abs(mid-b.start)))[0];if(nearest&&Math.min(Math.abs(mid-nearest.end),Math.abs(mid-nearest.start))<=.16)assign(nearest.speaker,'boundary-extension')}
        }
        if(sound.kind==='laughter'&&speakers.length===1){
          const track=voiceTrack(acoustic,speakers[0]),index=track?Math.floor((mid-(track.offset||0))/track.step):-1;
          if(track?.activity&&(track.activity[index]||0)<.45&&(track.laughter?.[index]||0)<.5){
            laughterReview={reason:'laughter-identity-not-established',activeSpeakers:speakers.slice()};speakers=[];assignments={};
          }
        }
        speakers.sort();const speaker=speakers.length===1?speakers[0]:null,methods=unique(Object.values(assignments));
        const assignment=(review||laughterReview)&&!speakers.length?'identity-uncertain':methods.length===1?methods[0]:methods.length?'multiple-voice-evidence':null;
        const identityScore=speaker&&Number.isFinite(identityScores[speaker])?identityScores[speaker]:null,overlap=speakers.length>1||active.some(t=>t.overlap);
        const text=sound.kind==='environment'?'(주변소리)':sound.kind==='laughter'?'(웃음)':'',last=events.at(-1);
        const signature=JSON.stringify([speakers,assignments,review,laughterReview,overlap]);
        if(last&&last.kind===sound.kind&&last._signature===signature&&Math.abs(last.end-start)<.001){last.end=end;last.confidence=Math.max(last.confidence,sound.confidence);for(const [id,score]of Object.entries(identityScores))last.identityScores[id]=Number.isFinite(last.identityScores[id])?Math.min(last.identityScores[id],score):score;if(speaker)last.identityScore=last.identityScores[speaker]??null}
        else events.push({id:'sound-'+events.length,start,end,kind:sound.kind,speakers,speaker,assignments,assignment,confidence:+sound.confidence.toFixed(3),identityScores,identityScore,identityReview:review||null,laughterReview,text,overlap,_signature:signature});
      }
    }
    for(const event of events)delete event._signature;
    // ASR is a shared, independently timed caption track, not separated speech.
    const captions=chunks.filter(c=>Array.isArray(c.timestamp)&&Number.isFinite(c.timestamp[0])).map((c,i)=>({id:'caption-'+i,start:Math.max(0,c.timestamp[0]),end:Math.min(duration,c.timestamp[1]??duration),text:String(c.text||'').trim()}));
    for(const event of events)if(event.kind==='speech')event.text=captions.filter(c=>c.end>event.start&&c.start<event.end).map(c=>c.text).join(' ');
    return {events,captions};
  }
  function caption(analysis,time){
    const event=at(analysis?.events||[],time);if(!event)return '';
    if(event.kind==='environment')return '(주변소리)';if(event.kind==='laughter')return '(웃음)';if(event.kind==='silence')return '';
    if(event.editedText)return event.text;
    return (analysis.captions||[]).filter(c=>time>=c.start&&time<c.end).map(c=>c.text).join(' ')||event.text||'';
  }
  function motion(analysis,personId,time){
    const resting={mouth:0,laughing:false,dx:0,dy:0,tilt:0,kind:'rest'};
    const event=at(analysis?.events||[],time);
    if(!event||!people(event,analysis).includes(personId))return resting;
    const acoustic=analysis.acoustic,mixed=acoustic?.envelope;if(!mixed)return resting;
    const ids=voices(event),manual=manualPeople(event)!==null,id=ids.find(id=>analysis.mapping?.[id]===personId);
    const track=!manual&&id?voiceTrack(acoustic,id):null;
    // Simultaneous automatic voices need their own activity or separated signal.
    // A human can explicitly select several people using shared recording timing.
    if(!manual&&ids.length>1&&!track)return resting;
    const activityIndex=track?Math.floor((time-(track.offset||0))/track.step):0;
    const laugh=event.kind==='laughter',recoveredLaugh=laugh&&track?.laughter?.[activityIndex]>=.5;
    // Speech activity is not a laughter detector. A verified laugh has its own
    // identity evidence and uses the original vocal energy, including breathy pauses.
    const env=recoveredLaugh?mixed:track?.voice?track:mixed,index=Math.floor((time-(env.offset||0))/env.step);
    const activity=recoveredLaugh?clamp(track.laughter[activityIndex]):track?.activity?clamp(track.activity[activityIndex]||0):1;
    if(activity<.45)return resting;
    const energy=(env.voice[index]||0)*(env===track?1:activity);
    // Normalize locally, preserving quiet speakers and compressed loud speech.
    const a=Math.max(0,Math.floor((event.start-(env.offset||0))/env.step)),b=Math.min(env.voice.length,Math.ceil((event.end-(env.offset||0))/env.step));
    const reference=env===track?event.energyReferences?.[id]||Math.max(.0002,quantile(env.voice.slice(a,b),.85)):event.energyReference||Math.max(.0002,quantile(env.voice.slice(a,b),.85));
    const level=clamp(energy/reference),local=time-event.start;
    const phase=ids.length>1||manualPeople(event)?.length>1?((Number((id||personId).replace(/\D/g,''))||1)-1)*.37:0;
    const pulse=.5+.5*Math.sin(local*2*Math.PI*(laugh?4.8:4.6)+phase);
    // Even a nearly constant envelope must articulate; pauses always close.
    const gate=level>.12&&energy>.00008,mouth=gate?clamp(level*(.12+.88*pulse)**1.25):0;
    const ramp=clamp(Math.min(local/.12,(event.end-time)/.16)),edge=ramp*ramp*(3-2*ramp),shake=laugh&&gate?edge*(.25+.75*level):0;
    return {mouth,laughing:laugh&&edge>0,laughIntensity:laugh?edge:0,dx:shake*Math.sin(local*27+phase)*1.5,dy:shake*(Math.sin(local*30+phase)*2.6+Math.sin(local*60+phase)*.45),tilt:shake*Math.sin(local*25+phase)*.012,kind:event.kind,source:manual?'manual-mixed-timing':recoveredLaugh?'verified-laughter-mixture':track?.source||'single-voice-mixture'};
  }
  function prepare(analysis){
    const env=analysis.acoustic?.envelope;if(!env)return;
    for(const event of analysis.events||[]){
      event.energyReference=Math.max(.0002,quantile(env.voice.slice(Math.floor(event.start/env.step),Math.ceil(event.end/env.step)),.85));event.energyReferences={};
      for(const id of voices(event)){const track=voiceTrack(analysis.acoustic,id);if(track?.voice)event.energyReferences[id]=Math.max(.0002,quantile(track.voice.slice(Math.max(0,Math.floor((event.start-(track.offset||0))/track.step)),Math.ceil((event.end-(track.offset||0))/track.step)),.85))}
    }
  }
  function valid(analysis){const env=analysis?.acoustic?.envelope;return analysis?.acoustic?.version===version&&Array.isArray(analysis.events)&&analysis.events.every(e=>Number.isFinite(e.start)&&Number.isFinite(e.end)&&e.start>=0&&e.end>e.start&&e.end<=analysis.duration+.02&&['speech','laughter','environment','silence'].includes(e.kind))&&env?.step===.02&&Array.isArray(env.voice)&&env.voice.length>0&&env.voice.every(v=>Number.isFinite(v)&&v>=0)}
  return {version,at,voices,manualPeople,people,voiceTrack,needsReview,score,laughterTrack,classify,masked,compose,caption,motion,prepare,valid};
})();
if(typeof module!=='undefined')module.exports=AudioEvents;
