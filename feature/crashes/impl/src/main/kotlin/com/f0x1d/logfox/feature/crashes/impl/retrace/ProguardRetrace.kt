package com.f0x1d.logfox.feature.crashes.impl.retrace

/**
 * A minimal but faithful implementation of ProGuard/R8 stack trace retracing.
 *
 * Supported mapping format (both old ProGuard and R8 produce it):
 *
 * ```
 * com.example.Foo -> a.b.c:
 *     java.lang.String name -> a
 *     12:34:void doWork(int) -> b
 *     35:35:void doWork(int) -> b
 * ```
 *
 * R8 may additionally emit the obfuscated line range for inlined frames:
 *
 * ```
 *     12:34:void doWork(int):56:58 -> b
 * ```
 *
 * Pure Kotlin on purpose: no Android dependencies, so it can be unit tested.
 */
class ProguardRetrace private constructor(
    private val classes: Map<String, ClassMapping>,
) {

    data class MethodMapping(
        val obfuscatedName: String,
        val originalName: String,
        val returnType: String,
        val arguments: String,
        /** Line range in the original source. */
        val originalLines: IntRange?,
        /** Line range in the obfuscated code (R8 inline frames only). */
        val obfuscatedLines: IntRange?,
    )

    data class ClassMapping(
        val obfuscatedName: String,
        val originalName: String,
        val methods: Map<String, List<MethodMapping>>,
    )

    fun retrace(text: String): String = text.lineSequence().joinToString("\n") { line ->
        retraceLine(line)
    }

    fun retraceLine(line: String): String {
        retraceFrame(line)?.let { return it }

        return retraceHeader(line)
    }

    private fun retraceFrame(line: String): String? {
        val match = FRAME_REGEX.matchEntire(line) ?: return null

        val indent = match.groupValues[1]
        val qualified = match.groupValues[2]
        val location = match.groupValues[3]

        val lastDot = qualified.lastIndexOf('.')
        if (lastDot < 0) return null

        val obfuscatedClass = qualified.substring(0, lastDot)
        val obfuscatedMethod = qualified.substring(lastDot + 1)

        val classMapping = classes[obfuscatedClass] ?: return null

        val lineNumber = LINE_NUMBER_REGEX.find(location)?.groupValues?.get(1)?.toIntOrNull()

        val method = pickMethod(classMapping.methods[obfuscatedMethod], lineNumber)
            ?: return null

        val originalLine = originalLineNumber(method, lineNumber)
        val source = buildSource(classMapping, location, originalLine)

        return "${indent}at ${classMapping.originalName}.${method.originalName}($source)"
    }

    private fun pickMethod(candidates: List<MethodMapping>?, lineNumber: Int?): MethodMapping? {
        if (candidates.isNullOrEmpty()) return null

        if (lineNumber == null) {
            return candidates.firstOrNull { it.obfuscatedLines == null } ?: candidates.first()
        }

        // Inlined frames carry the obfuscated range, so match that one first.
        candidates.firstOrNull { mapping -> mapping.obfuscatedLines?.contains(lineNumber) == true }
            ?.let { return it }

        // ProGuard does not renumber lines, so the obfuscated line is also a source line.
        candidates.firstOrNull { mapping -> mapping.originalLines?.contains(lineNumber) == true }
            ?.let { return it }

        // Nothing matched: keep the frame untouched instead of guessing the first overload.
        return null
    }

    private fun originalLineNumber(method: MethodMapping, lineNumber: Int?): Int? {
        if (lineNumber == null) return null

        val obfuscated = method.obfuscatedLines
        val original = method.originalLines

        return if (obfuscated != null && original != null && lineNumber in obfuscated) {
            original.first + (lineNumber - obfuscated.first)
        } else {
            lineNumber
        }
    }

    private fun buildSource(
        classMapping: ClassMapping,
        location: String,
        originalLine: Int?,
    ): String {
        if (originalLine == null) return location

        val simpleName = classMapping.originalName
            .substringAfterLast('.')
            .substringBefore('$')

        return "$simpleName.java:$originalLine"
    }

    private fun retraceHeader(line: String): String {
        val match = HEADER_REGEX.matchEntire(line) ?: return line

        val prefix = match.groupValues[1]
        val obfuscatedClass = match.groupValues[2]
        val suffix = match.groupValues[3]

        val classMapping = classes[obfuscatedClass] ?: return line

        return "$prefix${classMapping.originalName}$suffix"
    }

    companion object {
        val EMPTY = ProguardRetrace(emptyMap())

        private val FRAME_REGEX = Regex("""^(\s*)at\s+([^\s(]+)\((.*)\)\s*$""")
        private val LINE_NUMBER_REGEX = Regex(""":(\d+)\)?\s*$""")
        private val HEADER_REGEX =
            Regex("""^(\s*(?:Caused by:\s*|Suppressed:\s*)?)([A-Za-z_$][\w$.]*)(:.*)?$""")

        private val CLASS_LINE_REGEX = Regex("""^(\S+)\s+->\s+(\S+):\s*$""")
        private val METHOD_LINE_REGEX = Regex("""^\s+(\d+):(\d+):(.+)\s->\s(\S+)\s*$""")
        private val METHOD_SIGNATURE_REGEX = Regex("""^(.*?)\s+([^\s(]+)\s*\((.*)\)$""")
        private val INLINE_RANGE_REGEX = Regex("""^(.+?):(\d+):(\d+)$""")

        fun parse(mapping: String): ProguardRetrace {
            val builders = LinkedHashMap<String, MutableClassBuilder>()

            var current: MutableClassBuilder? = null

            for (rawLine in mapping.lineSequence()) {
                val line = rawLine.trimEnd()

                if (line.isBlank()) continue

                // A class header has no indentation
                if (!line.first().isWhitespace()) {
                    val classMatch = CLASS_LINE_REGEX.matchEntire(line)

                    if (classMatch != null) {
                        val originalName = classMatch.groupValues[1]
                        val obfuscatedName = classMatch.groupValues[2]

                        current = MutableClassBuilder(obfuscatedName, originalName)
                        builders[obfuscatedName] = current
                    } else {
                        current = null
                    }

                    continue
                }

                val builder = current ?: continue

                // Field lines (`    java.lang.String name -> a`) do not match on purpose
                val methodMatch = METHOD_LINE_REGEX.matchEntire(line) ?: continue

                val sourceStart = methodMatch.groupValues[1].toIntOrNull() ?: continue
                val sourceEnd = methodMatch.groupValues[2].toIntOrNull() ?: continue

                var signature = methodMatch.groupValues[3].trim()
                val obfuscatedMethodName = methodMatch.groupValues[4]

                var obfuscatedStart: Int? = null
                var obfuscatedEnd: Int? = null

                INLINE_RANGE_REGEX.matchEntire(signature)?.let { inline ->
                    signature = inline.groupValues[1]
                    obfuscatedStart = inline.groupValues[2].toIntOrNull()
                    obfuscatedEnd = inline.groupValues[3].toIntOrNull()
                }

                val signatureMatch = METHOD_SIGNATURE_REGEX.matchEntire(signature)

                val returnType = signatureMatch?.groupValues?.get(1)?.trim().orEmpty()
                val originalMethodName = signatureMatch?.groupValues?.get(2)?.trim()
                    ?: signature.substringBefore('(').trim()
                val arguments = signatureMatch?.groupValues?.get(3).orEmpty()

                builder.addMethod(
                    MethodMapping(
                        obfuscatedName = obfuscatedMethodName,
                        originalName = originalMethodName,
                        returnType = returnType,
                        arguments = arguments,
                        originalLines = rangeOf(sourceStart, sourceEnd),
                        obfuscatedLines = if (obfuscatedStart != null && obfuscatedEnd != null) {
                            rangeOf(obfuscatedStart, obfuscatedEnd)
                        } else {
                            null
                        },
                    ),
                )
            }

            val classes = builders.mapValues { (obfuscatedName, builder) ->
                ClassMapping(
                    obfuscatedName = obfuscatedName,
                    originalName = builder.originalName,
                    methods = builder.methods.mapValues { (_, methods) -> methods.toList() },
                )
            }

            return ProguardRetrace(classes)
        }

        private fun rangeOf(start: Int, end: Int): IntRange =
            if (start <= end) start..end else end..start

        private class MutableClassBuilder(
            val obfuscatedName: String,
            val originalName: String,
        ) {
            val methods = mutableMapOf<String, MutableList<MethodMapping>>()

            fun addMethod(method: MethodMapping) {
                methods.getOrPut(method.obfuscatedName) { mutableListOf() }.add(method)
            }
        }
    }
}
