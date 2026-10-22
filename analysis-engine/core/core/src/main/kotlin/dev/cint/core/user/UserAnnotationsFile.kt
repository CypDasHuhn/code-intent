package dev.cint.core.user

import dev.cint.core.user.layout.AnnotationLayout
import dev.cint.core.user.lock.AnnotationFileLock
import dev.cint.core.user.lock.AnnotationLockObserver
import java.nio.file.Path

object UserAnnotationsFile {
    const val DIRECTORY: String = AnnotationLayout.DIRECTORY
    const val FILE_NAME: String = AnnotationLayout.FILE_NAME

    internal var lockObserver: AnnotationLockObserver?
        get() = AnnotationFileLock.observer
        set(value) {
            AnnotationFileLock.observer = value
        }

    fun path(root: Path): Path = AnnotationLayout.path(root)

    fun load(root: Path): List<UserAnnotation> = AnnotationFileStore.load(path(root))

    fun loadTolerant(
        root: Path,
        onError: (Throwable) -> Unit = {},
    ): List<UserAnnotation> =
        runCatching { load(root) }.getOrElse { error ->
            onError(error)
            emptyList()
        }

    fun add(
        root: Path,
        annotation: UserAnnotation,
    ): List<UserAnnotation> =
        synchronized(AnnotationFileLock.lockFor(root)) {
            AnnotationFileLock.withFileLockToken(root) { token ->
                val existing = loadTolerant(root)
                val kept =
                    existing.filterNot {
                        it.path == annotation.path &&
                            it.span == annotation.span &&
                            it.intents == annotation.intents
                    }
                val updated = kept + annotation
                writeLocked(root, updated, token)
                updated
            }
        }

    fun save(
        root: Path,
        annotations: List<UserAnnotation>,
    ) {
        synchronized(AnnotationFileLock.lockFor(root)) {
            AnnotationFileLock.withFileLockToken(root) { token ->
                writeLocked(root, annotations, token)
            }
        }
    }

    internal fun releaseIfOwned(
        root: Path,
        token: String,
    ): Boolean = AnnotationFileLock.releaseIfOwned(root, token)

    internal fun isStaleForTest(root: Path): Boolean = AnnotationFileLock.isStaleForTest(root)

    internal fun processStartMarkerForTest(pid: Long): String = AnnotationFileLock.processStartMarkerForTest(pid)

    internal fun <T> withFileLock(
        root: Path,
        onAcquireConflict: ((Path) -> Unit)? = null,
        onBeforeOwnerWrite: ((Path) -> Unit)? = null,
        onAfterClaimAcquired: ((Path) -> Unit)? = null,
        onBeforeRelease: (() -> Unit)? = null,
        onBeforeReleaseRestore: (() -> Unit)? = null,
        acquireTimeoutMillis: Long = 15_000,
        block: () -> T,
    ): T =
        AnnotationFileLock.withFileLock(
            root,
            onAcquireConflict,
            onBeforeOwnerWrite,
            onAfterClaimAcquired,
            onBeforeRelease,
            onBeforeReleaseRestore,
            acquireTimeoutMillis,
            block,
        )

    internal fun <T> withFileLockToken(
        root: Path,
        onAcquireConflict: ((Path) -> Unit)? = null,
        onBeforeOwnerWrite: ((Path) -> Unit)? = null,
        onAfterClaimAcquired: ((Path) -> Unit)? = null,
        onBeforeRelease: (() -> Unit)? = null,
        onBeforeReleaseRestore: (() -> Unit)? = null,
        acquireTimeoutMillis: Long = 15_000,
        block: (String) -> T,
    ): T =
        AnnotationFileLock.withFileLockToken(
            root,
            onAcquireConflict,
            onBeforeOwnerWrite,
            onAfterClaimAcquired,
            onBeforeRelease,
            onBeforeReleaseRestore,
            acquireTimeoutMillis,
            block,
        )

    private fun writeLocked(
        root: Path,
        annotations: List<UserAnnotation>,
        token: String,
    ) {
        AnnotationFileLock.assertOwned(root, token)
        AnnotationFileStore.write(path(root), annotations) {
            AnnotationFileLock.assertOwned(root, token)
        }
    }
}
