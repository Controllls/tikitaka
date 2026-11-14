package com.example.hd_project.tiki

import com.example.hd_project.momo.model.StoryBeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class StoryLine(val startUs: Long, val endUs: Long, val text: String)
@Serializable data class DialogueStory(val version: String, val model: String, val title: String,
    val mentionedPlace: String = "", val beats: List<StoryBeat> = emptyList(),
    val status: String = "generated", val reason: String = "", val elapsedMs: Long = 0)

/** Offline text rules only. Titles quote the transcript; framing never determines speaker identity. */
object DialogueStoryRules {
    const val VERSION = "dialogue-story-rules-v4"
    const val MODEL = "none"
    val json = Json { encodeDefaults = true }
    private val filler = Regex("(?:[어아음으오흠ㅋㅎ]+|네|예|응|그래|그렇구나|맞아|안녕하세요|감사합니다|웃음)")
    private val stopWords = setOf("그리고", "그래서", "그런데", "그러면", "하지만", "정말", "진짜", "그냥", "이거", "저거", "그거", "우리", "저는", "나는")
    private fun clean(text: String) = text.replace(Regex("[\\p{Cc}\\s]+"), " ").trim()
    private fun meaningful(text: String) = text.count(Char::isLetterOrDigit)>=2 &&
        !filler.matches(text.filter(Char::isLetterOrDigit))
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase()).map { it.value }
        .filter { it !in stopWords && !filler.matches(it) }
        .map { if(it.length>=3)it.replace(Regex("(은|는|이|가|을|를|에|도|만)$"), "") else it }.toSet()

    private fun boundedTitle(value: String): String {
        val text=clean(value)
        if(text.codePointCount(0,text.length)<=24)return text
        val prefix=text.substring(0,text.offsetByCodePoints(0,23))
        val boundary=prefix.lastIndexOf(' ')
        return (if(boundary>=12)prefix.substring(0,boundary) else prefix).trimEnd()+"…"
    }

    /** Choose a concise sentence supported by recurring words, with source order breaking ties. */
    fun title(lines: List<StoryLine>, fallbackTitle: String="함께 나눈 이야기"): String {
        val candidates=lines.asSequence().filter { it.endUs>it.startUs }.sortedBy { it.startUs }
            .flatMap { it.text.split(Regex("[.!?。！？\\n]+")).asSequence() }.map(::clean)
            .filter(::meaningful).distinct().toList()
        if(candidates.isEmpty())return boundedTitle(fallbackTitle.takeIf { meaningful(clean(it)) } ?: "함께 나눈 이야기")
        val vocabulary=candidates.map(::words)
        val frequencies=vocabulary.flatMap { it }.groupingBy { it }.eachCount()
        val selected=candidates.indices.maxByOrNull { i ->
            val length=candidates[i].codePointCount(0,candidates[i].length)
            vocabulary[i].sumOf { (frequencies.getValue(it)-1).coerceAtMost(3) }.toDouble() +
                vocabulary[i].size.coerceAtMost(8)*.35 + (if(length in 8..24)2.0 else 0.0) -
                (length-24).coerceAtLeast(0)*.08
        }!!
        return boundedTitle(candidates[selected])
    }

    fun plan(lines: List<StoryLine>, durationUs: Long, fallbackTitle: String="함께 나눈 이야기"): DialogueStory {
        require(durationUs>0)
        val valid=lines.mapNotNull { line ->
            val start=line.startUs.coerceIn(0,durationUs);val end=line.endUs.coerceIn(0,durationUs)
            val text=clean(line.text)
            if(end>start && meaningful(text))StoryLine(start,end,text) else null
        }
        val title=title(valid,fallbackTitle)
        val scenes=scenes(valid)
        if(scenes.isEmpty())return fallback(title,"no_transcript")
        val beats=scenes.mapIndexed { i,line ->
            val start=if(i==0)0L else line.startUs
            val end=scenes.getOrNull(i+1)?.startUs?.coerceAtLeast(start) ?: durationUs
            val shot=when { i==0 -> "wide"; '?' in line.text || '？' in line.text -> "exchange"; else -> "speaker" }
            StoryBeat(start,end,shot)
        }.filter { it.endUs>it.startUs }
        return DialogueStory(VERSION,MODEL,title,beats=beats,status="extracted")
    }

    fun scenes(lines: List<StoryLine>): List<StoryLine> {
        // STT can return a whole conversation as one segment. Keep its ending, too.
        val clean = lines.filter { it.endUs > it.startUs && it.text.isNotBlank() && it.text.trim() != "(웃음)" }
            .sortedBy { it.startUs }.flatMap { line ->
                val text = line.text.trim().replace(Regex("\\s+"), " ")
                text.chunked(160).mapIndexedNotNull { i, part ->
                    val start = line.startUs + (line.endUs-line.startUs) * (i*160) / text.length
                    val end = line.startUs + (line.endUs-line.startUs) * minOf((i+1)*160,text.length) / text.length
                    if (end>start) StoryLine(start,end,part) else null
                }
            }
        if (clean.isEmpty()) return emptyList()
        val count = minOf(6, clean.size)
        return (0 until count).map { i ->
            val group = clean.subList(i * clean.size / count, (i + 1) * clean.size / count)
            val text=group.joinToString(" ") { it.text }
            val sample=if(text.length<=160)text else text.take(52)+" … "+
                text.substring(text.length/2-25,text.length/2+25)+" … "+text.takeLast(52)
            StoryLine(group.first().startUs, group.last().endUs, sample)
        }
    }

    fun fallback(title: String, reason: String) = DialogueStory(VERSION, "none", title,
        status = "basic", reason = reason)
}
