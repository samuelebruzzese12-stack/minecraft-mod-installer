package com.mmi.modinstaller

import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.data.ErrorMessages
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun passwordProtectedArchive() {
        assertTrue(ErrorMessages.describe(ArchivePasswordException()).contains("password"))
    }

    @Test
    fun unsafePathsAreExplained() {
        val text = ErrorMessages.describe(ArchiveUnsafeException(listOf("../evil.txt")))
        assertTrue(text.contains("pericolosi"))
    }

    @Test
    fun limitMessageIsPassedThrough() {
        val e = LimitExceededException(FindingCode.ARCHIVE_TOO_LARGE, "Archivio troppo grande (3 GB)")
        assertEquals("Archivio troppo grande (3 GB)", ErrorMessages.describe(e))
    }

    @Test
    fun corruptAndUnsupportedFallBackToDefaults() {
        assertTrue(ErrorMessages.describe(ArchiveCorruptException("")).contains("danneggiato"))
        assertTrue(ErrorMessages.describe(ArchiveUnsupportedException(FindingCode.UNSUPPORTED_FORMAT, "")).contains("non supportato"))
    }

    @Test
    fun missingFileAndPermissionLoss() {
        assertTrue(ErrorMessages.describe(FileNotFoundException("x")).contains("non è più disponibile"))
        assertTrue(ErrorMessages.describe(SecurityException("denied")).contains("Selezionalo di nuovo"))
    }

    @Test
    fun diskFull() {
        val text = ErrorMessages.describe(IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(text.contains("Spazio insufficiente"))
    }

    @Test
    fun otherIoErrorsKeepTheirDetail() {
        val text = ErrorMessages.describe(IOException("boom"))
        assertTrue(text.contains("boom"))
    }

    @Test
    fun unexpectedErrorsNameTheirType() {
        val text = ErrorMessages.describe(IllegalStateException("qualcosa"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("qualcosa"))
    }

    @Test
    fun outOfMemory() {
        assertTrue(ErrorMessages.describe(OutOfMemoryError()).contains("Memoria insufficiente"))
    }
}
