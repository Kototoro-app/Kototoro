package org.skepsun.kototoro.cloudstream.desktop

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException

class PluginBytecodeRewriterTest {

	@Test
	fun `process exits in a plugin class throw instead of ending the host`() {
		val rewritten = PluginBytecodeRewriter.rewriteClass(exitingClass())
		val type = object : ClassLoader(javaClass.classLoader) {
			fun define(bytes: ByteArray): Class<*> = defineClass(CLASS_NAME, bytes, 0, bytes.size)
		}.define(rewritten)

		for (method in listOf("systemExit", "runtimeExit", "runtimeHalt", "kotlinExitProcess")) {
			val error = assertThrows<InvocationTargetException>(method) { type.getMethod(method).invoke(null) }
			assertInstanceOf(PluginExitRequested::class.java, error.cause, method)
		}
	}

	// As StreamPlay sizes its concurrency when it loads: the compatibility runtime's MemoryInfo is a stub that throws.
	@Test
	fun `a plugin reads the machine's memory through ActivityManager`() {
		val rewritten = PluginBytecodeRewriter.rewriteClass(memoryReadingClass())
		val type = object : ClassLoader(javaClass.classLoader) {
			fun define(bytes: ByteArray): Class<*> = defineClass(CLASS_NAME, bytes, 0, bytes.size)
		}.define(rewritten)

		val total = type.getMethod("totalMemory", android.app.ActivityManager::class.java)
			.invoke(null, DesktopActivityManager.service()) as Long

		assertTrue(total > 0, "total memory $total")
	}

	@Test
	fun `a class without process exits keeps its bytes`() {
		val bytes = classWith("plain") { visitInsn(Opcodes.RETURN) }

		assertArrayEquals(bytes, PluginBytecodeRewriter.rewriteClass(bytes))
	}

	private fun exitingClass(): ByteArray = classWith(
		"systemExit" to {
			visitInsn(Opcodes.ICONST_3)
			visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false)
			visitInsn(Opcodes.RETURN)
		},
		"runtimeExit" to {
			visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
			visitInsn(Opcodes.ICONST_4)
			visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exit", "(I)V", false)
			visitInsn(Opcodes.RETURN)
		},
		"runtimeHalt" to {
			visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
			visitInsn(Opcodes.ICONST_5)
			visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "halt", "(I)V", false)
			visitInsn(Opcodes.RETURN)
		},
		"kotlinExitProcess" to {
			visitInsn(Opcodes.ICONST_1)
			visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/system/ProcessKt", "exitProcess", "(I)Ljava/lang/Void;", false)
			visitInsn(Opcodes.POP)
			visitInsn(Opcodes.RETURN)
		},
	)

	/** `fun totalMemory(manager: ActivityManager): Long = MemoryInfo().also(manager::getMemoryInfo).totalMem` */
	private fun memoryReadingClass(): ByteArray {
		val memoryInfo = "android/app/ActivityManager\$MemoryInfo"
		val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, CLASS_NAME.replace('.', '/'), null, "java/lang/Object", null)
		writer.visitMethod(
			Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "totalMemory", "(Landroid/app/ActivityManager;)J", null, null,
		).apply {
			visitCode()
			visitTypeInsn(Opcodes.NEW, memoryInfo)
			visitInsn(Opcodes.DUP)
			visitMethodInsn(Opcodes.INVOKESPECIAL, memoryInfo, "<init>", "()V", false)
			visitVarInsn(Opcodes.ASTORE, 1)
			visitVarInsn(Opcodes.ALOAD, 0)
			visitVarInsn(Opcodes.ALOAD, 1)
			visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/app/ActivityManager", "getMemoryInfo", "(L$memoryInfo;)V", false)
			visitVarInsn(Opcodes.ALOAD, 1)
			visitFieldInsn(Opcodes.GETFIELD, memoryInfo, "totalMem", "J")
			visitInsn(Opcodes.LRETURN)
			visitMaxs(0, 0)
			visitEnd()
		}
		writer.visitEnd()
		return writer.toByteArray()
	}

	private fun classWith(name: String, body: MethodVisitor.() -> Unit) = classWith(name to body)

	private fun classWith(vararg methods: Pair<String, MethodVisitor.() -> Unit>): ByteArray {
		val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, CLASS_NAME.replace('.', '/'), null, "java/lang/Object", null)
		for ((name, body) in methods) {
			writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()V", null, null).apply {
				visitCode()
				body()
				visitMaxs(0, 0)
				visitEnd()
			}
		}
		writer.visitEnd()
		return writer.toByteArray()
	}

	private companion object {
		const val CLASS_NAME = "test.plugin.Exits"
	}
}
