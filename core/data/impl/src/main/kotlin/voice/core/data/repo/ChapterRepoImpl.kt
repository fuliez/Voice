package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.internals.dao.ChapterDao
import voice.core.data.runForMaxSqlVariableNumber

@ContributesBinding(AppScope::class)
public class ChapterRepoImpl(private val dao: ChapterDao) : ChapterRepo {

  private val cache = mutableMapOf<ChapterId, Chapter?>()

  // Several files can be analyzed at the same time, so the cache needs to be guarded.
  private val cacheLock = Mutex()

  override suspend fun get(id: ChapterId): Chapter? = cacheLock.withLock {
    // this does not use getOrPut because a `null` value should also be cached
    if (!cache.containsKey(id)) {
      cache[id] = dao.chapter(id)
    }
    cache[id]
  }

  internal suspend fun warmup(ids: List<ChapterId>) {
    val missing = cacheLock.withLock { ids.filter { it !in cache } }
    val chapters = missing.runForMaxSqlVariableNumber {
      dao.chapters(it)
    }
    cacheLock.withLock {
      chapters.forEach { cache[it.id] = it }
    }
  }

  override suspend fun put(chapter: Chapter) {
    dao.insert(chapter)
    cacheLock.withLock {
      cache[chapter.id] = chapter
    }
  }
}
