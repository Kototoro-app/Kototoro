package org.skepsun.kototoro.video.player

internal data class Anime4KHookPass(
	val description: String,
	val hook: String,
	val bindings: List<String>,
	val save: String?,
	val widthExpression: String?,
	val heightExpression: String?,
	val conditionExpression: String?,
	val components: Int,
	val source: String,
)

/** Parses the small mpv shader-hook directive subset used by the bundled MIT Anime4K shaders. */
internal object Anime4KHookShaderParser {

	fun parse(source: String): List<Anime4KHookPass> {
		val passes = mutableListOf<Anime4KHookPass>()
		var builder: PassBuilder? = null
		for (line in source.lineSequence()) {
			if (line.startsWith("//!DESC ")) {
				builder?.build()?.let(passes::add)
				builder = PassBuilder(line.removePrefix("//!DESC ").trim())
				continue
			}
			val current = builder ?: continue
			when {
				line.startsWith("//!HOOK ") -> current.hook = line.directiveValue("HOOK")
				line.startsWith("//!BIND ") -> current.bindings += line.directiveValue("BIND")
				line.startsWith("//!SAVE ") -> current.save = line.directiveValue("SAVE")
				line.startsWith("//!WIDTH ") -> current.widthExpression = line.directiveValue("WIDTH")
				line.startsWith("//!HEIGHT ") -> current.heightExpression = line.directiveValue("HEIGHT")
				line.startsWith("//!WHEN ") -> current.conditionExpression = line.directiveValue("WHEN")
				line.startsWith("//!COMPONENTS ") -> current.components =
					line.directiveValue("COMPONENTS").toIntOrNull() ?: 4
				line.startsWith("//!") -> Unit
				else -> current.source.appendLine(line)
			}
		}
		builder?.build()?.let(passes::add)
		return passes
	}

	private fun String.directiveValue(name: String): String = removePrefix("//!$name ").trim()

	private class PassBuilder(private val description: String) {
		var hook: String? = null
		val bindings = mutableListOf<String>()
		var save: String? = null
		var widthExpression: String? = null
		var heightExpression: String? = null
		var conditionExpression: String? = null
		var components: Int = 4
		val source = StringBuilder()

		fun build(): Anime4KHookPass? {
			val target = hook ?: return null
			if (!source.contains("vec4 hook()")) return null
			return Anime4KHookPass(
				description = description,
				hook = target,
				bindings = bindings.distinct(),
				save = save,
				widthExpression = widthExpression,
				heightExpression = heightExpression,
				conditionExpression = conditionExpression,
				components = components,
				source = source.toString().trim(),
			)
		}
	}
}

internal data class ShaderTextureSize(val width: Int, val height: Int)

/** Evaluates mpv's postfix size/WHEN expressions without accepting arbitrary code. */
internal object Anime4KHookExpression {

	fun evaluate(
		expression: String?,
		textures: Map<String, ShaderTextureSize>,
		output: ShaderTextureSize,
	): Double? {
		if (expression.isNullOrBlank()) return null
		val stack = ArrayDeque<Double>()
		for (token in expression.split(Regex("\\s+")).filter(String::isNotBlank)) {
			val value = token.toDoubleOrNull() ?: resolveVariable(token, textures, output)
			if (value != null) {
				stack.addLast(value)
				continue
			}
			if (token == "!") {
				val operand = stack.removeLastOrNull() ?: return null
				stack.addLast(if (operand == 0.0) 1.0 else 0.0)
				continue
			}
			val right = stack.removeLastOrNull() ?: return null
			val left = stack.removeLastOrNull() ?: return null
			stack.addLast(
				when (token) {
					"+" -> left + right
					"-" -> left - right
					"*" -> left * right
					"/" -> if (right == 0.0) return null else left / right
					">" -> (left > right).numeric()
					"<" -> (left < right).numeric()
					">=" -> (left >= right).numeric()
					"<=" -> (left <= right).numeric()
					"==" -> (left == right).numeric()
					"&&" -> (left != 0.0 && right != 0.0).numeric()
					"||" -> (left != 0.0 || right != 0.0).numeric()
					else -> return null
				},
			)
		}
		return stack.singleOrNull()
	}

	private fun resolveVariable(
		token: String,
		textures: Map<String, ShaderTextureSize>,
		output: ShaderTextureSize,
	): Double? {
		val separator = token.lastIndexOf('.')
		if (separator <= 0) return null
		val name = token.substring(0, separator)
		val size = if (name == "OUTPUT") output else textures[name] ?: return null
		return when (token.substring(separator + 1)) {
			"w" -> size.width.toDouble()
			"h" -> size.height.toDouble()
			else -> null
		}
	}

	private fun Boolean.numeric(): Double = if (this) 1.0 else 0.0
}
