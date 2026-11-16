package com.pes2017.recomp.patch.install

import com.pes2017.recomp.patch.DpFileListParser
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchSource
import com.pes2017.recomp.patch.PatchSourceKind
import com.pes2017.recomp.patch.PlannedCpk
import com.pes2017.recomp.patch.PlannedDpFileList
import com.pes2017.recomp.patch.PlannedSave
import com.pes2017.recomp.patch.SavePayloadParser
import com.pes2017.recomp.patch.SaveSlot
import com.pes2017.recomp.patch.StfsException
import com.pes2017.recomp.patch.StfsPackage
import com.pes2017.recomp.patch.isSafePatchFileName
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stages, verifies and swaps the three PES 2018 data slots.
 *
 * A run is:
 *  1. copy only the selected entries out of the STFS CONs into
 *     `patch-staging/<id>/dlc/...` or `/saves/...`;
 *  2. verify size, CPK magic, every DpFileList reference and the save hash;
 *  3. write a journal naming every target, staged path and backup;
 *  4. rename active -> `patch-backups/<slot>/<timestamp>`, staged -> active;
 *  5. mark the journal committed and delete staging.
 *
 * If anything fails before the journal, staging is deleted. After the journal,
 * every rename is undone from the journal's per-slot state. A process death
 * leaves the journal for [recover] on the next app start.
 *
 * The class is deliberately JVM-only: Android owns the service, the
 * notification and the game-running check; this owns the files.
 */
