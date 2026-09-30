package rida.pour.les.pros.agent

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rida.pour.les.pros.agent.llm.AnthropicProvider
import rida.pour.les.pros.agent.llm.LlmProvider
import rida.pour.les.pros.data.io.RidaWorkbookExporter
import rida.pour.les.pros.domain.SyntheseRow
import rida.pour.les.pros.xlsx.XlsxWriter
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Écrit la synthèse en xlsx dans le stockage privé de l'app (partagé ensuite via FileProvider). */
@Singleton
class XlsxSyntheseWriter @Inject constructor(@ApplicationContext private val context: Context) : SyntheseFileWriter {
    override suspend fun write(rows: List<SyntheseRow>, today: LocalDate): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Ne garde que les 20 dernières synthèses.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val stamp = today.toString() + "_" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
        val file = File(dir, "Synthese_RIDA_$stamp.xlsx")
        file.outputStream().use { XlsxWriter.write(RidaWorkbookExporter.synthese(rows), it) }
        file.absolutePath
    }

    companion object {
        const val DIR = "syntheses"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    abstract fun llm(impl: AnthropicProvider): LlmProvider

    @Binds
    abstract fun syntheseWriter(impl: XlsxSyntheseWriter): SyntheseFileWriter
}
