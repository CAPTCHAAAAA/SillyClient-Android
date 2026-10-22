package com.sillyclient.ui

/**
 * 把 HTML `accept` 属性转换为 SAF 能理解的 MIME 过滤规则。
 *
 * `accept` 允许写扩展名（如 `.json`、`.lorebook`），而 SAF 只按 MIME 过滤；
 * 把扩展名原样塞进 `Intent.type` / `EXTRA_MIME_TYPES` 会让选择器把这些文件
 * 判定为类型不匹配：能打开，但文件全部置灰不可选。
 *
 * 即使把已知扩展名翻译成 MIME 也不能可靠过滤：部分文件管理器会把 `.json`
 * 上报成 `application/octet-stream`，按 `application/json` 过滤仍会置灰。
 * 因此规则是：
 * - accept 全部是合法 MIME 类型时保留过滤；
 * - 只要出现扩展名形式或无法识别的项，就放弃 MIME 过滤，由页面自身校验文件。
 */
object FileChooserMimeTypes {

    data class Filter(val type: String, val extraMimeTypes: List<String>)

    private val unrestricted = Filter("*/*", emptyList())
    private val mimeTypePattern = Regex("^[A-Za-z0-9!#$&^_.+-]+/(?:\\*|[A-Za-z0-9!#$&^_.+*-]+)$")

    fun resolve(acceptTypes: Array<String>?): Filter {
        val tokens = acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return unrestricted

        val mimeTypes = LinkedHashSet<String>()
        for (token in tokens) {
            val normalized = token.lowercase()
            when {
                normalized == "*/*" -> return unrestricted
                normalized.contains('/') -> {
                    val mimeType = normalized.substringBefore(';').trim()
                    if (!mimeTypePattern.matches(mimeType)) return unrestricted
                    mimeTypes.add(mimeType)
                }
                // 扩展名无法通过 SAF 可靠过滤，交给页面按文件名/内容自行校验。
                else -> return unrestricted
            }
        }
        if (mimeTypes.isEmpty()) return unrestricted
        return if (mimeTypes.size == 1) {
            Filter(mimeTypes.first(), emptyList())
        } else {
            Filter("*/*", mimeTypes.toList())
        }
    }
}
