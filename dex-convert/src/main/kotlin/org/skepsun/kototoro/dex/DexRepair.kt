package org.skepsun.kototoro.dex

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexCodeNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.node.insn.AbstractMethodStmtNode
import com.googlecode.d2j.node.insn.BaseSwitchStmtNode
import com.googlecode.d2j.node.insn.ConstStmtNode
import com.googlecode.d2j.node.insn.DexLabelStmtNode
import com.googlecode.d2j.node.insn.DexStmtNode
import com.googlecode.d2j.node.insn.FieldStmtNode
import com.googlecode.d2j.node.insn.FillArrayDataStmtNode
import com.googlecode.d2j.node.insn.FilledNewArrayStmtNode
import com.googlecode.d2j.node.insn.JumpStmtNode
import com.googlecode.d2j.node.insn.MethodStmtNode
import com.googlecode.d2j.node.insn.Stmt0RNode
import com.googlecode.d2j.node.insn.Stmt1RNode
import com.googlecode.d2j.node.insn.Stmt2R1NNode
import com.googlecode.d2j.node.insn.Stmt2RNode
import com.googlecode.d2j.node.insn.Stmt3RNode
import com.googlecode.d2j.node.insn.TypeStmtNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexFileVisitor

/**
 * R8 removes trivial constructors and inlines them at the call sites, leaving `new-instance vA, LT;` followed by
 * `invoke-direct {vA, ...}, LS;-><init>(...)` where S is an ancestor of T (usually `Object`). ART accepts that; the
 * JVM verifier does not, and dex2jar turns it into `new S(...)` whose value is then stored as a T. The repair gives T
 * (and the classes between T and S) the constructor the call site needs, and points the call at it, before dex2jar
 * sees the code. Nothing else about the method changes.
 */
internal class DexRepairPlan private constructor(
    private val defined: Set<String>,
    private val superOf: Map<String, String>,
    private val constructors: Set<String>,
    /** class -> constructor parameter lists to add */
    val synthesized: Map<String, Set<List<String>>>,
) {
    class Builder {
        private val defined = HashSet<String>()
        private val superOf = HashMap<String, String>()
        private val constructors = HashSet<String>()
        private val sites = ArrayList<Triple<String, String, List<String>>>()

        /** Indexes one dex file: what it defines and which instantiations need repair. */
        fun add(dex: ByteArray) {
            val node = read(dex)
            for (clz in node.clzs.orEmpty()) {
                defined += clz.className
                clz.superClass?.let { superOf[clz.className] = it }
                for (method in clz.methods.orEmpty()) {
                    if (method.method.name == "<init>") constructors += key(clz.className, method.method.parameterTypes.asList())
                    method.codeNode?.let { code -> forEachSite(code) { _, created, called -> sites += Triple(created, called.owner, called.parameterTypes.asList()) } }
                }
            }
        }

        fun build(): DexRepairPlan {
            val partial = DexRepairPlan(defined, superOf, constructors, emptyMap())
            val synthesized = HashMap<String, MutableSet<List<String>>>()
            for ((created, ancestor, parameters) in sites) {
                for ((owner, list) in partial.resolve(created, ancestor, parameters) ?: continue) {
                    synthesized.getOrPut(owner) { LinkedHashSet() } += list
                }
            }
            return DexRepairPlan(defined, superOf, constructors, synthesized)
        }
    }

    /**
     * The constructors to add so `new created` can run `ancestor.<init>(parameters)`, or null when the shape is not
     * one this repair understands (the class is not ours, the ancestor is not on its chain, or the exact constructor
     * already exists and the call would then run code twice).
     */
    fun resolve(created: String, ancestor: String, parameters: List<String>): List<Pair<String, List<String>>>? {
        if (created == ancestor || created !in defined || key(created, parameters) in constructors) return null
        val chain = ArrayList<String>()
        var current = created
        while (current != ancestor) {
            if (current !in defined) return null
            chain += current
            current = superOf[current] ?: return null
            if (chain.size > MAXIMUM_CHAIN) return null
        }
        return chain.filter { key(it, parameters) !in constructors }.map { it to parameters }
    }

    fun apply(node: DexFileNode): Int {
        var repaired = 0
        for (clz in node.clzs.orEmpty()) {
            for (method in clz.methods.orEmpty()) {
                val code = method.codeNode ?: continue
                forEachSite(code) { index, created, called ->
                    // Same decision as when the plan was built, so every repaired site has its constructor.
                    if (resolve(created, called.owner, called.parameterTypes.asList()) == null) return@forEachSite
                    val old = code.stmts[index] as MethodStmtNode
                    code.stmts[index] = MethodStmtNode(old.op, old.args, Method(created, called.name, called.parameterTypes, called.returnType))
                        .also { it.index = old.index }
                    repaired++
                }
            }
            synthesized[clz.className]?.forEach { parameters ->
                if (clz.methods == null) clz.methods = ArrayList()
                clz.methods.add(constructor(clz, parameters))
            }
        }
        return repaired
    }

    private fun constructor(clz: DexClassNode, parameters: List<String>): DexMethodNode {
        val method = Method(clz.className, "<init>", parameters.toTypedArray(), "V")
        val registers = 1 + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val code = DexCodeNode()
        code.visitRegister(registers)
        code.visitMethodStmt(
            Op.INVOKE_DIRECT, arguments(parameters),
            Method(requireNotNull(clz.superClass), "<init>", parameters.toTypedArray(), "V"),
        )
        code.visitStmt0R(Op.RETURN_VOID)
        code.visitEnd()
        return DexMethodNode(DexConstants.ACC_PUBLIC or DexConstants.ACC_CONSTRUCTOR or DexConstants.ACC_SYNTHETIC, method).also {
            it.codeNode = code
        }
    }

    /** `this` is register 0 and the parameters follow it; long/double take two registers. */
    private fun arguments(parameters: List<String>): IntArray {
        val registers = ArrayList<Int>()
        var next = 0
        registers += next++
        for (parameter in parameters) {
            registers += next
            next += if (parameter == "J" || parameter == "D") 2 else 1
        }
        return registers.toIntArray()
    }

    /** Wraps a reader so dex2jar sees the repaired classes; [repaired] counts the call sites that changed. */
    class Reader(private val delegate: BaseDexFileReader, private val plan: DexRepairPlan) : BaseDexFileReader {
        var repaired = 0
            private set

        override fun getDexVersion() = delegate.dexVersion
        override fun getClassNames(): List<String> = delegate.classNames
        override fun accept(visitor: DexFileVisitor) = accept(visitor, 0)
        override fun accept(visitor: DexFileVisitor, config: Int) {
            val node = DexFileNode()
            delegate.accept(node, config)
            repaired += plan.apply(node)
            node.accept(visitor)
        }

        override fun accept(visitor: DexFileVisitor, first: Int, second: Int) {
            val node = DexFileNode()
            delegate.accept(node, first, second)
            repaired += plan.apply(node)
            node.accept(visitor)
        }
    }

    private companion object {
        const val MAXIMUM_CHAIN = 32

        fun key(owner: String, parameters: List<String>) = owner + parameters.joinToString("", "(", ")")

        fun read(dex: ByteArray): DexFileNode = DexFileNode().also {
            DexFileReader(dex).accept(it, DexFileReader.SKIP_DEBUG or DexFileReader.IGNORE_READ_EXCEPTION)
        }
    }
}

