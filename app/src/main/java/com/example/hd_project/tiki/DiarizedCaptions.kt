package com.example.hd_project.tiki

import com.example.hd_project.momo.model.Caption
import kotlin.math.roundToInt

/** Pleos supplies segment text, not word alignment. Allocate every word once; never duplicate a line for overlapping voices. */
internal object DiarizedCaptions {
    fun split(text: String, startMs: Long, endMs: Long, turns: List<SpeakerTurn>, prefix: String): List<Caption> {
        data class Slot(val a: Long,val b: Long,val speaker: Int?)
        val edges=(listOf(startMs,endMs)+turns.flatMap { listOf(it.startMs.coerceIn(startMs,endMs),it.endMs.coerceIn(startMs,endMs)) }).distinct().sorted()
        val slots=mutableListOf<Slot>()
        edges.zipWithNext().forEach { (a,b) ->
            val active=turns.filter { it.startMs<b && it.endMs>a }
            if(active.isNotEmpty()) {
                // Text does not identify which overlapping voice said each word. Keep that interval unassigned.
                val speaker=active.map { it.speaker }.distinct().singleOrNull()
                val previous=slots.lastOrNull()
                if(previous!=null && previous.b==a && previous.speaker==speaker)slots[slots.lastIndex]=previous.copy(b=b)
                else slots.add(Slot(a,b,speaker))
            }
        }
        if(slots.isEmpty())slots.add(Slot(startMs,endMs,null))
        val words=text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { it.chunked(28) }
        val total=slots.sumOf { it.b-it.a }.coerceAtLeast(1);var elapsed=0L;var consumed=0
        return buildList {
            slots.forEachIndexed { index,slot ->
                elapsed+=slot.b-slot.a
                val until=if(index==slots.lastIndex)words.size else (words.size*elapsed.toDouble()/total).roundToInt().coerceIn(consumed,words.size)
                val pieces=MetadataEpisodes.readableText(words.subList(consumed,until).joinToString(" "));consumed=until
                val length=pieces.sumOf { it.length }.coerceAtLeast(1);var used=0
                pieces.forEach { piece ->
                    val a=slot.a*1000+(slot.b-slot.a)*1000*used/length;used+=piece.length
                    val b=slot.a*1000+(slot.b-slot.a)*1000*used/length
                    if(b>a)add(Caption("$prefix-${size}",a,b,piece,slot.speaker?.let { listOf("performance${it+1}") } ?: emptyList(),requiresReview=true))
                }
            }
        }
    }
}
