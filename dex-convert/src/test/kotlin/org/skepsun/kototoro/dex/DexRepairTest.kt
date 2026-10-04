package org.skepsun.kototoro.dex

import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.smali.Smali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R8 inlines trivial constructors: `new-instance LT;` is then initialised by a constructor of an ancestor
 * (`Object.<init>`, or the library class T forwarded to). ART accepts it, the JVM verifier does not. The instruction
 * shapes below are hand-written smali because that is exactly what R8 emits.
 */
class DexRepairTest {
    @TempDir lateinit var root: Path

    private val base = """
        .class public Lfix/Base;
        .super Ljava/lang/Object;
        .field public name:Ljava/lang/String;
        .method public <init>(Ljava/lang/String;)V
            .registers 3
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput-object p1, p0, Lfix/Base;->name:Ljava/lang/String;
            return-void
        .end method
    """.trimIndent()

    private fun dex(vararg classes: Pair<String, String>): ByteArray {
        val writer = DexFileWriter()
        for ((name, text) in classes) Smali.smaliFile2Node(name, text).accept(writer)
        return writer.toByteArray()
    }

    private fun convert(vararg dexes: ByteArray): Pair<DexConversionReport, Path> {
        val archive = root.resolve("input-${dexes.size}-${System.nanoTime()}.apk")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            dexes.forEachIndexed { index, bytes ->
                zip.putNextEntry(ZipEntry(if (index == 0) "classes.dex" else "classes${index + 1}.dex"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val jar = root.resolve("out-${System.nanoTime()}.jar")
        return DexJarConverter.convert(archive, jar) to jar
    }

    /** Calls `fix.Factory.make(arg)` in a fresh loader; loading it runs the JVM verifier. */
    private fun make(jar: Path, argument: String? = null): Any? = URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
        val factory = Class.forName("fix.Factory", true, loader)
        if (argument == null) factory.getMethod("make").invoke(null) else factory.getMethod("make", String::class.java).invoke(null, argument)
    }

    @Test
    fun `an instance created as T and initialised by Object is rebuilt as a T with a real constructor`() {
        val (report, jar) = convert(dex(
            "T.smali" to """
                .class public Lfix/T;
                .super Ljava/lang/Object;
            """.trimIndent(),
            "Factory.smali" to """
                .class public Lfix/Factory;
                .super Ljava/lang/Object;
                .method public static make()Lfix/T;
                    .registers 2
                    new-instance v0, Lfix/T;
                    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
                    return-object v0
                .end method
            """.trimIndent(),
        ))
        assertEquals(1, report.repairedInstantiations)
        assertEquals("fix.T", make(jar)!!.javaClass.name)
    }

    @Test
    fun `a forwarded constructor survives a loop and register moves between creation and call`() {
        val (report, jar) = convert(dex(
            "Base.smali" to base,
            "T.smali" to """
                .class public Lfix/T;
                .super Lfix/Base;
            """.trimIndent(),
            "Factory.smali" to """
                .class public Lfix/Factory;
                .super Ljava/lang/Object;
                .method public static make(Ljava/lang/String;)Lfix/Base;
                    .registers 5
                    new-instance v0, Lfix/T;
                    const/4 v2, 0x0
                    const/4 v3, 0x3
                    :loop
                    if-ge v2, v3, :done
                    add-int/lit8 v2, v2, 0x1
                    goto :loop
                    :done
                    move-object v1, v0
                    invoke-direct {v1, p0}, Lfix/Base;-><init>(Ljava/lang/String;)V
                    return-object v1
                .end method
            """.trimIndent(),
        ))
        assertEquals(1, report.repairedInstantiations)
        val made = make(jar, "forwarded")!!
        assertEquals("fix.T", made.javaClass.name)
        assertEquals("forwarded", made.javaClass.getField("name").get(made))
    }

    @Test
    fun `constructors are added along the whole chain between the created class and the called ancestor`() {
        val (report, jar) = convert(dex(
            "Base.smali" to base,
            "Mid.smali" to """
                .class public Lfix/Mid;
                .super Lfix/Base;
            """.trimIndent(),
            "Top.smali" to """
                .class public Lfix/Top;
                .super Lfix/Mid;
            """.trimIndent(),
            "Factory.smali" to """
                .class public Lfix/Factory;
                .super Ljava/lang/Object;
                .method public static make(Ljava/lang/String;)Lfix/Base;
                    .registers 3
                    new-instance v0, Lfix/Top;
                    invoke-direct {v0, p0}, Lfix/Base;-><init>(Ljava/lang/String;)V
                    return-object v0
                .end method
            """.trimIndent(),
        ))
        assertEquals(1, report.repairedInstantiations)
        val made = make(jar, "chain")!!
        assertEquals("fix.Top", made.javaClass.name)
        assertEquals("chain", made.javaClass.getField("name").get(made))
    }

    @Test
    fun `a class living in another dex file still gets its constructor`() {
        val factory = dex(
            "Factory.smali" to """
                .class public Lfix/Factory;
                .super Ljava/lang/Object;
                .method public static make()Lfix/T;
                    .registers 2
                    new-instance v0, Lfix/T;
                    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
                    return-object v0
                .end method
            """.trimIndent(),
        )
        val other = dex("T.smali" to ".class public Lfix/T;\n.super Ljava/lang/Object;")
        val (report, jar) = convert(factory, other)
        assertEquals(1, report.repairedInstantiations)
        assertEquals("fix.T", make(jar)!!.javaClass.name)
    }

    @Test
    fun `correct constructor calls are left alone`() {
        val (report, jar) = convert(dex(
            "Base.smali" to base,
            "Factory.smali" to """
                .class public Lfix/Factory;
                .super Ljava/lang/Object;
                .method public static make(Ljava/lang/String;)Lfix/Base;
                    .registers 3
                    new-instance v0, Lfix/Base;
                    invoke-direct {v0, p0}, Lfix/Base;-><init>(Ljava/lang/String;)V
                    return-object v0
                .end method
            """.trimIndent(),
        ))
        assertEquals(0, report.repairedInstantiations)
        val made = make(jar, "plain")!!
        assertEquals("plain", made.javaClass.getField("name").get(made))
    }
}