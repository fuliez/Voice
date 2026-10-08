package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.isAudioFile
import voice.core.data.repo.ChapterRepo
import voice.core.data.repo.getOrPut
import voice.core.documentfile.CachedDocumentFile
import voice.core.logging.api.Logger
import java.time.Instant

internal data class ChapterParseResult(
  val chapters: List<Chapter>,
  val firstChapterMetadata: Metadata?,
)

/**
 * How many files are analyzed at the same time.
 *
 * Analyzing a file spends most of its time waiting for storage, so a few files are read in parallel.
 * Media3 prepares at most `MetadataRetriever.DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS` (5) media sources
 * in parallel, so this stays below that limit.
 */
private const val MAX_PARALLEL_ANALYSES = 4

@Inject
internal class ChapterParser(
  private val chapterRepo: ChapterRepo,
  private val mediaAnalyzer: MediaAnalyzer,
) {

  suspend fun parse(documentFile: CachedDocumentFile): ChapterParseResult {
    val semaphore = Semaphore(MAX_PARALLEL_ANALYSES)
    val parsed = coroutineScope {
      documentFile.audioFiles()
        .map { file ->
          async {
            semaphore.withPermit { parseChapter(file) }
          }
        }
        .awaitAll()
        .filterNotNull()
    }

    val chapters = parsed.map { it.chapter }.sorted()
    val analyzedMetadata: Map<ChapterId, Metadata?> = parsed.associate { it.chapter.id to it.metadata }
    Logger.d("Parsed ${chapters.size} chapters, ${parsed.count { it.metadata != null }} needed analysis")
    return ChapterParseResult(
      chapters = chapters,
      firstChapterMetadata = chapters.firstOrNull()?.let { analyzedMetadata[it.id] },
    )
  }

  private suspend fun parseChapter(file: CachedDocumentFile): ParsedChapter? {
    val id = ChapterId(file.uri)
    val lastModified = Instant.ofEpochMilli(file.lastModified)
    // getOrPut is inline, so what the analyzer read for a fresh chapter is visible below.
    var analyzedMetadata: Metadata? = null
    val chapter = chapterRepo.getOrPut(
      id = id,
      lastModified = lastModified,
      fileSize = file.length,
    ) {
      val metaData = mediaAnalyzer.analyze(file)
      analyzedMetadata = metaData
      metaData?.let {
        Chapter(
          id = id,
          duration = it.duration,
          fileLastModified = lastModified,
          name = it.title ?: it.fileName,
          markData = it.chapters,
          fileSize = file.length,
        )
      }
    } ?: return null
    return ParsedChapter(chapter = chapter, metadata = analyzedMetadata)
  }

  private fun CachedDocumentFile.audioFiles(): List<CachedDocumentFile> {
    val result = mutableListOf<CachedDocumentFile>()
    fun collect(file: CachedDocumentFile) {
      when {
        file.isAudioFile() -> result += file
        file.isDirectory -> file.children.forEach { collect(it) }
      }
    }
    collect(this)
    return result
  }

  private data class ParsedChapter(
    val chapter: Chapter,
    val metadata: Metadata?,
  )
}
