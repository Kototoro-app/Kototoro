package org.skepsun.kototoro.core.javascript

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 将默认 LegadoJavaAPI 与临时 bridge 组合为单个 `java` 绑定。
 *
 * 规则：
 * - 临时 bridge 优先，覆盖同名成员
 * - 默认 API 作为回退，避免丢失 JsExtensions 兼容能力
 */
internal class CompositeJavaBinding(
    private val primaryWrapper: Scriptable,
    private val fallbackWrapper: Scriptable,
    private val ownerScope: Scriptable,
) : ScriptableObject() {

    init {
        parentScope = ownerScope
        prototype = ScriptableObject.getObjectPrototype(ownerScope)
    }

    override fun getClassName(): String = "CompositeJavaBinding"

    override fun get(name: String, start: Scriptable): Any {
        return resolve(name) ?: Scriptable.NOT_FOUND
    }

    override fun has(name: String, start: Scriptable): Boolean {
        return resolve(name) != null
    }

    override fun getIds(): Array<Any> {
        val ids = LinkedHashSet<Any>()
        collectIds(primaryWrapper, ids)
        collectIds(fallbackWrapper, ids)
        return ids.toTypedArray()
    }

    private fun resolve(name: String): Any? {
        return resolveFrom(primaryWrapper, name) ?: resolveFrom(fallbackWrapper, name)
    }

    private fun resolveFrom(wrapper: Scriptable, name: String): Any? {
        val property = ScriptableObject.getProperty(wrapper, name)
        if (property == Scriptable.NOT_FOUND) return null
        return if (property is Function) {
            BoundRhinoFunction(
                targetWrapper = wrapper,
                delegate = property,
                ownerScope = ownerScope,
            )
        } else {
            property
        }
    }

    private fun collectIds(wrapper: Scriptable, target: MutableSet<Any>) {
        wrapper.ids.forEach { id ->
            if (id is String && ScriptableObject.getProperty(wrapper, id) != Scriptable.NOT_FOUND) {
                target += id
            }
        }
    }
}

private class BoundRhinoFunction(
    private val targetWrapper: Scriptable,
    private val delegate: Function,
    ownerScope: Scriptable,
) : BaseFunction() {

    init {
        parentScope = ownerScope
        prototype = ScriptableObject.getFunctionPrototype(ownerScope)
    }

    override fun getClassName(): String = "BoundRhinoFunction"

    override fun call(
        cx: RhinoContext,
        scope: Scriptable,
        thisObj: Scriptable,
        args: Array<out Any>,
    ): Any {
        return delegate.call(cx, scope, targetWrapper, args)
    }

    override fun construct(
        cx: RhinoContext,
        scope: Scriptable,
        args: Array<out Any>,
    ): Scriptable {
        return delegate.construct(cx, scope, args)
    }
}
