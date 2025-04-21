package net.fix

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

enum class MessageType(val color: Int) {
    SUCCESS(Color.GREEN),
    ERROR(Color.RED),
    WARNING(Color.YELLOW),
    INFO(Color.WHITE)
}

data class SpannableMessage(val text: String, val type: MessageType) {
    fun toSpannable(): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        spannable.setSpan(ForegroundColorSpan(type.color), 0, text.length, 0)
        return spannable
    }

    fun toAnnotatedString(): AnnotatedString {
        return buildAnnotatedString {
            append(text)
            addStyle(
                style = SpanStyle(color = androidx.compose.ui.graphics.Color(type.color)),
                start = 0,
                end = text.length
            )
        }
    }
}

fun formatError(message: String): String {
    return "[!] Error: $message\n"
}