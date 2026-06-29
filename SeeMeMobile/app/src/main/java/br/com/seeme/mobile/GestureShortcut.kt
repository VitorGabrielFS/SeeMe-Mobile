package br.com.seeme.mobile

data class GestureShortcut(
    val fingers: Int,
    val name: String,
    val type: ShortcutType,
    val value: String
)

enum class ShortcutType {
    Website,
    ShareText,
    WebHome
}