class PatchInstaller(
    private val gameRoot: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val fault: ((FaultPoint, InstallSlot?) -> Unit)? = null,
) {

    private val stagingRoot = File(gameRoot, "patch-staging")
    private val backupRoot = File(gameRoot, "patch-backups")
    private val journalFile = File(gameRoot, "patch-journal.properties")
    private val lockFile = File(gameRoot, LOCK_NAME)

    fun target(slot: InstallSlot): File = File(gameRoot, slot.relativePath)

    /**
     * The exact peak requirement, before anything is written. The caller can
     * show every part; install re-reads the free space and fails closed with
     * the same numbers if it no longer fits.
     */
    fun space(
        plan: PatchPlan,
        selection: InstallSelection,
        policy: InstallBackupPolicy,
    ): InstallSpace {
        val selected = selection.availableIn(plan)
        var staging = 0L
        if (selected.patch) {
            staging = InstallSpace.safeAdd(staging, plan.cpkBytes)
            staging = InstallSpace.safeAdd(staging, plan.dpFileList.size)
        }
        for (save in plan.saves) {
            if (!selected.includes(InstallSlot.forSave(save.slot))) continue
            val inner = InstallSpace.safeAdd(save.dataBinSize, INF_RESERVE)
            staging = InstallSpace.safeAdd(
                staging,
                InstallSpace.safeAdd(inner, save.dataPngSize ?: 0L),
            )
        }

        var backup = 0L
        var freed = 0L
        for (slot in selected.slots()) {
            val active = sizeOf(target(slot))
            if (policy != InstallBackupPolicy.NONE) {
                backup = InstallSpace.safeAdd(backup, active)
            }
            if (policy == InstallBackupPolicy.FREE_OLD) {
                freed = InstallSpace.safeAdd(freed, sizeOf(File(backupRoot, slot.key)))
            }
        }

        var spool = 0L
        if (plan.sourceKind != PatchSourceKind.FOLDER) {
            if (selected.patch) {
                for (cpk in plan.cpks) spool = maxOf(spool, cpk.outerSize)
            }
            for (save in plan.saves) {
                if (selected.includes(InstallSlot.forSave(save.slot))) {
                    spool = maxOf(spool, save.outerSize)
                }
            }
        }

        return InstallSpace(
            stagingBytes = staging,
            backupBytes = backup,
            spoolBytes = spool,
            marginBytes = InstallSpace.MARGIN_BYTES,
            freeBytes = freeSpace(gameRoot).coerceAtLeast(0),
            freedOldBackupBytes = freed,
        )
    }

    /**
     * Runs one import. The source must still be open; this opens its own
     * sequential session so the scan that produced [plan] is not disturbed.
     *
     * [installId] is the id the service already wrote into its start record.
     * Reusing it here is what ties the journal, the backup sidecars, the live
     * heartbeat and the terminal record to one install; a caller with none
     * (tests, a plain tool) gets a fresh one.
     */
    fun install(
        source: PatchSource,
        plan: PatchPlan,
        selection: InstallSelection,
        policy: InstallBackupPolicy = InstallBackupPolicy.KEEP,
        cancelled: () -> Boolean = { false },
        progress: (InstallProgress) -> Unit = {},
        installId: String? = null,
    ): InstallResult {
        val selected = selection.availableIn(plan)
        if (!selected.any) {
            return InstallResult.Failed(
                InstallFailure.NOTHING_SELECTED,
                "nothing selected is present in this source",
            )
        }
        if (!gameRoot.isDirectory) {
            return InstallResult.Failed(
                InstallFailure.NO_GAME_FOLDER,
                "no game folder at ${gameRoot.path}",
            )
        }
        validatePlan(plan, selected)?.let {
            return InstallResult.Failed(InstallFailure.VERIFY, it)
        }
        if (!acquireLock()) {
            return InstallResult.Failed(InstallFailure.BUSY, "another patch operation is running")
        }

        // The id names the staging folder, so a caller-supplied one is checked
        // before it reaches the filesystem; anything odd falls back to a UUID.
        val id = installId?.takeIf { SAFE_ID.matches(it) } ?: UUID.randomUUID().toString()
        val workRoot = File(stagingRoot, id)
        var keepForRecovery = false
        var journal: InstallJournal? = null
        var committed = false
        try {
            recoverLocked()
            if (policy == InstallBackupPolicy.FREE_OLD) {
                selected.slots().forEach { deleteOldBackups(it) }
            }

            progress(InstallProgress(InstallPhase.PREPARING, 0))
            val space = space(plan, selected, policy)
            if (!space.enough) {
                return InstallResult.Failed(
                    InstallFailure.LOW_SPACE,
                    "need ${space.requiredBytes} bytes at ${gameRoot.path}; " +
                        "${space.availableBytes} are free",
                )
            }

            if (!workRoot.mkdirs() && !workRoot.isDirectory) {
                throw IllegalStateException("cannot create staging at ${workRoot.path}")
            }
            val tracker = Tracker(
                total = workTotal(plan, selected, space),
                progress = progress,
                cancelled = cancelled,
            )
            extract(source, plan, selected, workRoot, tracker, cancelled)
            if (cancelled()) throw InstallCancelledException()
            tracker.phase(InstallPhase.VERIFYING, 90, "Verifying staged files")
            verifyStaged(workRoot, plan, selected)
            if (cancelled()) throw InstallCancelledException()

            fault?.invoke(FaultPoint.STAGED, null)

            val created = buildJournal(plan, selected, policy, workRoot, id)
            journal = created
            created.write(journalFile)
            fault?.invoke(FaultPoint.JOURNAL_READY, null)

            tracker.phase(InstallPhase.SWAPPING, 95, "Moving the patch into place")
            swap(created, policy)
            created.phase = JournalPhase.COMMITTED
            created.write(journalFile)
            committed = true
            fault?.invoke(FaultPoint.COMMITTED, null)

            tracker.phase(InstallPhase.CLEANING, 100, "Finishing")
            runCatching { PatchStateStore.update(gameRoot, installedState(plan, selected)) }
            runCatching { cleanupWork(workRoot) }
            runCatching { journalFile.delete() }
            journal = null
            return InstallResult.Success(
                slots = selected.slots(),
                bytes = space.stagingBytes,
                backup = policy,
            )
        } catch (death: SimulatedProcessDeath) {
            // A test proving the journal survives a process death. Leave every
            // file exactly where the crash happened; only drop the in-process
            // lock, as a new process would have no lock at all.
            keepForRecovery = true
            throw death
        } catch (cancel: InstallCancelledException) {
            val restored = if (journal != null) rollback(journal) else true
            if (restored) cleanupWork(workRoot)
            return InstallResult.Cancelled(restored, "cancelled")
        } catch (t: Throwable) {
            val failure = when (t) {
                is InstallVerifyException -> InstallFailure.VERIFY
                is StfsException -> InstallFailure.VERIFY
                else -> InstallFailure.IO
            }
            val restored = if (journal != null && !committed) rollback(journal) else true
            if (!committed && restored) cleanupWork(workRoot)
            return InstallResult.Failed(
                failure,
                t.message ?: t.toString(),
                restored,
            )
        } finally {
            if (keepForRecovery) {
                releaseProcessLockOnly()
            } else {
                unlock()
            }
        }
    }

    /** Deletes the active slot and the runtime's `.bak` sibling. The backup stays. */
    fun remove(slot: InstallSlot): InstallResult {
        if (!gameRoot.isDirectory) {
            return InstallResult.Failed(InstallFailure.NO_GAME_FOLDER, "no game folder")
        }
        if (!acquireLock()) {
            return InstallResult.Failed(InstallFailure.BUSY, "another patch operation is running")
        }
        val target = target(slot)
        val bak = File(target.parentFile, target.name + ".bak")
        val trash = File(stagingRoot, "remove-${UUID.randomUUID()}")
        var moved = false
        var bytes = 0L
        try {
            recoverLocked()
            bytes = sizeOf(target)
            if (target.exists() || bak.exists()) {
                if (!trash.mkdirs() && !trash.isDirectory) {
                    throw IllegalStateException("cannot create staging at ${trash.path}")
                }
                // Move the runtime's `.bak` first: if the process dies before
                // the active slot moves, recovery must not restore that stale
                // copy as the slot the player asked to remove.
                if (bak.exists()) {
                    val movedBak = bak.renameTo(File(trash, slot.key + ".bak"))
                    if (!movedBak && !bak.deleteRecursively()) {
                        throw IllegalStateException("cannot remove ${bak.path}")
                    }
                }
                if (target.exists()) {
                    if (!target.renameTo(File(trash, slot.key))) {
                        throw IllegalStateException("cannot move ${target.path} aside")
                    }
                    moved = true
                }
                fault?.invoke(FaultPoint.REMOVE_STAGED, slot)
                runCatching { trash.deleteRecursively() }
                cleanupEmptyStaging()
            }
            runCatching { PatchStateStore.clearSlot(gameRoot, slot) }
            return InstallResult.Success(listOf(slot), bytes, InstallBackupPolicy.NONE)
        } catch (death: SimulatedProcessDeath) {
            throw death
        } catch (t: Throwable) {
            // If the rename never happened the active slot is untouched. Once
            // it has, the trash stays for recover() to delete; never delete it
            // back into a half-removed active slot.
            if (!moved) runCatching { trash.deleteRecursively() }
            return InstallResult.Failed(InstallFailure.IO, t.message ?: t.toString())
        } finally {
            unlock()
        }
    }

    /** Puts the newest backup back as the active slot, keeping the old active as staging until commit. */
    fun restore(slot: InstallSlot): InstallResult {
        if (!gameRoot.isDirectory) {
            return InstallResult.Failed(InstallFailure.NO_GAME_FOLDER, "no game folder")
        }
        if (!acquireLock()) {
            return InstallResult.Failed(InstallFailure.BUSY, "another patch operation is running")
        }
        var journal: InstallJournal? = null
        val id = UUID.randomUUID().toString()
        val workRoot = File(stagingRoot, "restore-$id")
        try {
            recoverLocked()
            val slotBackups = File(backupRoot, slot.key)
            val backup = slotBackups.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: return InstallResult.Failed(InstallFailure.NO_BACKUP, "no backup in ${slot.key}")
            slotBackups.listFiles()
                ?.filter { it.isDirectory && it != backup }
                ?.forEach { runCatching { it.deleteRecursively() } }

            val target = target(slot)
            val hadActive = target.exists()
            if (!workRoot.mkdirs() && !workRoot.isDirectory) {
                throw IllegalStateException("cannot create staging at ${workRoot.path}")
            }
            val old = File(workRoot, "old-${slot.key}")
            val record = JournalSlot(
                slot = slot,
                state = SlotState.PENDING,
                hadActive = hadActive,
                target = slot.relativePath,
                staged = relative(old),
                backup = relative(backup),
            )
            val created = InstallJournal(
                id = id,
                op = JournalOp.RESTORE,
                phase = JournalPhase.STAGING_READY,
                slots = mutableListOf(record),
                createdAt = System.currentTimeMillis(),
                work = relative(workRoot),
            )
            journal = created
            created.write(journalFile)

            created.phase = JournalPhase.SWAPPING
            created.write(journalFile)
            if (hadActive) {
                record.state = SlotState.OLD_MOVING
                created.write(journalFile)
                if (!target.renameTo(old)) {
                    throw IllegalStateException("cannot move ${target.path} aside")
                }
                record.state = SlotState.OLD_MOVED
                created.write(journalFile)
            }
            record.state = SlotState.NEW_MOVING
            created.write(journalFile)
            target.parentFile?.mkdirs()
            if (!backup.renameTo(target)) {
                throw IllegalStateException("cannot put the backup at ${target.path}")
            }
            record.state = SlotState.SWAPPED
            created.write(journalFile)
            created.phase = JournalPhase.COMMITTED
            created.write(journalFile)
            journal = null

            runCatching { old.deleteRecursively() }
            runCatching { cleanupWork(workRoot) }
            runCatching { journalFile.delete() }
            // The sidecar was written before the backup was made; it describes
            // exactly what was just put back.
            val sidecar = PatchStateStore.sidecar(backup)
            val restored = PatchStateStore.readSidecar(backup)
            runCatching { applyRestoredState(slot, restored) }
            runCatching { sidecar.delete() }
            runCatching {
                slotBackups.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".properties") }
                    ?.forEach { it.delete() }
            }
            runCatching {
                if (slotBackups.listFiles().isNullOrEmpty()) slotBackups.delete()
            }
            return InstallResult.Success(listOf(slot), sizeOf(target), InstallBackupPolicy.KEEP)
        } catch (t: Throwable) {
            val restored = if (journal != null) rollback(journal) else true
            if (journal == null || restored) cleanupWork(workRoot)
            return InstallResult.Failed(InstallFailure.IO, t.message ?: t.toString(), restored)
        } finally {
            unlock()
        }
    }

    /** Deletes every backup in a slot. */
    fun deleteBackup(slot: InstallSlot): InstallResult {
        if (!gameRoot.isDirectory) {
            return InstallResult.Failed(InstallFailure.NO_GAME_FOLDER, "no game folder")
        }
        if (!acquireLock()) {
            return InstallResult.Failed(InstallFailure.BUSY, "another patch operation is running")
        }
        try {
            recoverLocked()
            val dir = File(backupRoot, slot.key)
            val bytes = sizeOf(dir)
            if (dir.exists() && !dir.deleteRecursively()) {
                throw IllegalStateException("cannot delete ${dir.path}")
            }
            return InstallResult.Success(listOf(slot), bytes, InstallBackupPolicy.NONE)
        } catch (t: Throwable) {
            return InstallResult.Failed(InstallFailure.IO, t.message ?: t.toString())
        } finally {
            unlock()
        }
    }

    /**
     * Called at app start and before an install: an unfinished transaction is
     * rolled back, staging is removed, a committed install is kept, and a
     * runtime `.bak` is put back when it is the only copy of a slot.
     */
    fun recover(): RecoveryReport {
        if (!gameRoot.isDirectory) return RecoveryReport()
        if (!acquireLock()) return RecoveryReport(busy = true)
        return try {
            val outcome = recoverLocked()
            // Only a recovery that did something is persisted. An empty report
            // written on every clean start would erase the evidence of the one
            // incident somebody is trying to explain.
            if (outcome.report.changed ||
                outcome.report.problems.isNotEmpty() ||
                outcome.installId != null
            ) {
                writeRecoveryReport(outcome)
            }
            outcome.report
        } finally {
            unlock()
        }
    }

    // ---- extraction ------------------------------------------------------

    private fun extract(
        source: PatchSource,
        plan: PatchPlan,
        selection: InstallSelection,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        val remaining = LinkedHashMap<String, WorkItem>()
        workItems(plan, selection).forEach { remaining[it.path] = it }
        if (remaining.size != workItems(plan, selection).size) {
            throw InstallVerifyException("the plan uses one source entry twice")
        }

        val session = try {
            source.openSession()
        } catch (t: Throwable) {
            throw InstallVerifyException("cannot open the source again: ${t.message}")
        }
        try {
            while (true) {
                if (cancelled()) throw InstallCancelledException()
                val entry = session.next() ?: break
                if (entry.isDirectory) continue
                val item = remaining.remove(entry.path) ?: continue
                process(item, entry, session, workRoot, tracker, cancelled)
            }
        } finally {
            runCatching { session.close() }
        }
        if (remaining.isNotEmpty()) {
            throw InstallVerifyException(
                "the source no longer contains " +
                    remaining.keys.take(3).joinToString(", "),
            )
        }
        for (slot in selection.slots()) preserveXContent(workRoot, slot)
    }

    private fun process(
        item: WorkItem,
        entry: com.pes2017.recomp.patch.PatchSourceEntry,
        session: com.pes2017.recomp.patch.PatchSourceSession,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        val direct = entry.file
        if (direct != null && direct.isFile) {
            if (item.expectedOuter > 0 && entry.size >= 0 && entry.size != item.expectedOuter) {
                throw InstallVerifyException(
                    "${entry.path}: ${entry.size} bytes, expected ${item.expectedOuter}",
                )
            }
            if (item is WorkItem.SaveWork) {
                val hash = sha256File(direct, tracker, cancelled)
                if (!hash.equals(item.save.outerSha256, ignoreCase = true)) {
                    throw InstallVerifyException(
                        "${entry.path}: SHA-256 does not match the scanned save",
                    )
                }
            }
            openPackage(direct) { pkg ->
                extractInner(pkg, item, workRoot, tracker, cancelled)
            }
            return
        }

        val spool = File(workRoot, "spool.con")
        val stream = try {
            session.open()
        } catch (t: Throwable) {
            throw InstallVerifyException("cannot read ${entry.path}: ${t.message}")
        }
        try {
            val expected = item.expectedOuter
            val hash = spool(stream, spool, expected, tracker, entry.path, cancelled)
            if (item is WorkItem.SaveWork &&
                !hash.equals(item.save.outerSha256, ignoreCase = true)
            ) {
                throw InstallVerifyException(
                    "${entry.path}: SHA-256 does not match the scanned save",
                )
            }
            openPackage(spool) { pkg ->
                extractInner(pkg, item, workRoot, tracker, cancelled)
            }
        } finally {
            runCatching { stream.close() }
            runCatching { spool.delete() }
        }
    }

    private fun openPackage(file: File, block: (StfsPackage) -> Unit) {
        try {
            StfsPackage.open(file).use(block)
        } catch (e: StfsException) {
            throw InstallVerifyException("${file.name}: ${e.message}", e)
        }
    }

    private fun extractInner(
        pkg: StfsPackage,
        item: WorkItem,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        when (item) {
            is WorkItem.CpkWork -> extractCpk(pkg, item, workRoot, tracker, cancelled)
            is WorkItem.DpWork -> extractDp(pkg, item, workRoot, tracker, cancelled)
            is WorkItem.SaveWork -> extractSave(pkg, item, workRoot, tracker, cancelled)
        }
    }

    private fun extractCpk(
        pkg: StfsPackage,
        item: WorkItem.CpkWork,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        val cpk = item.cpk
        val inner = pkg.findNamed(cpk.innerName)
            ?: throw InstallVerifyException("${item.path}: no ${cpk.innerName} inside the CON")
        if (inner.directory || inner.size != cpk.size) {
            throw InstallVerifyException(
                "${item.path}: ${cpk.innerName} is ${inner.size} bytes, expected ${cpk.size}",
            )
        }
        val target = stagedFile(workRoot, item.slot, cpk.innerName)
        val read = writeEntry(pkg, inner, target, tracker, item.path, cancelled)
        if (read != cpk.size) {
            throw InstallVerifyException(
                "${cpk.innerName}: wrote $read of ${cpk.size} bytes",
            )
        }
        if (!hasCpkMagic(target)) {
            throw InstallVerifyException("${cpk.innerName}: staged file has no CPK magic")
        }
    }

    private fun extractDp(
        pkg: StfsPackage,
        item: WorkItem.DpWork,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        val dp = item.dp
        val inner = pkg.findNamed(dp.innerName)
            ?: throw InstallVerifyException("${item.path}: no ${dp.innerName} inside the CON")
        if (inner.directory || inner.size != dp.size) {
            throw InstallVerifyException(
                "${item.path}: ${dp.innerName} is ${inner.size} bytes, expected ${dp.size}",
            )
        }
        val target = stagedFile(workRoot, item.slot, dp.innerName)
        val read = writeEntry(pkg, inner, target, tracker, item.path, cancelled)
        if (read != dp.size) {
            throw InstallVerifyException("${dp.innerName}: wrote $read of ${dp.size} bytes")
        }
        val actual = sha256File(target, null, cancelled = { false })
        if (!actual.equals(dp.sha256, ignoreCase = true)) {
            throw InstallVerifyException("${dp.innerName}: SHA-256 differs from the scan")
        }
    }

    private fun extractSave(
        pkg: StfsPackage,
        item: WorkItem.SaveWork,
        workRoot: File,
        tracker: Tracker,
        cancelled: () -> Boolean,
    ) {
        val save = item.save
        val dataBin = pkg.findNamed("data.bin")
            ?: throw InstallVerifyException("${item.path}: no data.bin")
        if (dataBin.directory || dataBin.size != save.dataBinSize) {
            throw InstallVerifyException(
                "${item.path}: data.bin is ${dataBin.size} bytes, expected ${save.dataBinSize}",
            )
        }
        val target = stagedFile(workRoot, item.slot, "data.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val fileOut = FileOutputStream(target)
        val checked = CheckingOutputStream(fileOut, tracker, item.path, cancelled)
        val digestOut = DigestOutputStream(checked, digest)
        val read = try {
            pkg.readEntry(dataBin, digestOut, verify = true).bytes
        } finally {
            runCatching { digestOut.close() }
        }
        if (read != save.dataBinSize || target.length() != save.dataBinSize) {
            throw InstallVerifyException(
                "${item.path}: data.bin wrote $read of ${save.dataBinSize} bytes",
            )
        }
        if (!digest.digest().toHex().equals(save.dataBinSha256, ignoreCase = true)) {
            throw InstallVerifyException(
                "${item.path}: data.bin SHA-256 differs from the scan",
            )
        }
        val header = ByteArray(SavePayloadParser.MIN_HEADER_BYTES)
        target.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val n = input.read(header, offset, header.size - offset)
                if (n <= 0) break
                offset += n
            }
            if (offset != header.size) {
                throw InstallVerifyException("${item.path}: data.bin header is truncated")
            }
        }
        val payload = try {
            SavePayloadParser.parse(header, target.length())
        } catch (e: StfsException) {
            throw InstallVerifyException("${item.path}: ${e.message}", e)
        }
        if (payload != save.payload) {
            throw InstallVerifyException(
                "${item.path}: data.bin version/counts differ from the scan",
            )
        }

        val inf = pkg.findNamed("data.inf")
        if (save.dataInfName != null && inf == null) {
            throw InstallVerifyException("${item.path}: the scanned data.inf is gone")
        }
        if (inf != null && !inf.directory) {
            val infTarget = stagedFile(workRoot, item.slot, "data.inf")
            val bytes = writeEntry(pkg, inf, infTarget, tracker, item.path, cancelled)
            if (bytes != inf.size) {
                throw InstallVerifyException("${item.path}: data.inf is truncated")
            }
        }

        val png = pkg.findNamed("data.png")
        if (save.dataPngSize != null) {
            if (png == null || png.directory || png.size != save.dataPngSize) {
                throw InstallVerifyException(
                    "${item.path}: data.png is missing or has the wrong size",
                )
            }
            val pngTarget = stagedFile(workRoot, item.slot, "data.png")
            val bytes = writeEntry(pkg, png, pngTarget, tracker, item.path, cancelled)
            if (bytes != save.dataPngSize) {
                throw InstallVerifyException("${item.path}: data.png is truncated")
            }
        }
    }

    private fun writeEntry(
        pkg: StfsPackage,
        entry: com.pes2017.recomp.patch.StfsEntry,
        target: File,
        tracker: Tracker,
        detail: String,
        cancelled: () -> Boolean,
    ): Long {
        target.parentFile?.mkdirs()
        val out = CheckingOutputStream(
            FileOutputStream(target),
            tracker,
            detail,
            cancelled,
        )
        return try {
            val read = pkg.readEntry(entry, out, verify = true)
            out.flush()
            read.bytes
        } finally {
            runCatching { out.close() }
        }
    }

    // ---- verification ----------------------------------------------------

    private fun verifyStaged(
        workRoot: File,
        plan: PatchPlan,
        selection: InstallSelection,
    ) {
        if (selection.patch) {
            val dir = File(workRoot, InstallSlot.PATCH.relativePath)
            if (!dir.isDirectory) throw InstallVerifyException("no staged PATCH folder")
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            val byName = files.associateBy { it.name.lowercase(Locale.US) }
            val dpFile = byName["dpfilelist.bin"]
                ?: throw InstallVerifyException("no staged DpFileList.bin")
            if (dpFile.length() != plan.dpFileList.size) {
                throw InstallVerifyException(
                    "staged DpFileList.bin is ${dpFile.length()} bytes, expected " +
                        "${plan.dpFileList.size} (the CON is not written)",
                )
            }
            val dpBytes = dpFile.readBytes()
            if (!sha256(dpBytes).equals(plan.dpFileList.sha256, ignoreCase = true)) {
                throw InstallVerifyException("staged DpFileList.bin SHA-256 differs from the scan")
            }
            val entries = try {
                DpFileListParser.parse(dpBytes)
            } catch (e: StfsException) {
                throw InstallVerifyException("staged DpFileList.bin: ${e.message}", e)
            }
            for (record in entries) {
                if (byName[record.name.lowercase(Locale.US)] == null) {
                    throw InstallVerifyException(
                        "DpFileList.bin references ${record.name}, which was not staged",
                    )
                }
            }
            for (cpk in plan.cpks) {
                val staged = byName[cpk.innerName.lowercase(Locale.US)]
                    ?: throw InstallVerifyException("${cpk.innerName} was not staged")
                if (staged.length() != cpk.size || !hasCpkMagic(staged)) {
                    throw InstallVerifyException(
                        "${cpk.innerName}: staged size or CPK magic is wrong",
                    )
                }
            }
            val allowed = HashSet<String>()
            allowed.add("dpfilelist.bin")
            allowed.add(".xcontent")
            plan.cpks.forEach { allowed.add(it.innerName.lowercase(Locale.US)) }
            files.firstOrNull { it.name.lowercase(Locale.US) !in allowed }?.let {
                throw InstallVerifyException("unexpected file in the PATCH staging: ${it.name}")
            }
        }

        for (slot in selection.slots()) {
            if (!slot.isSave) continue
            val save = plan.saves.firstOrNull { InstallSlot.forSave(it.slot) == slot }
                ?: throw InstallVerifyException("no ${slot.targetName} save in the plan")
            val dir = File(workRoot, slot.relativePath)
            if (!dir.isDirectory) throw InstallVerifyException("no staged ${slot.targetName}")
            val dataBin = File(dir, "data.bin")
            if (!dataBin.isFile || dataBin.length() != save.dataBinSize) {
                throw InstallVerifyException("${slot.targetName}/data.bin has the wrong size")
            }
            val actual = sha256File(dataBin, null, cancelled = { false })
            if (!actual.equals(save.dataBinSha256, ignoreCase = true)) {
                throw InstallVerifyException("${slot.targetName}/data.bin SHA-256 differs")
            }
            val header = ByteArray(SavePayloadParser.MIN_HEADER_BYTES)
            dataBin.inputStream().use { it.readFully(header) }
            val payload = try {
                SavePayloadParser.parse(header, dataBin.length())
            } catch (e: StfsException) {
                throw InstallVerifyException("${slot.targetName}/data.bin: ${e.message}", e)
            }
            if (payload != save.payload) {
                throw InstallVerifyException(
                    "${slot.targetName}/data.bin has the wrong version or counts",
                )
            }
            if (save.dataInfName != null && !File(dir, "data.inf").isFile) {
                throw InstallVerifyException("${slot.targetName}/data.inf is missing")
            }
            if (save.dataPngSize != null) {
                val png = File(dir, "data.png")
                if (!png.isFile || png.length() != save.dataPngSize) {
                    throw InstallVerifyException("${slot.targetName}/data.png is wrong")
                }
            }
            val allowed = setOf("data.bin", "data.inf", "data.png", ".xcontent")
            dir.listFiles()?.filter { it.isFile }?.firstOrNull {
                it.name.lowercase(Locale.US) !in allowed
            }?.let {
                throw InstallVerifyException(
                    "unexpected file in ${slot.targetName}: ${it.name}",
                )
            }
        }
    }

    private fun validatePlan(plan: PatchPlan, selection: InstallSelection): String? {
        if (selection.patch) {
            if (plan.cpks.isEmpty()) return "the plan has no CPKs to install"
            val names = HashSet<String>()
            for (cpk in plan.cpks) {
                if (!isSafePatchFileName(cpk.innerName) ||
                    !cpk.innerName.endsWith(".cpk", ignoreCase = true)
                ) {
                    return "unsafe CPK name: ${cpk.innerName}"
                }
                if (!names.add(cpk.innerName.lowercase(Locale.US))) {
                    return "duplicate CPK name: ${cpk.innerName}"
                }
                if (cpk.size <= 0) return "${cpk.innerName} is empty"
            }
            if (!isSafePatchFileName(plan.dpFileList.innerName)) {
                return "unsafe DpFileList name: ${plan.dpFileList.innerName}"
            }
            if (plan.dpFileList.size <= 0) return "the DpFileList is empty"
            val listed = HashSet<String>()
            for (record in plan.dpFileList.entries) {
                if (!listed.add(record.name.lowercase(Locale.US))) {
                    return "DpFileList lists ${record.name} twice"
                }
                if (names.none { it == record.name.lowercase(Locale.US) }) {
                    return "DpFileList references ${record.name}, which the plan lacks"
                }
            }
        }
        for (save in plan.saves) {
            val slot = InstallSlot.forSave(save.slot)
            if (!selection.includes(slot)) continue
            if (save.targetName != slot.targetName) {
                return "${save.slot} is routed to ${save.targetName}, expected ${slot.targetName}"
            }
            if (save.dataBinSize <= 0) return "${slot.targetName}: data.bin is empty"
            if (!HEX64.matches(save.dataBinSha256)) {
                return "${slot.targetName}: the plan has no valid data.bin SHA-256"
            }
            if (save.outerSize < 0) return "${slot.targetName}: the CON size is unknown"
        }
        val paths = workItems(plan, selection).map { it.path }
        if (paths.size != paths.distinct().size) {
            return "the plan reuses one source entry for two targets"
        }
        return null
    }

    // ---- transaction -----------------------------------------------------

    private fun buildJournal(
        plan: PatchPlan,
        selection: InstallSelection,
        policy: InstallBackupPolicy,
        workRoot: File,
        id: String,
    ): InstallJournal {
        val stamp = timestamp()
        val slots = ArrayList<JournalSlot>()
        for (slot in selection.slots()) {
            val active = target(slot)
            val hadActive = active.exists()
            // NONE still moves the old slot into the work folder before it
            // deletes it: a rename is atomic, so a process death can leave the
            // slot absent but never half-deleted. A failed run can put it back.
            val backup = if (hadActive) {
                when (policy) {
                    InstallBackupPolicy.NONE -> File(workRoot, "discard-${slot.key}")
                    else -> newBackupPath(slot, stamp)
                }
            } else {
                null
            }
            val existing = if (policy == InstallBackupPolicy.KEEP && hadActive) {
                newestBackup(slot)
            } else {
                null
            }
            slots.add(
                JournalSlot(
                    slot = slot,
                    state = SlotState.PENDING,
                    hadActive = hadActive,
                    target = slot.relativePath,
                    staged = relative(File(workRoot, slot.relativePath)),
                    backup = backup?.let { relative(it) },
                    previousBackup = existing?.let { relative(it) },
                    previousBackupTemp = existing?.let {
                        relative(File(workRoot, "previous-backup-${slot.key}"))
                    },
                ),
            )
        }
        return InstallJournal(
            id = id,
            op = JournalOp.INSTALL,
            phase = JournalPhase.STAGING_READY,
            slots = slots,
            createdAt = System.currentTimeMillis(),
            work = relative(workRoot),
        )
    }

    private fun swap(journal: InstallJournal, policy: InstallBackupPolicy) {
        journal.phase = JournalPhase.SWAPPING
        journal.write(journalFile)
        for (record in journal.slots) {
            val active = resolve(record.target)
            val staged = record.staged?.let(::resolve)
                ?: throw InstallVerifyException("journal slot ${record.slot.key} has no staging")
            if (policy == InstallBackupPolicy.KEEP) {
                if (record.hadActive) {
                    val existing = record.previousBackup?.let(::resolve)
                    val temp = record.previousBackupTemp?.let(::resolve)
                    if (existing != null && existing.exists()) {
                        if (temp == null) {
                            throw InstallVerifyException("no place to keep the old backup")
                        }
                        temp.parentFile?.mkdirs()
                        if (!existing.renameTo(temp)) {
                            throw IllegalStateException(
                                "cannot set ${existing.path} aside",
                            )
                        }
                        moveSidecar(existing, temp)
                    }
                    // One backup per slot: older leftovers go now.
                    deleteOldBackups(record.slot)
                }
            } else {
                deleteOldBackups(record.slot)
            }
            if (record.hadActive) {
                record.state = SlotState.OLD_MOVING
                journal.write(journalFile)
                val backup = record.backup?.let(::resolve)
                    ?: throw InstallVerifyException("journal slot ${record.slot.key} has no backup")
                backup.parentFile?.mkdirs()
                if (policy != InstallBackupPolicy.NONE) {
                    // Before the rename: if the process dies here, recovery can
                    // still say which patch the backup holds.
                    writeBackupSidecar(record.slot, backup, journal.id)
                }
                if (!active.renameTo(backup)) {
                    throw IllegalStateException(
                        "cannot move ${active.path} to ${backup.path}",
                    )
                }
                record.state = SlotState.OLD_MOVED
                journal.write(journalFile)
                fault?.invoke(FaultPoint.OLD_MOVED, record.slot)
            }

            record.state = SlotState.NEW_MOVING
            journal.write(journalFile)
            active.parentFile?.mkdirs()
            if (!staged.renameTo(active)) {
                throw IllegalStateException(
                    "cannot move ${staged.path} to ${active.path}",
                )
            }
            record.state = SlotState.SWAPPED
            journal.write(journalFile)
            fault?.invoke(FaultPoint.NEW_MOVED, record.slot)
        }
    }

    /** Undoes an unfinished transaction. Returns true when every previous slot is back. */
    private fun rollback(journal: InstallJournal): Boolean {
        var ok = true
        for (record in journal.slots.asReversed()) {
            try {
                val active = resolve(record.target)
                val staged = record.staged?.let(::resolve)
                val backup = record.backup?.let(::resolve)
                when (journal.op) {
                    JournalOp.INSTALL -> when (record.state) {
                        SlotState.SWAPPED, SlotState.NEW_MOVING -> {
                            if (active.exists() && !active.deleteRecursively()) ok = false
                            if (backup != null && backup.exists()) {
                                active.parentFile?.mkdirs()
                                if (!backup.renameTo(active)) ok = false
                            }
                        }

                        SlotState.OLD_MOVED, SlotState.OLD_MOVING -> {
                            if (!active.exists() && backup != null && backup.exists()) {
                                active.parentFile?.mkdirs()
                                if (!backup.renameTo(active)) ok = false
                            }
                        }

                        SlotState.PENDING -> Unit
                    }

                    JournalOp.RESTORE -> when (record.state) {
                        SlotState.SWAPPED, SlotState.NEW_MOVING -> {
                            if (active.exists()) {
                                if (backup != null) {
                                    backup.parentFile?.mkdirs()
                                    if (backup.exists()) backup.deleteRecursively()
                                    if (!active.renameTo(backup)) ok = false
                                } else if (!active.deleteRecursively()) {
                                    ok = false
                                }
                            }
                            if (staged != null && staged.exists()) {
                                active.parentFile?.mkdirs()
                                if (!staged.renameTo(active)) ok = false
                            }
                        }

                        SlotState.OLD_MOVED, SlotState.OLD_MOVING -> {
                            if (!active.exists() && staged != null && staged.exists()) {
                                active.parentFile?.mkdirs()
                                if (!staged.renameTo(active)) ok = false
                            }
                        }

                        SlotState.PENDING -> Unit
                    }

                    JournalOp.REMOVE -> when (record.state) {
                        SlotState.SWAPPED, SlotState.NEW_MOVING,
                        SlotState.OLD_MOVED, SlotState.OLD_MOVING,
                        -> {
                            if (!active.exists() && backup != null && backup.exists()) {
                                active.parentFile?.mkdirs()
                                if (!backup.renameTo(active)) ok = false
                            }
                        }

                        SlotState.PENDING -> Unit
                    }
                }
                if (journal.op == JournalOp.INSTALL && !restorePreviousBackup(record)) {
                    ok = false
                }
                // A sidecar whose backup never appeared would otherwise be a
                // stray file in patch-backups/<slot> forever.
                if (backup != null && !backup.exists()) {
                    runCatching { PatchStateStore.sidecar(backup).delete() }
                }
            } catch (_: Throwable) {
                ok = false
            }
        }
        journal.work?.let { runCatching { resolve(it).deleteRecursively() } }
        if (ok) runCatching { journalFile.delete() }
        return ok
    }

    private fun recoverLocked(): RecoveryOutcome {
        if (!gameRoot.isDirectory) return RecoveryOutcome(RecoveryReport())
        var restored = emptyList<InstallSlot>()
        var discarded = false
        var discardedBytes = 0L
        var committedKept = false
        var journalInstallId: String? = null
        val problems = ArrayList<String>()

        var rollbackFailed = false
        if (journalFile.isFile) {
            val journal = try {
                InstallJournal.read(journalFile)
            } catch (t: Throwable) {
                problems.add("unreadable journal: ${t.message}")
                runCatching {
                    journalFile.renameTo(
                        File(gameRoot, "patch-journal.bad-${timestamp()}.properties"),
                    )
                }
                null
            }
            if (journal != null) {
                journalInstallId = journal.id
                if (journal.phase == JournalPhase.COMMITTED) {
                    committedKept = true
                    // A RESTORE that died between its commit and its state
                    // write still has its sidecar: put the name back now.
                    if (journal.op == JournalOp.RESTORE) {
                        restoreCommittedProvenance(journal)
                    }
                    runCatching { journalFile.delete() }
                } else {
                    val ok = rollback(journal)
                    if (!ok) {
                        rollbackFailed = true
                        problems.add("a previous transaction could not be fully rolled back")
                    }
                }
            }
        }

        if (!rollbackFailed && stagingRoot.exists()) {
            stagingRoot.listFiles()?.forEach {
                val bytes = sizeOf(it)
                if (it.deleteRecursively()) {
                    discarded = true
                    discardedBytes = InstallSpace.safeAdd(discardedBytes, bytes)
                }
            }
            if (stagingRoot.listFiles()?.isEmpty() == true) stagingRoot.delete()
        }

        val restoredBak = ArrayList<InstallSlot>()
        for (slot in InstallSlot.values()) {
            val target = target(slot)
            if (target.exists()) continue
            val bak = File(target.parentFile, target.name + ".bak")
            if (bak.isDirectory) {
                target.parentFile?.mkdirs()
                if (bak.renameTo(target)) {
                    restoredBak.add(slot)
                    PatchStateStore.clearSlot(gameRoot, slot)
                } else {
                    problems.add("could not restore ${bak.path}")
                }
            }
        }
        restored = restoredBak
        return RecoveryOutcome(
            report = RecoveryReport(
                restoredSlots = restored,
                discardedStaging = discarded,
                committedInstallKept = committedKept,
                problems = problems,
            ),
            installId = journalInstallId,
            discardedStagingBytes = discardedBytes,
        )
    }

    /**
     * `recovery.properties` next to the journal. The in-memory report dies with
     * the process, and the recovery worth explaining is exactly the one nobody
     * is watching when it happens: a killed install, put back on the next
     * launch.
     */
    private fun writeRecoveryReport(outcome: RecoveryOutcome) {
        runCatching {
            val props = Properties()
            props["version"] = "1"
            props["time"] = System.currentTimeMillis().toString()
            outcome.installId?.let { props["installId"] = it }
            props["restoredSlots"] = outcome.report.restoredSlots.joinToString(",") { it.key }
            props["discardedStagingBytes"] = outcome.discardedStagingBytes.toString()
            props["committedInstallKept"] = outcome.report.committedInstallKept.toString()
            props["problems"] = outcome.report.problems
                .joinToString(" | ") { it.replace('\n', ' ') }
            val file = File(gameRoot, RECOVERY_FILE)
            val temp = File(gameRoot, RECOVERY_FILE + TEMP_SUFFIX)
            FileOutputStream(temp).use { out ->
                props.store(out, "PES 2018 patch recovery")
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    // ---- small pieces ----------------------------------------------------

    private fun workItems(plan: PatchPlan, selection: InstallSelection): List<WorkItem> {
        val items = ArrayList<WorkItem>()
        if (selection.patch) {
            plan.cpks.forEach { items.add(WorkItem.CpkWork(it.sourcePath, it)) }
            items.add(WorkItem.DpWork(plan.dpFileList.sourcePath, plan.dpFileList))
        }
        for (save in plan.saves) {
            val slot = InstallSlot.forSave(save.slot)
            if (!selection.includes(slot)) continue
            items.add(WorkItem.SaveWork(save.sourcePath, slot, save))
        }
        return items
    }

    private fun workTotal(plan: PatchPlan, selection: InstallSelection, space: InstallSpace): Long {
        // A run reads every outer CON into the spool and then writes its inner
        // files. Counting only the largest CON (space.spoolBytes, kept for the
        // free-space check) made the tracker reach its 85% staging cap long
        // before the work ended, so the bar sat at 85%.
        var total = space.stagingBytes
        if (plan.sourceKind != PatchSourceKind.FOLDER) {
            if (selection.patch) {
                for (cpk in plan.cpks) {
                    total = InstallSpace.safeAdd(total, cpk.outerSize)
                }
            }
            for (save in plan.saves) {
                if (selection.includes(InstallSlot.forSave(save.slot))) {
                    total = InstallSpace.safeAdd(total, save.outerSize)
                }
            }
        } else {
            // A folder source reads files directly; the existing save read is
            // the only source-side work not already in stagingBytes.
            for (save in plan.saves) {
                if (selection.includes(InstallSlot.forSave(save.slot))) {
                    total = InstallSpace.safeAdd(total, save.outerSize)
                }
            }
        }
        return total.coerceAtLeast(1)
    }

    private fun installedState(
        plan: PatchPlan,
        selection: InstallSelection,
    ): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        val now = System.currentTimeMillis().toString()
        if (selection.patch) {
            values["patch.name"] = plan.name
            values["patch.source"] = plan.sourceKind.name
            values["patch.sourceLabel"] = plan.sourceLabel
            values["patch.installedAt"] = now
            values["patch.cpks"] = plan.cpks.size.toString()
            values["patch.bytes"] = plan.cpkBytes.toString()
            // The DpFileList and the CPK sizes are the patch's identity. Without
            // them a later read cannot tell an intact install from a truncated
            // one, or from a folder somebody edited by hand.
            values["patch.dpBytes"] = plan.dpFileList.size.toString()
            values["patch.dpSha256"] = plan.dpFileList.sha256
            values["patch.manifest"] = cpkManifest(plan)
        }
        if (selection.editedData) {
            val save = plan.saves.firstOrNull { it.slot == SaveSlot.EDIT }
            if (save != null) {
                values["edit.name"] = save.dataInfName ?: "Edit save"
                values["edit.version"] = save.payload.version.toString()
                values["edit.players"] = save.payload.players.toString()
                values["edit.teams"] = save.payload.teams.toString()
                values["edit.bytes"] = save.dataBinSize.toString()
                values["edit.installedAt"] = now
            }
        }
        if (selection.system) {
            values["system.installedAt"] = now
            val save = plan.saves.firstOrNull { it.slot == SaveSlot.SYSTEM }
            if (save != null) {
                values["system.dataSha256"] = save.dataBinSha256
                values["system.bytes"] = save.dataBinSize.toString()
            }
        }
        return values
    }

    /** `name:size`, one CPK per line: the completeness check in PATCHES. */
    private fun cpkManifest(plan: PatchPlan): String =
        plan.cpks.joinToString("\n") { "${it.innerName}:${it.size}" }

    /**
     * Describes the slot that is about to move into [backup]: the provenance
     * this app wrote for it, plus what is really on disk now. Written before
     * the rename, so even a crash leaves the backup self-describing. A slot
     * installed by hand has no provenance; the measured fields still tell
     * RESTORE what it holds.
     */
    private fun writeBackupSidecar(slot: InstallSlot, backup: File, installId: String) {
        runCatching {
            val props = Properties()
            val current = PatchStateStore.read(gameRoot)
            val prefix = PatchStateStore.prefix(slot)
            current.stringPropertyNames()
                .filter { it.startsWith(prefix) }
                .forEach { props[it] = current.getProperty(it) }

            val dir = target(slot)
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            props["backup.slot"] = slot.key
            // Which install moved this data aside. RESTORE keeps the sidecar
            // alive, so an old backup can still be traced to its run.
            props["backup.installId"] = installId
            props["backup.createdAt"] = System.currentTimeMillis().toString()
            props["backup.bytes"] = files.sumOf { it.length() }.toString()
            props["backup.files"] = files.size.toString()
            if (slot == InstallSlot.PATCH) {
                val dp = files.firstOrNull { it.name.equals(DP_FILE_LIST, true) }
                if (dp != null) {
                    props["backup.dpBytes"] = dp.length().toString()
                    if (dp.length() <= MAX_SIDECAR_HASH_BYTES) {
                        props["backup.dpSha256"] = sha256File(dp, null) { false }
                    }
                }
            } else {
                val data = File(dir, "data.bin")
                if (data.isFile) {
                    props["backup.dataBytes"] = data.length().toString()
                    if (data.length() <= MAX_SIDECAR_HASH_BYTES) {
                        props["backup.dataSha256"] = sha256File(data, null) { false }
                    }
                }
            }
            PatchStateStore.writeSidecar(backup, props)
        }
    }

    /** Moves a backup's sidecar with the backup itself, when a backup is set aside or put back. */
    private fun moveSidecar(from: File, to: File) {
        val source = PatchStateStore.sidecar(from)
        if (!source.isFile) return
        to.parentFile?.mkdirs()
        val target = PatchStateStore.sidecar(to)
        runCatching {
            if (target.exists()) target.delete()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    /**
     * RESTORE puts the backup's own provenance back. Without a sidecar (a
     * backup made before sidecars existed, or one copied in by hand) the slot
     * keys are cleared, as before.
     */
    private fun applyRestoredState(slot: InstallSlot, sidecar: Properties) {
        val prefix = PatchStateStore.prefix(slot)
        val values = LinkedHashMap<String, String>()
        sidecar.stringPropertyNames()
            .filter { it.startsWith(prefix) }
            .forEach { values[it] = sidecar.getProperty(it) }
        if (values.isEmpty()) {
            PatchStateStore.clearSlot(gameRoot, slot)
            return
        }
        val existing = PatchStateStore.read(gameRoot).stringPropertyNames()
            .filter { it.startsWith(prefix) }
            .toSet()
        PatchStateStore.update(gameRoot, values, existing - values.keys)
    }

    /**
     * A RESTORE journal that reached COMMITTED but whose process died before
     * the state write. The backup was already moved into place, so only its
     * recorded provenance is missing; reading the sidecar again is safe to
     * repeat.
     */
    private fun restoreCommittedProvenance(journal: InstallJournal) {
        for (record in journal.slots) {
            val backup = record.backup ?: continue
            val sidecar = try {
                resolve("$backup.properties")
            } catch (_: Throwable) {
                continue
            }
            if (!sidecar.isFile) continue
            val props = Properties()
            runCatching { sidecar.inputStream().use { props.load(it) } }
            runCatching { applyRestoredState(record.slot, props) }
            runCatching { sidecar.delete() }
            runCatching {
                sidecar.parentFile?.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".properties") }
                    ?.forEach { it.delete() }
            }
            runCatching {
                val dir = sidecar.parentFile
                if (dir != null && dir.listFiles().isNullOrEmpty()) dir.delete()
            }
        }
    }

    private fun preserveXContent(workRoot: File, slot: InstallSlot) {
        val source = File(target(slot), ".xcontent")
        if (!source.isFile) return
        val target = stagedFile(workRoot, slot, ".xcontent")
        source.copyTo(target, overwrite = true)
    }

    private fun stagedFile(workRoot: File, slot: InstallSlot, name: String): File {
        if (!isSafePatchFileName(name)) {
            throw InstallVerifyException("unsafe file name: $name")
        }
        val dir = File(workRoot, slot.relativePath)
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("cannot create ${dir.path}")
        }
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) {
            throw InstallVerifyException("$name escapes ${slot.relativePath}")
        }
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("cannot replace ${file.path}")
        }
        return file
    }

    private fun hasCpkMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val bytes = ByteArray(4)
        file.inputStream().use { it.readFully(bytes) }
        return String(bytes, Charsets.ISO_8859_1) == "CPK "
    }

    private fun spool(
        input: InputStream,
        target: File,
        expected: Long,
        tracker: Tracker,
        detail: String,
        cancelled: () -> Boolean,
    ): String {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(target).use { raw ->
            BufferedOutputStream(raw, COPY_BUFFER).use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    if (cancelled()) throw InstallCancelledException()
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    total += n
                    tracker.add(n.toLong(), detail)
                }
            }
        }
        if (expected >= 0 && total != expected) {
            throw InstallVerifyException(
                "$detail: read $total of $expected bytes from the source",
            )
        }
        return digest.digest().toHex()
    }

    private fun sha256File(
        file: File,
        tracker: Tracker?,
        cancelled: () -> Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                if (cancelled()) throw InstallCancelledException()
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                tracker?.add(n.toLong(), file.name)
            }
        }
        return digest.digest().toHex()
    }

    private fun deleteOldBackups(slot: InstallSlot) {
        val dir = File(backupRoot, slot.key)
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newestBackup(slot: InstallSlot): File? =
        File(backupRoot, slot.key).listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }

    /** Puts the RESTORE PREVIOUS entry back after a failed KEEP install. */
    private fun restorePreviousBackup(record: JournalSlot): Boolean {
        val original = record.previousBackup?.let(::resolve) ?: return true
        val temp = record.previousBackupTemp?.let(::resolve) ?: return true
        if (!temp.exists()) return true
        original.parentFile?.mkdirs()
        if (original.exists() && !original.deleteRecursively()) return false
        if (!temp.renameTo(original)) return false
        moveSidecar(temp, original)
        return true
    }

    private fun newBackupPath(slot: InstallSlot, stamp: String): File {
        val dir = File(backupRoot, slot.key)
        var candidate = File(dir, stamp)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stamp-$index")
            index++
        }
        return candidate
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun cleanupWork(workRoot: File) {
        runCatching { workRoot.deleteRecursively() }
        cleanupEmptyStaging()
    }

    private fun cleanupEmptyStaging() {
        runCatching {
            if (stagingRoot.isDirectory && stagingRoot.listFiles()?.isEmpty() == true) {
                stagingRoot.delete()
            }
        }
    }

    private fun relative(file: File): String = file.relativeTo(gameRoot).invariantSeparatorsPath

    private fun resolve(path: String): File {
        if (File(path).isAbsolute) throw InstallVerifyException("absolute path in journal: $path")
        val root = gameRoot.canonicalFile
        val file = File(root, path).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw InstallVerifyException("path escapes the game root: $path")
        }
        return file
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ---- lock ------------------------------------------------------------

    private fun acquireLock(): Boolean {
        if (!PROCESS_LOCK.compareAndSet(false, true)) return false
        return try {
            if (!gameRoot.isDirectory) {
                PROCESS_LOCK.set(false)
                return false
            }
            // A process that owned this file is gone; a live install holds
            // PROCESS_LOCK and never reaches here.
            if (lockFile.exists()) lockFile.delete()
            lockFile.writeText("started=${System.currentTimeMillis()}\n")
            ACTIVE_ROOT = gameRoot.canonicalFile
            true
        } catch (t: Throwable) {
            PROCESS_LOCK.set(false)
            ACTIVE_ROOT = null
            runCatching { lockFile.delete() }
            throw t
        }
    }

    private fun unlock() {
        runCatching { lockFile.delete() }
        ACTIVE_ROOT = null
        PROCESS_LOCK.set(false)
    }

    private fun releaseProcessLockOnly() {
        ACTIVE_ROOT = null
        PROCESS_LOCK.set(false)
    }

    // ---- types -----------------------------------------------------------

    /**
     * What recovery did, plus the facts [RecoveryReport] does not carry: which
     * install the journal belonged to and how much staging was thrown away.
     */
    private data class RecoveryOutcome(
        val report: RecoveryReport,
        val installId: String? = null,
        val discardedStagingBytes: Long = 0L,
    )

    private sealed class WorkItem(val path: String, val slot: InstallSlot) {
        abstract val expectedOuter: Long

        class CpkWork(path: String, val cpk: PlannedCpk) :
            WorkItem(path, InstallSlot.PATCH) {
            override val expectedOuter: Long get() = cpk.outerSize
        }

        class DpWork(path: String, val dp: PlannedDpFileList) :
            WorkItem(path, InstallSlot.PATCH) {
            // The outer CON size is deliberately not trusted here: some ZIP64
            // entries report 0 until the stream is consumed. Read to EOF, then
            // verify the inner DpFileList size/SHA-256 from the STFS table.
            override val expectedOuter: Long get() = -1L
        }

        class SaveWork(path: String, slot: InstallSlot, val save: PlannedSave) :
            WorkItem(path, slot) {
            override val expectedOuter: Long get() = save.outerSize
        }
    }

    private class InstallVerifyException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private class CheckingOutputStream(
        out: OutputStream,
        private val tracker: Tracker,
        private val detail: String,
        private val cancelled: () -> Boolean,
    ) : FilterOutputStream(BufferedOutputStream(out, COPY_BUFFER)) {

        override fun write(b: Int) {
            checkCancel()
            out.write(b)
            tracker.add(1, detail)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkCancel()
            out.write(b, off, len)
            tracker.add(len.toLong(), detail)
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.flush()
            out.close()
        }

        private fun checkCancel() {
            if (cancelled()) throw InstallCancelledException()
        }
    }

    private class Tracker(
        private val total: Long,
        private val progress: (InstallProgress) -> Unit,
        private val cancelled: () -> Boolean,
    ) {
        private var processed = 0L
        private var lastPercent = -1
        private var lastDetail = ""

        fun add(bytes: Long, detail: String) {
            if (cancelled()) throw InstallCancelledException()
            processed = InstallSpace.safeAdd(processed, bytes)
            val percent = (processed * 85L / total).toInt().coerceIn(0, 85)
            if (percent != lastPercent || detail != lastDetail) {
                lastPercent = percent
                lastDetail = detail
                // The same counters the percent comes from, so a reader can
                // show real bytes instead of guessing them back out of a bar
                // that is capped at 85 while staging is still running.
                progress(
                    InstallProgress(
                        InstallPhase.STAGING,
                        percent,
                        detail,
                        processed,
                        total,
                    ),
                )
            }
        }

        /**
         * A phase that is not itself byte work. The counters still belong to
         * the run: processed is everything read and written so far and total is
         * the work total the staging cap is computed against, so the bytes do
         * not fall back to 0 at verifying, swapping or finishing.
         */
        fun phase(phase: InstallPhase, percent: Int, detail: String = "") {
            lastPercent = percent
            lastDetail = detail
            progress(InstallProgress(phase, percent, detail, processed, total))
        }
    }

    companion object {
        private const val LOCK_NAME = ".patch-install.lock"
        private const val COPY_BUFFER = 1 shl 20
        private const val INF_RESERVE = 4096L
        private const val DP_FILE_LIST = "DpFileList.bin"

        /** `recovery.properties` beside the journal: what the last recovery did. */
        private const val RECOVERY_FILE = "recovery.properties"
        private const val TEMP_SUFFIX = ".tmp"

        /** A journal id and a staging folder name: no separators, no dots-dots. */
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,64}")

        /** A save's data.bin is a few MB; anything larger is not hashed just for a sidecar. */
        private const val MAX_SIDECAR_HASH_BYTES = 64L * 1024 * 1024
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
        private val PROCESS_LOCK = AtomicBoolean(false)

        @Volatile
        private var ACTIVE_ROOT: File? = null

        fun busy(): Boolean = PROCESS_LOCK.get()

        fun lockFile(root: File): File = File(root, LOCK_NAME)

        /** True when a slot transaction is running or a journal is still waiting for recovery. */
        fun pending(root: File): Boolean =
            PROCESS_LOCK.get() || lockFile(root).isFile || File(root, "patch-journal.properties").isFile
    }
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val n = read(target, offset, target.size - offset)
        if (n <= 0) break
        offset += n
    }
}

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_FORMAT[value ushr 4])
        out.append(HEX_FORMAT[value and 0x0F])
    }
    return out.toString()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private val HEX_FORMAT = "0123456789abcdef".toCharArray()
