package com.example.hd_project.momo

import android.content.Context
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable data class PresetPerson(val personId: String, val name: String, val seat: String, val manifest: String, val pose: String = "front")
@Serializable data class PresetDocument(val presetId: String, val background: String, val vehicle: String,
    val seatbelts: String? = null, val steeringWheel: String? = null, val people: List<PresetPerson>,
    val speakerMapping: Map<String,String>, val layout: CabinLayout = CabinLayout()) {
    fun validate() {
        require(people.size in 1..4 && people.map { it.personId }.distinct().size == people.size)
        require(people.map { it.seat }.distinct().size == people.size && people.all { it.seat in SeatIds })
        require(people.all { it.personId.matches(Regex("P[1-4]")) && it.pose in listOf("front","left","right","down") })
        require(speakerMapping.keys.all { it.matches(Regex("S[1-8]")) } && speakerMapping.values.all { value -> people.any { it.personId == value } })
        require(listOf(layout.carLeft,layout.carTop,layout.carWidth,layout.frontHeight,layout.rearHeight,layout.frontRootY,layout.rearRootY).all { it.isFinite() })
        require(layout.carWidth in 100f..2000f && layout.frontHeight in 100f..1600f && layout.rearHeight in 100f..1600f)
    }
}
@Serializable private data class PackedAsset(val path: String, val sha256: String, val bytes: Long)
data class InstalledPreset(val root: File,val config: PresetDocument,val fingerprint: String) {
    fun file(path: String): File {
        val result=File(root,path).canonicalFile
        require(result.path.startsWith(root.canonicalPath+File.separator) && result.isFile) { "영상 리소스가 누락되었어요." }
        return result
    }
    fun apply(scene: SceneProject) = scene.copy(people=config.people.map { Person(it.personId,it.name,it.seat,
        manifestRef=file(it.manifest).path,pose=it.pose) },speakerMapping=config.speakerMapping,
        sampleVisuals=false,backgroundRef=file(config.background).path,backgroundIsVideo=config.background.endsWith(".mp4",true),
        vehicleRef=file(config.vehicle).path,seatbeltsRef=config.seatbelts?.let { file(it).path },
        steeringWheelRef=config.steeringWheel?.let { file(it).path },presetFingerprint=fingerprint,layout=config.layout)
}
object FixedPreset {
    suspend fun install(context: Context): InstalledPreset = withContext(Dispatchers.IO) {
        val index=context.assets.open("momo/index.json").use { it.readBytes() }
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(index).joinToString("") { "%02x".format(it) }
        val root=File(context.noBackupFilesDir,"momo-assets/$fingerprint").apply { mkdirs() }
        val assets=MomoJson.decodeFromString<List<PackedAsset>>(index.decodeToString())
        for(asset in assets) {
            val file=File(root,asset.path).canonicalFile
            require(file.path.startsWith(root.canonicalPath+File.separator))
            if(!file.isFile || file.length()!=asset.bytes || sha256(file)!=asset.sha256) {
                file.parentFile!!.mkdirs()
                val pending=File(file.parentFile,file.name+".pending")
                context.assets.open("momo/${asset.path}").use { input -> pending.outputStream().use { input.copyTo(it) } }
                check(sha256(pending)==asset.sha256) { "영상 리소스를 확인하지 못했어요." }
                check(pending.renameTo(file))
            }
        }
        val config=MomoJson.decodeFromString<PresetDocument>(File(root,"preset.json").readText()).also { it.validate() }
        InstalledPreset(root,config,fingerprint)
    }
}
