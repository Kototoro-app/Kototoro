package org.skepsun.kototoro.reader.translate.data

import android.util.Log

data class Anime4kPass(
    val desc: String,
    val hook: String,
    val binds: List<String>,
    val save: String?,
    val widthExpression: String?,
    val heightExpression: String?,
    val fragmentCode: String
)

object Anime4kCompiler {
    private const val TAG = "Anime4kCompiler"

    fun parse(shaderSources: List<String>): List<Anime4kPass> {
        val passes = mutableListOf<Anime4kPass>()

        for (source in shaderSources) {
            var currentDesc = ""
            var currentHook = ""
            val currentBinds = mutableListOf<String>()
            var currentSave: String? = null
            var currentWidth: String? = null
            var currentHeight: String? = null
            val currentCode = StringBuilder()

            var inHook = false

            val lines = source.lines()
            for (line in lines) {
                val tline = line.trim()
                if (tline.startsWith("//!DESC ")) {
                    currentDesc = tline.removePrefix("//!DESC ").trim()
                } else if (tline.startsWith("//!HOOK ")) {
                    if (inHook && currentCode.isNotEmpty()) {
                        passes.add(Anime4kPass(currentDesc, currentHook, currentBinds.toList(), currentSave, currentWidth, currentHeight, currentCode.toString()))
                        currentBinds.clear()
                        currentSave = null
                        currentWidth = null
                        currentHeight = null
                        currentCode.clear()
                    }
                    inHook = true
                    currentHook = tline.removePrefix("//!HOOK ").trim()
                } else if (inHook) {
                    if (tline.startsWith("//!BIND ")) {
                        currentBinds.add(tline.removePrefix("//!BIND ").trim())
                    } else if (tline.startsWith("//!SAVE ")) {
                        currentSave = tline.removePrefix("//!SAVE ").trim()
                    } else if (tline.startsWith("//!WIDTH ")) {
                        currentWidth = tline.removePrefix("//!WIDTH ").trim()
                    } else if (tline.startsWith("//!HEIGHT ")) {
                        currentHeight = tline.removePrefix("//!HEIGHT ").trim()
                    } else if (tline.startsWith("//!COMPONENTS ") || tline.startsWith("//!WHEN ")) {
                        // ignore
                    } else if (tline.startsWith("//")) {
                        // ignore normal comments
                    } else {
                        currentCode.appendLine(line)
                    }
                }
            }
            if (inHook && currentCode.isNotEmpty()) {
                passes.add(Anime4kPass(currentDesc, currentHook, currentBinds.toList(), currentSave, currentWidth, currentHeight, currentCode.toString()))
            }
        }
        return passes
    }

    fun compileToFragmentShader(pass: Anime4kPass): String {
        val sb = StringBuilder()
        sb.appendLine("#version 300 es")
        sb.appendLine("precision highp float;")
        sb.appendLine("in vec2 vTexCoord;")
        sb.appendLine("out vec4 outColor;")

        // Always bind MAIN
        sb.appendLine("uniform sampler2D MAIN;")
        sb.appendLine("uniform vec2 MAIN_size;")
        sb.appendLine("uniform vec2 MAIN_pt;")

        // Macros for MAIN
        sb.appendLine("#define MAIN_pos vTexCoord")
        sb.appendLine("#define MAIN_tex(pos) texture(MAIN, pos)")
        sb.appendLine("#define MAIN_texOff(offset) texture(MAIN, vTexCoord + (offset) * MAIN_pt)")

        // Bind other textures
        val uniqueBinds = pass.binds.distinct().filter { it != "MAIN" && it != "HOOKED" }.toMutableList()
        if (pass.hook != "MAIN" && !uniqueBinds.contains(pass.hook)) {
            uniqueBinds.add(pass.hook)
        }
        for (bind in uniqueBinds) {
            sb.appendLine("uniform sampler2D $bind;")
            sb.appendLine("uniform vec2 ${bind}_size;")
            sb.appendLine("uniform vec2 ${bind}_pt;")
            sb.appendLine("#define ${bind}_pos vTexCoord")
            sb.appendLine("#define ${bind}_tex(pos) texture($bind, pos)")
            sb.appendLine("#define ${bind}_texOff(offset) texture($bind, vTexCoord + (offset) * ${bind}_pt)")
        }

        // Always define HOOKED stuff to point to pass.hook (Mpv uses HOOKED as the active texture)
        val hookedTarget = pass.hook
        sb.appendLine("#define HOOKED_pos vTexCoord")
        sb.appendLine("#define HOOKED_tex(pos) texture($hookedTarget, pos)")
        sb.appendLine("#define HOOKED_texOff(offset) texture($hookedTarget, vTexCoord + (offset) * ${hookedTarget}_pt)")
        sb.appendLine("#define HOOKED_size ${hookedTarget}_size")
        sb.appendLine("#define HOOKED_pt ${hookedTarget}_pt")

        sb.appendLine(pass.fragmentCode)

        sb.appendLine("void main() {")
        sb.appendLine("    outColor = hook();")
        sb.appendLine("}")
        return sb.toString()
    }
}
