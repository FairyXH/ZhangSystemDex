package io.github.fairyxh.zhangsystemdex.core.rubbish

/**
 * 极简 JSON 构建器（值类型安全，无外部依赖）。
 *
 * 之所以自带一个而不用 org.json：HttpBackend 的 MiniJson 只解析扁平对象，
 * 而清理结果需要嵌套数组；同时这里可以精确控制转义，避免手工拼接出错。
 */
class JsonBuilder {
    private val sb = StringBuilder()

    fun beginObject(): JsonBuilder { sb.append(LBRACE); return this }

    fun endObject(): JsonBuilder { sb.append(RBRACE); return this }

    fun beginArray(): JsonBuilder { sb.append(LBRACKET); return this }

    fun endArray(): JsonBuilder { sb.append(RBRACKET); return this }

    fun comma(): JsonBuilder { sb.append(COMMA); return this }

    fun key(name: String): JsonBuilder {
        sb.append(quote(name)).append(COLON)
        return this
    }

    fun value(v: String): JsonBuilder { sb.append(quote(v)); return this }

    fun value(v: Int): JsonBuilder { sb.append(v.toString()); return this }

    fun value(v: Long): JsonBuilder { sb.append(v.toString()); return this }

    fun value(v: Boolean): JsonBuilder { sb.append(v.toString()); return this }

    fun raw(s: String): JsonBuilder { sb.append(s); return this }

    fun string(v: String): JsonBuilder = value(v)

    override fun toString(): String = sb.toString()

    companion object {
        private const val LBRACE = "{"
        private const val RBRACE = "}"
        private const val LBRACKET = "["
        private const val RBRACKET = "]"
        private const val COMMA = ","
        private const val COLON = ":"

        fun obj(build: JsonBuilder.() -> Unit): String {
            val b = JsonBuilder()
            b.beginObject()
            b.build()
            b.endObject()
            return b.toString()
        }

        fun arr(build: JsonBuilder.() -> Unit): String {
            val b = JsonBuilder()
            b.beginArray()
            b.build()
            b.endArray()
            return b.toString()
        }

        fun quote(s: String): String {
            val out = StringBuilder(s.length + 8)
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}