/** Filter lists with hundreds of genre entries move the uninitialised object through registers for that long. */
private const val MAXIMUM_DISTANCE = 8192

/**
 * Calls [onSite] for each constructor call on an object that `new-instance LT;` created, when the constructor belongs
 * to a different class than T. The creation is the nearest earlier write to the receiver register: a valid DEX never
 * merges different uninitialised objects, so on every path to the call that write is the same `new-instance`, loops
 * and branches in between (the constructor arguments can contain whole loops) do not change that.
 */
private fun forEachSite(code: DexCodeNode, onSite: (index: Int, created: String, called: Method) -> Unit) {
    val stmts = code.stmts
    for (index in stmts.indices) {
        val call = stmts[index] as? MethodStmtNode ?: continue
        if ((call.op != Op.INVOKE_DIRECT && call.op != Op.INVOKE_DIRECT_RANGE) ||
            call.method.name != "<init>" || call.args.isEmpty()
        ) continue
        val created = creationBefore(stmts, index, call.args[0]) ?: continue
        if (created != call.method.owner) onSite(index, created, call.method)
    }
}

/** The type `new-instance` gave to [register] before [call], following plain moves of the uninitialised object. */
private fun creationBefore(stmts: List<DexStmtNode>, call: Int, start: Int): String? {
    var register = start
    var index = call - 1
    while (index >= 0 && call - index <= MAXIMUM_DISTANCE) {
        val stmt = stmts[index]
        when (writes(stmt, register)) {
            Writes.NO -> Unit
            Writes.UNKNOWN -> return null
            Writes.YES -> return when {
                stmt is TypeStmtNode && stmt.op == Op.NEW_INSTANCE && stmt.a == register -> stmt.type
                stmt is Stmt2RNode && stmt.op.name.startsWith("MOVE_OBJECT") && stmt.a == register -> {
                    register = stmt.b
                    index--
                    continue
                }
                else -> null
            }
        }
        index--
    }
    return null
}

private enum class Writes { YES, NO, UNKNOWN }

private val WIDE_RESULT = Regex("(ADD|SUB|MUL|DIV|REM|AND|OR|XOR|SHL|SHR|USHR|NEG|NOT)_(LONG|DOUBLE).*|(INT|FLOAT|LONG|DOUBLE)_TO_(LONG|DOUBLE)")

/** Whether [stmt] overwrites [register] (a wide result covers the register after its own as well). */
private fun writes(stmt: DexStmtNode, register: Int): Writes {
    fun target(destination: Int, op: Op): Writes {
        val wide = op.name.contains("WIDE") || WIDE_RESULT.matches(op.name)
        return if (destination == register || wide && destination + 1 == register) Writes.YES else Writes.NO
    }
    return when (stmt) {
        is DexLabelStmtNode, is AbstractMethodStmtNode, is FilledNewArrayStmtNode, is FillArrayDataStmtNode,
        is JumpStmtNode, is BaseSwitchStmtNode, is Stmt0RNode -> Writes.NO
        is Stmt1RNode -> if (stmt.op.name.startsWith("MOVE_RESULT") || stmt.op.name == "MOVE_EXCEPTION") target(stmt.a, stmt.op) else Writes.NO
        is Stmt2RNode -> target(stmt.a, stmt.op)
        is Stmt2R1NNode -> target(stmt.distReg, stmt.op)
        is Stmt3RNode -> if (stmt.op.name.startsWith("APUT")) Writes.NO else target(stmt.a, stmt.op)
        is ConstStmtNode -> target(stmt.a, stmt.op)
        is TypeStmtNode -> target(stmt.a, stmt.op)
        is FieldStmtNode -> if (stmt.op.name.contains("PUT")) Writes.NO else target(stmt.a, stmt.op)
        else -> Writes.UNKNOWN
    }
}