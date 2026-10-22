package dev.cint.core.user

import java.nio.file.Path

object UserAnnotationsFile {
    const val DIRECTORY: String = ".cint"
    const val FILE_NAME: String = "user-annotations.json"

    internal var lockObserver: AnnotationLockObserver?
        get() = AnnotationFileLock.observer
        set(value) {
            AnnotationFileLock.observer = value
        }

    fun path(root: Path): Path = root.resolve(DIRECTORY).resolve(FILE_NAME)

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
                AnnotationFileLock.write(root, updated, token)
                updated
            }
        }

    fun save(
        root: Path,
        annotations: List<UserAnnotation>,
    ) {
        synchronized(AnnotationFileLock.lockFor(root)) {
            AnnotationFileLock.withFileLockToken(root) { token ->
                AnnotationFileLock.write(root, annotations, token)
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
}
