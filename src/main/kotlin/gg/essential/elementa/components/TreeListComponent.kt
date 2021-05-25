package gg.essential.elementa.components

import gg.essential.elementa.UIComponent
import gg.essential.elementa.constraints.*
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.pixels
import kotlin.math.max

abstract class TreeArrowComponent : UIComponent() {
    abstract fun open()
    abstract fun close()
}

open class TreeListComponent(roots: List<TreeNode>) : UIContainer() {
    constructor(root: TreeNode) : this(listOf(root))

    constructor() : this(emptyList())

    init {
        constrain {
            width = ChildBasedMaxSizeConstraint()
            height = ChildBasedSizeConstraint()
        }

        setRoots(roots)
    }

    fun setRoots(roots: List<TreeNode>) = apply {
        clearChildren()
        roots.forEach { it.displayComponent childOf this }
    }
}

abstract class TreeNode {
    open var indentationOffset: Float = 10f

    private var displayComponentBacker: TreeNodeComponent? = null

    val displayComponent: TreeNodeComponent
        get() {
            if (displayComponentBacker == null)
                displayComponentBacker = TreeNodeComponent()
            return displayComponentBacker!!
        }

    abstract fun getArrowComponent(): TreeArrowComponent

    abstract fun getPrimaryComponent(): UIComponent

    fun addChild(node: TreeNode) = apply {
        displayComponent.addNodeChild(node.displayComponent)
    }

    fun removeChildAt(index: Int) = apply {
        displayComponent.removeNodeChildAt(index)
    }

    fun clearChildren() = apply {
        displayComponent.clearNodeChildren()
    }

    fun withChildren(childBuilder: TreeNodeBuilder.() -> Unit): TreeNode {
        val builder = TreeNodeBuilder(this)
        builder.apply(childBuilder)
        return builder.root
    }

    inner class TreeNodeComponent : UIContainer() {
        private val arrowComponent = getArrowComponent()
        private var opened = false
        private val childContainer = UIContainer().constrain {
            x = indentationOffset.pixels()
            y = SiblingConstraint()
            width = ChildBasedMaxSizeConstraint()
            height = ChildBasedSizeConstraint()
        }
        private val ownContent = UIContainer().constrain {
            width = ChildBasedSizeConstraint()
            height = ChildBasedMaxSizeConstraint()
        }

        init {
            constrain {
                y = SiblingConstraint()
                width = basicWidthConstraint {
                    max(ownContent.getWidth(), childContainer.getWidth() + indentationOffset)
                }
                height = ChildBasedSizeConstraint()
            }

            ownContent childOf this
            arrowComponent childOf ownContent

            UIContainer().constrain {
                x = SiblingConstraint()
                width = 5.pixels()
            } childOf ownContent

            getPrimaryComponent() childOf ownContent
            childContainer childOf this
            childContainer.hide(instantly = true)

            arrowComponent.onMouseClick { event ->
                event.stopImmediatePropagation()

                if (opened) {
                    arrowComponent.close()
                    childContainer.hide()
                } else {
                    arrowComponent.open()
                    childContainer.unhide()
                }
                opened = !opened
            }
        }

        fun addNodeChild(component: UIComponent) = apply {
            childContainer.addChild(component)
        }

        fun removeNodeChildAt(index: Int) = apply {
            childContainer.children.removeAt(index)
        }

        fun clearNodeChildren() = apply {
            childContainer.clearChildren()
        }
    }
}

class TreeNodeBuilder(val root: TreeNode) {
    fun add(node: TreeNode) {
        root.addChild(node)
    }
}