package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.people.*
import java.io.File

object TikiScene {
    fun buildPerformance(context: Context, base: SceneProject, profile: PersonProfile = TikiProfile.get(context), count: Int = 2): SceneProject {
        val root=install(context)
        require(count in 1..4)
        val character=profile.characterId.takeIf { it in TikiProfile.characters } ?: TikiProfile.characters.first()
        val characters=listOf(character)+TikiProfile.characters.filter { it!=character }
        val people=(0 until count).map { i -> Person("P${i+1}",if(i==0)profile.name else "동행 $i",SeatIds[i],
            manifestRef=File(root,"characters/${characters[i]}/manifest.json").path) }
        fun vehicle(name: String)=File(root,"vehicles/$name.png").path
        return base.copy(people=people,
            speakerMapping=people.mapIndexed { i,p -> "performance${i+1}" to p.personId }.toMap(),backgroundRef=File(root,"backgrounds/city-road-loop.mp4").path,backgroundIsVideo=true,
            vehicleRef=vehicle("01-frame-and-mirror"),seatbeltsRef=vehicle("02-front-seat-belts"),steeringWheelRef=vehicle("06-steering-wheel"),
            vehicleRearRef=vehicle("03-rear-seat"),vehicleFrontRef=vehicle("05-front-seats"),vehicleBottomRef=vehicle("04-bottom-interior-panel"),
            rendererVersion=ProductionSettings.RENDERER,presetFingerprint="designer-defaults-2026-11-12",burnCaptions=false,
            width=1280,height=720,layout=CabinLayout(0f,0f,1920f,800f,500f,980f,760f))
    }
    @Synchronized fun install(context: Context): File {
        val root = File(context.noBackupFilesDir, "tikitaka-art-v1")
        fun copy(path: String) {
            val entries = context.assets.list("tiki/video/$path").orEmpty()
            val out = File(root, path)
            if (entries.isNotEmpty()) { out.mkdirs(); entries.forEach { copy(if(path.isEmpty()) it else "$path/$it") } }
            else if (!out.isFile) {
                out.parentFile!!.mkdirs()
                val pending = File(out.path + ".pending")
                context.assets.open("tiki/video/$path").use { input -> pending.outputStream().use { input.copyTo(it) } }
                check(pending.renameTo(out))
            }
        }
        copy("")
        return root
    }
    fun build(context: Context, base: SceneProject, analysis: AnalysisBundle, profiles: List<PersonProfile>): SceneProject {
        val root = install(context)
        val matches = VoiceMatcher.match(analysis.result, profiles).take(4)
        require(matches.isNotEmpty()) { "대화 화자가 감지되지 않았어요. 원음은 보관되어 있어요." }
        val used = matches.mapNotNull { it.profile?.characterId }.toMutableSet()
        val people = matches.mapIndexed { index, match ->
            val character = match.profile?.characterId ?: TikiProfile.characters.first { it !in used }.also { used.add(it) }
            Person("P${index+1}", match.profile?.name ?: "함께한 사람 ${index+1}", SeatIds[index],
                manifestRef = File(root, "characters/$character/manifest.json").path)
        }
        fun vehicle(name: String) = File(root, "vehicles/$name.png").path
        return base.copy(people = people, speakerMapping = matches.mapIndexed { i, m -> m.speakerId to people[i].personId }.toMap(),
            backgroundRef = File(root, "backgrounds/city-road-loop.mp4").path, backgroundIsVideo = true,
            vehicleRef = vehicle("01-frame-and-mirror"), seatbeltsRef = vehicle("02-front-seat-belts"),
            steeringWheelRef = vehicle("06-steering-wheel"),
            vehicleRearRef = vehicle("03-rear-seat"), vehicleFrontRef = vehicle("05-front-seats"),
            vehicleBottomRef = vehicle("04-bottom-interior-panel"),
            rendererVersion = "tikitaka-android-sprites-v1", presetFingerprint = "designer-defaults-2026-11-12",burnCaptions=false,
            layout = CabinLayout(0f,260f,540f,800f,500f,980f,760f))
    }
}
