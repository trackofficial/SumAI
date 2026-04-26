package com.example.sumai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LectureSummarizer(private val llamaBridge: LlamaBridge) {

    companion object {
        private const val TAG = "LectureSummarizer"
    }

    /**
     * Создание конспекта лекции через LLM
     */
    suspend fun createSummary(
        rawText: String,
        internetSearch: suspend (String) -> String?,
        onStatus: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {

        onStatus("🔍 Анализ структуры лекции...")

        // Шаг 1: Извлечение основных тем
        val topics = extractTopics(rawText)
        onStatus("📚 Выявлено тем: ${topics.size}")

        // Шаг 2: Поиск информации по каждой теме в интернете
        onStatus("🌐 Поиск дополнительной информации...")
        val additionalInfo = mutableMapOf<String, String>()
        for (topic in topics.take(3)) { // Максимум 3 темы для поиска
            val searchResult = internetSearch("$topic лекция определение")
            if (searchResult != null) {
                additionalInfo[topic] = searchResult
                onStatus("✅ Найдена информация по теме: ${topic.take(30)}...")
            }
        }

        // Шаг 3: Выявление противоречий
        onStatus("🔍 Анализ противоречий в тексте...")
        val contradictions = findContradictions(rawText)
        if (contradictions.isNotEmpty()) {
            onStatus("⚠️ Найдено противоречий: ${contradictions.size}")
        }

        // Шаг 4: Генерация полного конспекта через LLM
        onStatus("✍️ Создание конспекта нейросетью Lucy...")
        val summary = generateSummaryWithLLM(
            rawText = rawText,
            topics = topics,
            additionalInfo = additionalInfo,
            contradictions = contradictions
        )

        onStatus("✅ Конспект готов!")
        summary
    }

    /**
     * Извлечение основных тем из текста через LLM
     */
    private suspend fun extractTopics(text: String): List<String> {
        val prompt = """
            Проанализируй следующий текст лекции и выдели основные темы.
            Верни ТОЛЬКО список тем, одну тему на строку.
            Не добавляй нумерацию, только темы.
            
            Текст:
            ${text.take(3000)}
            
            Темы:
        """.trimIndent()

        val response = llamaBridge.generate(prompt)
        return response.lines()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .take(5)
    }

    /**
     * Поиск противоречий в тексте через LLM
     */
    private suspend fun findContradictions(text: String): List<Contradiction> {
        val prompt = """
            Проанализируй текст на наличие логических противоречий, 
            несостыковок или ошибок. Для каждого найденного противоречия укажи:
            1. Что утверждается в одном месте
            2. Что утверждается в другом месте
            3. Почему это противоречие
            
            Текст:
            ${text.take(3000)}
            
            Если противоречий нет, верни "НЕТ".
        """.trimIndent()

        val response = llamaBridge.generate(prompt)
        if (response.contains("НЕТ", ignoreCase = true)) {
            return emptyList()
        }

        // Парсим противоречия (упрощённо)
        return listOf(Contradiction(
            description = response.take(500),
            severity = "medium"
        ))
    }

    /**
     * Генерация полного конспекта через LLM
     */
    private suspend fun generateSummaryWithLLM(
        rawText: String,
        topics: List<String>,
        additionalInfo: Map<String, String>,
        contradictions: List<Contradiction>
    ): String {
        val prompt = buildString {
            append("Ты - эксперт по созданию учебных конспектов. Создай структурированный конспект лекции.\n\n")

            append("## ИСХОДНЫЙ ТЕКСТ ЛЕКЦИИ:\n")
            append("${rawText.take(4000)}\n\n")

            if (topics.isNotEmpty()) {
                append("## ВЫДЕЛЕННЫЕ ТЕМЫ:\n")
                topics.forEach { append("- $it\n") }
                append("\n")
            }

            if (additionalInfo.isNotEmpty()) {
                append("## ДОПОЛНИТЕЛЬНАЯ ИНФОРМАЦИЯ ИЗ ИНТЕРНЕТА:\n")
                additionalInfo.forEach { (topic, info) ->
                    append("### $topic\n")
                    append("$info\n\n")
                }
            }

            if (contradictions.isNotEmpty()) {
                append("## ВЫЯВЛЕННЫЕ ПРОТИВОРЕЧИЯ:\n")
                contradictions.forEach { contradiction ->
                    append("⚠️ ${contradiction.description}\n\n")
                }
            }

            append("""
                ## ТРЕБОВАНИЯ К КОНСПЕКТУ:
                
                1. **Структура**: Используй заголовки (#, ##, ###) и подзаголовки
                2. **Определения**: Выдели ключевые определения жирным шрифтом (**определение**)
                3. **Таблицы**: Используй таблицы для сравнения понятий
                4. **Списки**: Используй маркированные и нумерованные списки
                5. **Цитаты**: Выделяй важные мысли в цитаты (> текст)
                6. **Противоречия**: Если найдены противоречия, вынеси их в отдельный раздел
                7. **Выводы**: В конце сделай список ключевых выводов
                8. **Ссылки**: Если есть информация из интернета, добавь ссылки на источники
                
                ## ФОРМАТ ОТВЕТА (ТОЛЬКО MARKDOWN):
                
                Верни конспект в формате Markdown. Используй:
                - # Заголовок лекции
                - ## Основные темы
                - ### Подтемы
                - **определения** для ключевых терминов
                - | Таблица | Для сравнения |
                - > Важные цитаты из лекции
                - ⚠️ Внимание: для противоречий
                
                Начни прямо сейчас:
                
            """.trimIndent())
        }

        return llamaBridge.generate(prompt) { token ->
            // Можно выводить токены в реальном времени
            Log.d(TAG, "Generated token: $token")
        }
    }

    data class Contradiction(
        val description: String,
        val severity: String // "low", "medium", "high"
    )
}