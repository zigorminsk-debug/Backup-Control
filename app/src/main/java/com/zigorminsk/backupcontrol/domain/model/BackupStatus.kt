package com.zigorminsk.backupcontrol.domain.model

enum class BackupStatus {
    SUCCESS,
    WARNING,
    ERROR,
    UNKNOWN;

    val labelRu: String
        get() = when (this) {
            SUCCESS -> "Успешно"
            WARNING -> "Предупреждение"
            ERROR -> "Ошибка"
            UNKNOWN -> "Не определён"
        }
}

/**
 * Состояние организации в жизненном цикле контроля:
 * - PENDING_REVIEW: собрана за 6 месяцев, ожидает редактирования и сохранения пользователем;
 * - SAVED: сохранена пользователем (или добавлена автоматически при появлении нового отправителя после сохранения списка) — контролируется;
 * - EXCLUDED: исключена/удалена пользователем — не контролируется и не возвращается из писем повторно.
 */
enum class OrganizationApprovalState {
    PENDING_REVIEW,
    SAVED,
    EXCLUDED;

    val labelRu: String
        get() = when (this) {
            PENDING_REVIEW -> "На проверке"
            SAVED -> "Сохранена"
            EXCLUDED -> "Исключена"
        }
}

enum class OrganizationHealth {
    ERROR,
    WARNING,
    MISSING_MAIL,
    HEALTHY,
    PAUSED,
    PENDING_REVIEW,
    EXCLUDED
}

enum class DashboardFilter(val labelRu: String) {
    ALL("Все"),
    PROBLEMS("Проблемы"),
    ERRORS("Ошибки"),
    MISSING("Нет писем"),
    NEW_ADDED("Новые"),
    HEALTHY("В норме"),
    PAUSED("Пауза")
}

enum class OrganizationGroupFilter(val labelRu: String) {
    SAVED("Сохранённые"),
    NEW_ADDED("Новые"),
    PENDING("На проверке"),
    EXCLUDED("Исключённые")
}
