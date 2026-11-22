package by.csl.backupcontrol

import by.csl.backupcontrol.domain.analyzer.BackupStatusAnalyzer
import by.csl.backupcontrol.domain.model.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupStatusAnalyzerTest {

    @Test
    fun `veeam subject tags are classified accurately`() {
        val ok = BackupStatusAnalyzer.analyze("[Success] Backup Job 1C_SQL (12 VMs)", "Job finished")
        assertEquals(BackupStatus.SUCCESS, ok.status)

        val warn = BackupStatusAnalyzer.analyze("[Warning] Backup Job FileServer", "1 VM with warning")
        assertEquals(BackupStatus.WARNING, warn.status)

        val err = BackupStatusAnalyzer.analyze("[Failed] Backup Job DC01", "Error: Snapshot failed")
        assertEquals(BackupStatus.ERROR, err.status)
    }

    @Test
    fun `cobian zero errors is SUCCESS and non-zero errors is ERROR`() {
        val cobianOk = BackupStatusAnalyzer.analyze(
            subject = "Cobian Backup 11 - Отчёт о резервном копировании",
            body = "Резервное копирование завершено. Ошибок: 0, обработано файлов: 1524."
        )
        assertEquals(BackupStatus.SUCCESS, cobianOk.status)

        val cobianErr = BackupStatusAnalyzer.analyze(
            subject = "Cobian Backup 11 - Отчёт о резервном копировании",
            body = "Резервное копирование завершено. Ошибок: 2, обработано файлов: 1524."
        )
        assertEquals(BackupStatus.ERROR, cobianErr.status)

        val reflectorOk = BackupStatusAnalyzer.analyze(
            subject = "Cobian Reflector report",
            body = "Backup done. Errors: 0. Warnings: 0. Backed up files: 45."
        )
        assertEquals(BackupStatus.SUCCESS, reflectorOk.status)
    }

    @Test
    fun `russian 1c and acronis reports are classified accurately`() {
        val acronisFail = BackupStatusAnalyzer.analyze(
            subject = "[ABR11.5] Задача 'Полное копирование сервера' не выполнена",
            body = "Код ошибки: 2. Недостаточно места на диске."
        )
        assertEquals(BackupStatus.ERROR, acronisFail.status)

        val script1cOk = BackupStatusAnalyzer.analyze(
            subject = "Архивация базы 1С Бухгалтерия",
            body = "Архив успешно создан: D:\\Backup\\1c_2026_11_22.7z. Код возврата: 0"
        )
        assertEquals(BackupStatus.SUCCESS, script1cOk.status)

        val script1cErr = BackupStatusAnalyzer.analyze(
            subject = "Архивация базы 1С Бухгалтерия",
            body = "Не удалось создать архив информационной базы. Exit code: 1"
        )
        assertEquals(BackupStatus.ERROR, script1cErr.status)
    }

    @Test
    fun `normalizeJobKey matches same job across different statuses and dates`() {
        val failedKey = BackupStatusAnalyzer.normalizeJobKey("[Failed] Backup Job SQL_1C 2026-11-21 (2 errors)")
        val successKey = BackupStatusAnalyzer.normalizeJobKey("[Success] Backup Job SQL_1C 2026-11-22")
        assertEquals(failedKey, successKey)
    }
}
