package org.skepsun.kototoro.cloudstream.desktop

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A plugin asked to end the process; on Windows that would close Kototoro, so it is refused. */
class PluginExitRequested(call: String) : RuntimeException("Cloudstream plugin called $call; Kototoro keeps running")

/**
 * Where a converted plugin's process exits are sent instead. Plugins restart Cloudstream to apply their settings:
 * the launch intent, then `Runtime.exit(0)` (46 of 444 surveyed plugins call it). On Android the host stops the
 * restart before the exit runs; on Windows the exit itself is rewritten here ([PluginExitRewriter]) to throw.
 */
object PluginExits {
	@JvmStatic
	fun exit(@Suppress("UNUSED_PARAMETER") status: Int): Unit = throw PluginExitRequested("System.exit")

	@JvmStatic
	fun exit(@Suppress("UNUSED_PARAMETER") runtime: Runtime, @Suppress("UNUSED_PARAMETER") status: Int): Unit =
		throw PluginExitRequested("Runtime.exit")

	@JvmStatic
	fun halt(@Suppress("UNUSED_PARAMETER") runtime: Runtime, @Suppress("UNUSED_PARAMETER") status: Int): Unit =
		throw PluginExitRequested("Runtime.halt")

	@JvmStatic
	fun exitProcess(@Suppress("UNUSED_PARAMETER") status: Int): Void = throw PluginExitRequested("exitProcess")

	@JvmStatic
	fun killProcess(@Suppress("UNUSED_PARAMETER") pid: Int): Unit = throw PluginExitRequested("Process.killProcess")
}

/** Rewrites the process exits of a converted plugin jar into calls to [PluginExits]. */
object PluginExitRewriter {
	/** Bump when the rewrite changes: rewritten jars are cached under it. */
	const val VERSION = 1

	private val target = PluginExits::class.java.name.replace('.', '/')

	/** (owner, name, descriptor, opcode) of a call → the [PluginExits] method taking its receiver as the first argument. */
	private val replacements = mapOf(
		Call("java/lang/System", "exit", "(I)V", Opcodes.INVOKESTATIC) to Replacement("exit", "(I)V"),
		Call("java/lang/Runtime", "exit", "(I)V", Opcodes.INVOKEVIRTUAL) to Replacement("exit", "(Ljava/lang/Runtime;I)V"),
		Call("java/lang/Runtime", "halt", "(I)V", Opcodes.INVOKEVIRTUAL) to Replacement("halt", "(Ljava/lang/Runtime;I)V"),
		Call("kotlin/system/ProcessKt", "exitProcess", "(I)Ljava/lang/Void;", Opcodes.INVOKESTATIC) to
			Replacement("exitProcess", "(I)Ljava/lang/Void;"),
		Call("android/os/Process", "killProcess", "(I)V", Opcodes.INVOKESTATIC) to Replacement("killProcess", "(I)V"),
	)

	/** Writes [source] to [target] with every class's process exits rewritten; other entries are copied as they are. */
	fun rewrite(source: Path, target: Path) {
		val temporary = Files.createTempFile(target.toAbsolutePath().parent ?: Path.of("."), ".exits-", ".tmp")
		try {
			ZipInputStream(Files.newInputStream(source)).use { input ->
				ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
					while (true) {
						val entry = input.nextEntry ?: break
						val bytes = input.readBytes()
						output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
						output.write(if (entry.name.endsWith(".class")) rewriteClass(bytes) else bytes)
						output.closeEntry()
					}
				}
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	/** The class with its exits rewritten; the same bytes when it has none. */
	fun rewriteClass(bytes: ByteArray): ByteArray {
		val reader = ClassReader(bytes)
		// Each replacement takes and leaves the same stack as the call it replaces, so frames and maxima stand.
		val writer = ClassWriter(reader, 0)
		var changed = false
		reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
			override fun visitMethod(
				access: Int,
				name: String?,
				descriptor: String?,
				signature: String?,
				exceptions: Array<out String>?,
			): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
				override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
					val replacement = replacements[Call(owner, name, descriptor, opcode)]
					if (replacement == null) {
						super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
					} else {
						changed = true
						super.visitMethodInsn(Opcodes.INVOKESTATIC, target, replacement.name, replacement.descriptor, false)
					}
				}
			}
		}, 0)
		return if (changed) writer.toByteArray() else bytes
	}

	private data class Call(val owner: String, val name: String, val descriptor: String, val opcode: Int)

	private data class Replacement(val name: String, val descriptor: String)
}
