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
 * Retracing maps obfuscated class names, method names and (optionally) the source file name
 * back to the original ones. Line numbers are preserved, they are only used to pick the right
 * overload when several methods share one obfuscated name - exactly like ProGuard's Retrace
 * (line numbers are not renumbered during minification).
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
        val lines: List<IntRange>,
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
        FRAME_REGEX.matchEntire(line)?.let { match ->
            return retraceFrame(line, match) ?: line
        }

        return retraceHeader(line)
    }

    private fun retraceFrame(line: String, match: MatchResult): String? {
        val indent = match.groups["indent"]?.value.orEmpty()
        val obfuscatedClass = match.groups["cls"]?.value ?: return null
        val obfuscatedMethod = match.groups["method"]?.value ?: return null
        val location = match.groups["location"]?.value.orEmpty()

        val classMapping = classes[obfuscatedClass] ?: return null

        val lineNumber = LINE_NUMBER_REGEX.find(location)?.groupValues?.get(1)?.toIntOrNull()

        val methods = classMapping.methods[obfuscatedMethod]
            ?: return "${indent}at ${classMapping.originalName}.$obfuscatedMethod(${originalSource(classMapping, location)})"

        val method = methods.firstOrNull { mapping ->
            lineNumber == null || mapping.lines.isEmpty() ||
                mapping.lines.any { lineNumber in it }
        } ?: methods.first()

        return "${indent}at ${classMapping.originalName}.${method.originalName}(${originalSource(classMapping, location)})"
    }

    private fun originalSource(classMapping: ClassMapping, location: String): String {
        val lineNumber = LINE_NUMBER_REGEX.find(location)?.groupValues?.get(1)

        val simpleName = classMapping.originalName
            .substringAfterLast('.')
            .substringBefore('$')

        return when {
            lineNumber != null -> "$simpleName.java:$lineNumber"

            location.isBlank() -> simpleName

            else -> location
        }
    }

    private fun retraceHeader(line: String): String {
        HEADER_REGEX.matchEntire(line)?.let { match ->
            val obfuscatedClass = match.groups["cls"]?.value ?: return line
            val classMapping = classes[obfuscatedClass] ?: return line

            val prefix = match.groups["prefix"]?.value.orEmpty()
            val suffix = match.groups["suffix"]?.value.orEmpty()

            return "$prefix${classMapping.originalName}$suffix"
        }

        return line
    }

    companion object {
        val EMPTY = ProguardRetrace(emptyMap())

        private val FRAME_REGEX = Regex("""^(\s*)(?:at\s+)([^.\s(]+(?:\.[^.\s(]+)*)\.([^.\s(]+)\((.*)\)\s*$""")
        private val LINE_NUMBER_REGEX = Regex(""":(\d+)\)?\s*$""")
        private val HEADER_REGEX = Regex("""^(\s*(?:Caused by:\s*)?)([A-Za-z_$][\w$.]*)(:.*|)\s*$""")

        private val CLASS_LINE_REGEX = Regex("""^(\S+) -> (\S+):\s*$""")
        private val METHOD_LINE_REGEX = Regex("""^\s+(\d+):(\d+):(.+?) -> (\S+)\s*$""")
        private val METHOD_SIGNATURE_REGEX = Regex("""^(.*?)\s+([^\s(]+)\s*\((.*)\)$""")

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

                        continue
                    }

                    current = null

                    continue
                }

                val builder = current ?: continue

                val methodMatch = METHOD_LINE_REGEX.matchEntire(line) ?: continue

                val originalLineStart = methodMatch.groupValues[1].toIntOrNull() ?: continue
                val originalLineEnd = methodMatch.groupValues[2].toIntOrNull() ?: continue
                val signature = methodMatch.groupValues[3]
                val obfuscatedMethodName = methodMatch.groupValues[4]

                val signatureMatch = METHOD_SIGNATURE_REGEX.matchEntire(signature)

                val returnType = signatureMatch?.groupValues?.get(1)?.trim().orEmpty()
                val originalMethodName = signatureMatch?.groupValues?.get(2)?.trim()
                    ?: signature.substringBefore('(').trim()
                val arguments = signatureMatch?.groupValues?.get(3).orEmpty()

                val range = if (originalLineStart <= originalLineEnd) {
                    originalLineStart..originalLineEnd
                } else {
                    originalLineEnd..originalLineStart
                }

                builder.addMethod(
                    MethodMapping(
                        obfuscatedName = obfuscatedMethodName,
                        originalName = originalMethodName,
                        returnType = returnType,
                        arguments = arguments,
                        lines = listOf(range),
                    ),
                )
            }

            val classes = builders.mapValues { (obfuscatedName, builder) ->
                ClassMapping(
                    obfuscatedName = obfuscatedName,
                    originalName = builder.originalName,
                    methods = builder.methods.mapValues { (_, methods) ->
                        // Merge the line ranges of identical obfuscated methods, R8 emits one
                        // entry per original line range
                        methods.groupBy { it.originalName to it.arguments }
                            .map { (_, group) ->
                                group.first().copy(lines = group.flatMap { it.lines })
                            }
                    },
                )
            }

            return ProguardRetrace(classes)
        }

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
