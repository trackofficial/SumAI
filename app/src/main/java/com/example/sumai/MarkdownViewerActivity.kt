package com.example.sumai

import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat

class MarkdownViewerActivity : ComponentActivity() {

    private lateinit var tvMarkdown: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_markdown_viewer)

        tvMarkdown = findViewById(R.id.tvMarkdown)

        // Получаем текст из Intent
        val markdownText = intent.getStringExtra("markdown") ?: "Нет данных"

        // Конвертируем простой Markdown в HTML
        val htmlText = convertMarkdownToHtml(markdownText)

        // Отображаем как HTML
        tvMarkdown.text = HtmlCompat.fromHtml(htmlText, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        var html = markdown

        // Заголовки ##
        html = html.replace(Regex("## (.*?)(\n|$)")) { "<h2>${it.groupValues[1]}</h2>" }

        // Заголовки ###
        html = html.replace(Regex("### (.*?)(\n|$)")) { "<h3>${it.groupValues[1]}</h3>" }

        // Жирный текст ** **
        html = html.replace(Regex("\\*\\*(.*?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }

        // Списки -
        html = html.replace(Regex("- (.*?)(\n|$)")) { "• ${it.groupValues[1]}<br/>" }

        // Цитаты >
        html = html.replace(Regex("> (.*?)(\n|$)")) { "<i>${it.groupValues[1]}</i><br/>" }

        // Обычные переносы строк
        html = html.replace("\n", "<br/>")

        return "<html><body style='padding:16px;'>$html</body></html>"
    }
}