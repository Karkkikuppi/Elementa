package club.sk1er.elementa

import club.sk1er.elementa.components.UIBlock
import club.sk1er.elementa.components.Window
import club.sk1er.elementa.constraints.*
import club.sk1er.elementa.constraints.animation.*
import club.sk1er.elementa.dsl.animate
import club.sk1er.elementa.effects.Effect
import club.sk1er.elementa.effects.ScissorEffect
import club.sk1er.elementa.events.UIClickEvent
import club.sk1er.elementa.events.UIScrollEvent
import club.sk1er.elementa.utils.TriConsumer
import club.sk1er.elementa.utils.observable
import club.sk1er.mods.core.universal.UMouse
import club.sk1er.mods.core.universal.UResolution
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.reflect.KMutableProperty0

/**
 * UIComponent is the base of all drawing, meaning
 * everything visible on the screen is a UIComponent.
 */
abstract class UIComponent : Observable() {
    var componentName: String = this.javaClass.simpleName
    open val children = CopyOnWriteArrayList<UIComponent>().observable()
    val effects = mutableListOf<Effect>()

    open lateinit var parent: UIComponent

    open val hasParent: Boolean
        get() = ::parent.isInitialized

    var constraints = UIConstraints(this)
        set(value) {
            field = value
            setChanged()
            notifyObservers(constraints)
        }

    /* Bubbling Events */
    val mouseClickListeners = mutableListOf<UIComponent.(UIClickEvent) -> Unit>()
    private var lastClickTime = System.currentTimeMillis()
    private var lastClickCount = 0
    var mouseScrollListeners = mutableListOf<UIComponent.(UIScrollEvent) -> Unit>()

    /* Non-Bubbling Events */
    var mouseReleaseAction: UIComponent.() -> Unit = {}
    var mouseEnterAction: UIComponent.() -> Unit = {}
    var mouseLeaveAction: UIComponent.() -> Unit = {}
    var mouseDragAction: UIComponent.(mouseX: Float, mouseY: Float, button: Int) -> Unit = { _, _, _ -> }
    var keyTypeAction: UIComponent.(typedChar: Char, keyCode: Int) -> Unit = { _, _ -> }

    private var currentlyHovered = false
    private var beforeHideAnimation: AnimatingConstraints.() -> Unit = { }
    private var afterUnhideAnimation: AnimatingConstraints.() -> Unit = { }
    private var onFocus: (UIComponent.() -> Unit)? = null
    private var onFocusLost: (UIComponent.() -> Unit)? = null

    /**
     * Required for [unhide] so it can insert this component
     * back into the same position
     */
    private var indexInParent = 0

    // Field animation API
    private val fieldAnimationQueue = ConcurrentLinkedDeque<FieldAnimationComponent<*>>()

    // Timer API
    private val activeTimers = mutableMapOf<Int, Timer>()
    // We have to store stopped timers separately to avoid ConcurrentModificationException
    private val stoppedTimers = mutableSetOf<Int>()
    private var nextTimerId = 0

    private var isInitialized = false
    private var isFloating = false

    /**
     * Adds [component] to this component's children tree,
     * as well as sets [component]'s parent to this component.
     */
    open fun addChild(component: UIComponent) = apply {
        component.parent = this
        children.add(component)
    }

    /**
     * Helper for inserting a child at a specific index in the
     * children list. If a bad index is given to the method,
     * it logs an error message and returns without modifying
     * this component.
     */
    open fun insertChildAt(component: UIComponent, index: Int) = apply {
        if (index < 0 || index >= children.size) {
            println("Bad index given to insertChildAt (index: $index, children size: ${children.size}")
            return@apply
        }

        component.parent = this
        children.add(index, component)
    }

    /**
     * Helper for inserting a child before an existing child.
     * If the targetComponent is not a child of this component,
     * the method logs an error and returns without modifying
     * this component.
     */
    open fun insertChildBefore(newComponent: UIComponent, targetComponent: UIComponent) = apply {
        val indexOfExisting = children.indexOf(targetComponent)
        if (indexOfExisting == -1) {
            println("targetComponent given to insertChildBefore is not a child of this component")
            return@apply
        }

        newComponent.parent = this
        children.add(indexOfExisting, newComponent)
    }

    /**
     * Helper for inserting a child after an existing child.
     * If the targetComponent is not a child of this component,
     * the method logs an error and returns without modifying
     * this component.
     */
    open fun insertChildAfter(newComponent: UIComponent, targetComponent: UIComponent) = apply {
        val indexOfExisting = children.indexOf(targetComponent)
        if (indexOfExisting == -1) {
            println("targetComponent given to insertChildAfter is not a child of this component")
            return@apply
        }

        newComponent.parent = this
        children.add(indexOfExisting + 1, newComponent)
    }

    /**
     * Helper for replacing a child with another child. If
     * the componentToReplace is not a child of this component,
     * the method logs an error and returns without modifying
     * this component.
     */
    open fun replaceChild(newComponent: UIComponent, componentToReplace: UIComponent) = apply {
        val indexOfExisting = children.indexOf(componentToReplace)
        if (indexOfExisting == -1) {
            println("componentToReplace given to replaceChild is not a child of this component")
            return@apply
        }

        newComponent.parent = this
        children.removeAt(indexOfExisting)
        children.add(indexOfExisting, newComponent)
    }

    /**
     * Wrapper for [addChild].
     */
    open fun addChildren(vararg components: UIComponent) = apply {
        components.forEach { addChild(it) }
    }

    /**
     * Remove's [component] from this component's children, effectively
     * removing it from the hierarchy tree.
     *
     * However, [component]'s parent still references this.
     */
    open fun removeChild(component: UIComponent) = apply {
        children.remove(component)
    }

    /**
     * Removes all children, according to the same rules as [removeChild]
     */
    open fun clearChildren() = apply {
        children.clear()
    }

    /**
     * Kotlin wrapper for [childrenOfType]
     */
    inline fun <reified T> childrenOfType() = childrenOfType(T::class.java)

    /**
     * Fetches all children of this component that are instances of [clazz]
     */
    open fun <T> childrenOfType(clazz: Class<T>) = children.filterIsInstance(clazz)

    /**
     * Constructs an animation object specific to this component.
     *
     * A convenient Kotlin wrapper can be found at [club.sk1er.elementa.dsl.animate]
     */
    fun makeAnimation() = AnimatingConstraints(this, constraints)

    /**
     * Begin animating to a previously constructed animation.
     *
     * This is handled internally by the [club.sk1er.elementa.dsl.animate] dsl if used.
     */
    fun animateTo(constraints: AnimatingConstraints) {
        this.constraints = constraints
    }

    /**
     * Enables a set of effects to be applied when this component draws.
     */
    fun enableEffects(vararg effects: Effect) = apply {
        effects.forEach {
            it.bindComponent(this)
            if (isInitialized)
                it.setup()
        }
        this.effects.addAll(effects)
    }

    /**
     * Enables a single effect to be applied when the component draws.
     */
    fun enableEffect(effect: Effect) = apply {
        effect.bindComponent(this)
        if (isInitialized)
            effect.setup()
        this.effects.add(effect)
    }

    inline fun <reified T> removeEffect() {
        this.effects.removeIf { it is T }
    }

    fun <T : Effect> removeEffect(clazz: Class<T>) {
        this.effects.removeIf { clazz.isInstance(it) }
    }

    fun removeEffect(effect: Effect) {
        this.effects.remove(effect)
    }

    fun setChildOf(parent: UIComponent) = apply {
        parent.addChild(this)
    }

    fun setX(constraint: XConstraint) = apply {
        this.constraints.withX(constraint)
    }

    fun setY(constraint: YConstraint) = apply {
        this.constraints.withY(constraint)
    }

    fun setWidth(constraint: WidthConstraint) = apply {
        this.constraints.withWidth(constraint)
    }

    fun setHeight(constraint: HeightConstraint) = apply {
        this.constraints.withHeight(constraint)
    }

    fun setRadius(constraint: RadiusConstraint) = apply {
        this.constraints.withRadius(constraint)
    }

    fun setTextScale(constraint: HeightConstraint) = apply {
        this.constraints.withTextScale(constraint)
    }

    fun setColor(constraint: ColorConstraint) = apply {
        this.constraints.withColor(constraint)
    }

    open fun getLeft() = constraints.getX()

    open fun getTop() = constraints.getY()

    open fun getRight() = getLeft() + getWidth()

    open fun getBottom() = getTop() + getHeight()

    open fun getWidth() = constraints.getWidth()

    open fun getHeight() = constraints.getHeight()

    open fun getRadius() = constraints.getRadius()

    open fun getTextScale() = constraints.getTextScale()

    open fun getColor() = constraints.getColor()

    open fun isPositionCenter(): Boolean {
        return false
    }

    /**
     * Checks if the player's mouse is currently on top of this component.
     *
     * It simply checks the bounds of this component's constraints (i.e. x,y and width,height).
     * If this component has children outside of its parent's bounds (which probably is not a good idea anyways...)
     * that are being hovered, it will NOT consider this component as hovered.
     */
    open fun isHovered(): Boolean {
        val (mouseX, mouseY) = getMousePosition()
        return isPointInside(mouseX, mouseY)
    }

    protected fun getMousePosition(): Pair<Float, Float> {
        val scaledHeight = UResolution.scaledHeight
        val mouseX = UMouse.getScaledX().toFloat()
        val mouseY = scaledHeight - UMouse.getTrueY().toFloat() * scaledHeight / UResolution.windowHeight - 1f
        return mouseX to mouseY
    }

    open fun isPointInside(x: Float, y: Float): Boolean {
        return x > getLeft()
                && x < getRight()
                && y > getTop()
                && y < getBottom()
    }

    open fun hitTest(x: Float, y: Float): UIComponent {
        for (i in children.lastIndex downTo 0) {
            val child = children[i]

            if (child.isPointInside(x, y)) {
                return child.hitTest(x, y)
            }
        }

        return this
    }

    open fun isChildOf(component: UIComponent): Boolean {
        var currentParent = parent

        do {
            if (currentParent == component)
                return true
            currentParent = currentParent.parent
        } while (currentParent.parent != currentParent)

        return false
    }

    /**
     * Called once before the component's first draw. This method can
     * be used to do any initialization that dependent on the component
     * hierarchy (such as calls to getWidth/getHeight/etc).
     */
    open fun afterInitialization() {
        effects.forEach { it.setup() }
    }

    /**
     * Does the actual drawing for this component, meant to be overridden by specific components.
     * Also does some housekeeping dealing with hovering and effects.
     */
    open fun draw() {
        if (!isInitialized) {
            isInitialized = true
            afterInitialization()
        }

        // Draw colored outline around the components
        if (IS_DEBUG) {
            if (ScissorEffect.currentScissorState != null) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
            }

            val left = getLeft().toDouble()
            val right = getRight().toDouble()
            val top = getTop().toDouble()
            val bottom = getBottom().toDouble()

            val color = getDebugColor(depth(), (parent.hashCode() / PI) % PI)

            // Top outline block
            UIBlock.drawBlock(
                color,
                left - DEBUG_OUTLINE_WIDTH,
                top - DEBUG_OUTLINE_WIDTH,
                right + DEBUG_OUTLINE_WIDTH,
                top
            )

            // Right outline block
            UIBlock.drawBlock(color, right, top, right + DEBUG_OUTLINE_WIDTH, bottom)

            // Bottom outline block
            UIBlock.drawBlock(
                color,
                left - DEBUG_OUTLINE_WIDTH,
                bottom,
                right + DEBUG_OUTLINE_WIDTH,
                bottom + DEBUG_OUTLINE_WIDTH
            )

            // Left outline block
            UIBlock.drawBlock(color, left - DEBUG_OUTLINE_WIDTH, top, left, bottom)

            if (ScissorEffect.currentScissorState != null) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
            }
        }

        beforeChildrenDraw()

        val parentWindow = Window.of(this)

        this.children.filterNot {
            it.isFloating
        }.forEach { child ->
            // If the child is outside the current viewport, don't waste time drawing
            if (!this.alwaysDrawChildren() && !parentWindow.isAreaVisible(
                    child.getLeft().toDouble(),
                    child.getTop().toDouble(),
                    child.getRight().toDouble(),
                    child.getBottom().toDouble()
                )
            ) return@forEach

            child.draw()
        }

        if (this is Window)
            drawFloatingComponents()

        afterDraw()
    }

    open fun beforeDraw() {
        effects.forEach { it.beforeDraw() }
    }

    open fun afterDraw() {
        effects.forEach { it.afterDraw() }
    }

    open fun beforeChildrenDraw() {
        effects.forEach { it.beforeChildrenDraw() }
    }

    open fun mouseMove(window: Window) {
        val hovered = isHovered() && window.hoveredFloatingComponent.let {
            it == null || it == this || isComponentInParentChain(it)
        }

        if (hovered && !currentlyHovered) {
            mouseEnterAction()
            currentlyHovered = true
        } else if (!hovered && currentlyHovered) {
            mouseLeaveAction()
            currentlyHovered = false
        }

        this.children.forEach { it.mouseMove(window) }
    }

    /**
     * Runs the set [onMouseClick] method for the component and it's children.
     * Use this in the proper mouse click event to cascade all component's mouse click events.
     * Most common use is on the [Window] object.
     */
    open fun mouseClick(mouseX: Double, mouseY: Double, button: Int) {
        val clicked = hitTest(mouseX.toFloat(), mouseY.toFloat())

        lastClickCount = if (System.currentTimeMillis() - lastClickTime < 500) lastClickCount + 1 else 1
        lastClickTime = System.currentTimeMillis()

        clicked.fireClickEvent(UIClickEvent(mouseX.toFloat(), mouseY.toFloat(), button, clicked, clicked, lastClickCount))
    }

    protected fun fireClickEvent(event: UIClickEvent) {
        for (listener in mouseClickListeners) {
            this.listener(event)

            if (event.propagationStoppedImmediately) return
        }

        if (!event.propagationStopped && parent != this) {
            parent.fireClickEvent(event.copy(currentTarget = parent))
        }
    }

    /**
     * Runs the set [onMouseRelease] method for the component and it's children.
     * Use this in the proper mouse release event to cascade all component's mouse release events.
     * Most common use is on the [Window] object.
     */
    open fun mouseRelease() {
        mouseReleaseAction()

        this.children.forEach { it.mouseRelease() }
    }

    /**
     * Runs the set [onMouseScroll] method for the component and it's children.
     * Use this in the proper mouse scroll event to cascade all component's mouse scroll events.
     * Most common use is on the [Window] object.
     */
    open fun mouseScroll(delta: Double) {
        if (delta == 0.0) return

        for (i in children.lastIndex downTo 0) {
            val child = children[i]

            if (child.isHovered()) {
                return child.mouseScroll(delta)
            }
        }

        fireScrollEvent(UIScrollEvent(delta, this, this))
    }

    protected fun fireScrollEvent(event: UIScrollEvent) {
        for (listener in mouseScrollListeners) {
            this.listener(event)

            if (event.propagationStoppedImmediately) return
        }

        if (!event.propagationStopped && parent != this) {
            parent.fireScrollEvent(event.copy(currentTarget = parent))
        }
    }


    @Deprecated(
        "You no longer need to call mouseDrag manually, Elementa handles it internally.",
        level = DeprecationLevel.ERROR
    )
    open fun mouseDrag(mouseX: Int, mouseY: Int, button: Int) {
        // no-op
    }

    /**
     * Runs the set [onMouseDrag] method for the component and it's children.
     * Use this in the proper mouse drag event to cascade all component's mouse scroll events.
     * Most common use is on the [Window] object.
     */
    open fun dragMouse(mouseX: Int, mouseY: Int, button: Int) {
        mouseDragAction(mouseX - getLeft(), mouseY - getTop(), button)

        children.forEach { it.dragMouse(mouseX, mouseY, button) }
    }

    open fun keyType(typedChar: Char, keyCode: Int) {
        keyTypeAction(typedChar, keyCode)
    }

    open fun animationFrame() {
        constraints.animationFrame()

        effects.forEach(Effect::animationFrame)
        this.children.forEach(UIComponent::animationFrame)

        // Process field animations
        val queueIterator = fieldAnimationQueue.iterator()
        queueIterator.forEachRemaining {
            it.animationFrame()
            if (it.isComplete())
                queueIterator.remove()
        }

        // Process timers
        val timerIterator = activeTimers.iterator()
        timerIterator.forEachRemaining { (id, timer) ->
            if (id in stoppedTimers)
                return@forEachRemaining

            val time = System.currentTimeMillis()
            timer.timeLeft -= (time - timer.lastTime)
            timer.lastTime = time

            if (!timer.hasDelayed && timer.timeLeft <= 0L) {
                timer.hasDelayed = true
                timer.timeLeft += timer.interval
            }

            while (timer.timeLeft <= 0L) {
                timer.callback(id)
                timer.timeLeft += timer.interval
            }
        }

        stoppedTimers.forEach { activeTimers.remove(it) }
    }

    open fun alwaysDrawChildren(): Boolean {
        return false
    }

    fun depth(): Int {
        var current = this
        var depth = 0

        while (current !is Window && current.hasParent && current.parent != current) {
            current = current.parent
            depth++
        }

        if (current !is Window)
            throw IllegalStateException("No window parent? It's possible you haven't called Window.addChild() at this point in time.")

        return depth
    }

    /**
     * Adds a method to be run when mouse is clicked within the component.
     */
    fun onMouseClick(method: UIComponent.(event: UIClickEvent) -> Unit) = apply {
        mouseClickListeners.add(method)
    }

    /**
     * Adds a method to be run when mouse is clicked within the component.
     */
    fun onMouseClickConsumer(method: Consumer<UIClickEvent>) = apply {
        mouseClickListeners.add { method.accept(it) }
    }

    /**
     * Adds a method to be run when mouse is released within the component.
     */
    fun onMouseRelease(method: UIComponent.() -> Unit) = apply {
        mouseReleaseAction = method
    }

    /**
     * Adds a method to be run when mouse is released within the component.
     */
    fun onMouseReleaseRunnable(method: Runnable) = apply {
        mouseReleaseAction = { method.run() }
    }

    /**
     * Adds a method to be run when mouse is dragged anywhere on screen.
     * This does not check if mouse is in component.
     */
    fun onMouseDrag(method: UIComponent.(mouseX: Float, mouseY: Float, mouseButton: Int) -> Unit) = apply {
        mouseDragAction = method
    }

    /**
     * Adds a method to be run when mouse is dragged anywhere on screen.
     * This does not check if mouse is in component.
     */
    fun onMouseDragConsumer(method: TriConsumer<Float, Float, Int>) = apply {
        mouseDragAction = { t: Float, u: Float, v: Int -> method.accept(t, u, v) }
    }

    /**
     * Adds a method to be run when mouse enters the component.
     */
    fun onMouseEnter(method: UIComponent.() -> Unit) = apply {
        mouseEnterAction = method
    }

    /**
     * Adds a method to be run when mouse enters the component.
     */
    fun onMouseEnterRunnable(method: Runnable) = apply {
        mouseEnterAction = { method.run() }
    }

    /**
     * Adds a method to be run when mouse leaves the component.
     */
    fun onMouseLeave(method: UIComponent.() -> Unit) = apply {
        mouseLeaveAction = method
    }

    /**
     * Adds a method to be run when mouse leaves the component.
     */
    fun onMouseLeaveRunnable(method: Runnable) = apply {
        mouseLeaveAction = { method.run() }
    }

    /**
     * Adds a method to be run when mouse scrolls while in the component.
     */
    fun onMouseScroll(method: UIComponent.(UIScrollEvent) -> Unit) = apply {
        mouseScrollListeners.add(method)
    }

    /**
     * Adds a method to be run when mouse scrolls while in the component.
     */
    fun onMouseScrollConsumer(method: Consumer<UIScrollEvent>) = apply {
        mouseScrollListeners.add { method.accept(it) }
    }

    fun onKeyType(method: UIComponent.(typedChar: Char, keyCode: Int) -> Unit) = apply {
        keyTypeAction = method
    }

    fun onKeyTypeConsumer(method: BiConsumer<Char, Int>) {
        keyTypeAction = { t: Char, u: Int -> method.accept(t, u) }
    }

    /*
     Hide API
     */

    /**
     * Hides this component. Behind the scenes, "hiding" entails removal of this component
     * from the entire hierarchy, leading to changes in sibling/children relationships.
     *
     * This also means hidden components will no longer receive events, or be drawn in any way.
     *
     * NOTE: Make sure to release any focus on this component, because it will likely cause
     * unintended side effects.
     *
     * @param instantly normally, hiding a component will run its before-hide
     * animations, and when they are complete, it will fully remove the component.
     * If [instantly] is true, it will skip the animation cycle and instantly remove the component.
     */
    @JvmOverloads
    fun hide(instantly: Boolean = false) {
        if (instantly) {
            indexInParent = parent.children.indexOf(this@UIComponent)
            parent.removeChild(this@UIComponent)
            return
        }

        animate {
            this.beforeHideAnimation()

            val comp = this.completeAction
            onComplete {
                comp()

                indexInParent = parent.children.indexOf(this@UIComponent)
                parent.removeChild(this@UIComponent)
            }
        }
    }

    /**
     * Re-enables this component. This will do the opposite of [hide] and re-add this component
     * to the hierarchy, underneath the same parent.
     */
    fun unhide(useLastPosition: Boolean = true) {
        if (parent.children.contains(this)) {
            return
        }

        if (useLastPosition && indexInParent >= 0 && indexInParent < parent.children.size) {
            parent.children.add(indexInParent, this@UIComponent)
        } else {
            parent.children.add(this@UIComponent)
        }

        animate {
            this.afterUnhideAnimation()
        }
    }

    fun animateBeforeHide(animation: AnimatingConstraints.() -> Unit) = apply {
        beforeHideAnimation = animation
    }

    fun animateAfterUnhide(animation: AnimatingConstraints.() -> Unit) = apply {
        afterUnhideAnimation = animation
    }

    /**
     * Focus API
     */

    fun grabWindowFocus() {
        Window.of(this).focus(this)
    }

    fun onFocus(listener: UIComponent.() -> Unit) = apply {
        onFocus = listener
    }

    fun focus() {
        onFocus?.invoke(this)
    }

    fun releaseWindowFocus() {
        Window.of(this).unfocus()
    }

    fun onFocusLost(listener: UIComponent.() -> Unit) = apply {
        onFocusLost = listener
    }

    fun loseFocus() {
        onFocusLost?.invoke(this)
    }

    /**
     * Floating API
     */

    fun setFloating(floating: Boolean) {
        isFloating = floating

        if (floating) {
            Window.of(this).addFloatingComponent(this)
        } else {
            Window.of(this).removeFloatingComponent(this)
        }
    }

    /**
     * Field animation API
     */

    fun KMutableProperty0<Int>.animate(strategy: AnimationStrategy, time: Float, newValue: Int, delay: Float = 0f) {
        if (!validateAnimationFields(time, delay))
            return

        if (time == 0f) {
            this.set(newValue)
            return
        }

        val totalFrames = (time * Window.of(this@UIComponent).animationFPS).toInt()
        val totalDelay = (delay * Window.of(this@UIComponent).animationFPS).toInt()

        fieldAnimationQueue.removeIf { it.field == this }
        fieldAnimationQueue.addFirst(IntFieldAnimationComponent(this, strategy, totalFrames, this.get(), newValue, totalDelay))
    }

    fun KMutableProperty0<Float>.animate(strategy: AnimationStrategy, time: Float, newValue: Float, delay: Float = 0f) {
        if (!validateAnimationFields(time, delay))
            return

        if (time == 0f) {
            this.set(newValue)
            return
        }

        val totalFrames = (time * Window.of(this@UIComponent).animationFPS).toInt()
        val totalDelay = (delay * Window.of(this@UIComponent).animationFPS).toInt()

        fieldAnimationQueue.removeIf { it.field == this }
        fieldAnimationQueue.addFirst(FloatFieldAnimationComponent(this, strategy, totalFrames, this.get(), newValue, totalDelay))
    }

    fun KMutableProperty0<Long>.animate(strategy: AnimationStrategy, time: Float, newValue: Long, delay: Float = 0f) {
        if (!validateAnimationFields(time, delay))
            return

        if (time == 0f) {
            this.set(newValue)
            return
        }

        val totalFrames = (time * Window.of(this@UIComponent).animationFPS).toInt()
        val totalDelay = (delay * Window.of(this@UIComponent).animationFPS).toInt()

        fieldAnimationQueue.removeIf { it.field == this }
        fieldAnimationQueue.addFirst(LongFieldAnimationComponent(this, strategy, totalFrames, this.get(), newValue, totalDelay))
    }

    fun KMutableProperty0<Double>.animate(strategy: AnimationStrategy, time: Float, newValue: Double, delay: Float = 0f) {
        if (!validateAnimationFields(time, delay))
            return

        if (time == 0f) {
            this.set(newValue)
            return
        }

        val totalFrames = (time * Window.of(this@UIComponent).animationFPS).toInt()
        val totalDelay = (delay * Window.of(this@UIComponent).animationFPS).toInt()

        fieldAnimationQueue.removeIf { it.field == this }
        fieldAnimationQueue.addFirst(DoubleFieldAnimationComponent(this, strategy, totalFrames, this.get(), newValue, totalDelay))
    }

    fun KMutableProperty0<Color>.animate(strategy: AnimationStrategy, time: Float, newValue: Color, delay: Float = 0f) {
        if (!validateAnimationFields(time, delay))
            return

        if (time == 0f) {
            this.set(newValue)
            return
        }

        val totalFrames = (time * Window.of(this@UIComponent).animationFPS).toInt()
        val totalDelay = (delay * Window.of(this@UIComponent).animationFPS).toInt()

        fieldAnimationQueue.removeIf { it.field == this }
        fieldAnimationQueue.addFirst(ColorFieldAnimationComponent(this, strategy, totalFrames, this.get(), newValue, totalDelay))
    }

    fun KMutableProperty0<*>.stopAnimating() {
        fieldAnimationQueue.removeIf { it.field == this }
    }

    private fun validateAnimationFields(time: Float, delay: Float): Boolean {
        if (time < 0f) {
            println("time parameter of field animation call cannot be less than 0")
            return false
        }
        if (delay < 0f) {
            println("delay parameter of field animation call cannot be less than 0")
            return false
        }
        return true
    }

    private fun isComponentInParentChain(target: UIComponent): Boolean {
        var component: UIComponent = this
        while (component.hasParent && component !is Window) {
            component = component.parent
            if (component == target)
                return true
        }

        return false
    }

    /**
     * Timer API
     */

    fun startTimer(interval: Long, delay: Long = 0, callback: (Int) -> Unit): Int {
        val id = nextTimerId++
        activeTimers[id] = Timer(delay, interval, callback)
        return id
    }

    fun stopTimer(id: Int) = stoppedTimers.add(id)

    fun timer(interval: Long, delay: Long = 0, callback: (Int) -> Unit): () -> Unit {
        val id = startTimer(interval, delay, callback)
        return { stopTimer(id) }
    }

    fun startDelay(delay: Long, callback: () -> Unit): Int {
        return startTimer(delay) {
            callback()
            stopTimer(it)
        }
    }

    fun stopDelay(id: Int) = stopTimer(id)

    fun delay(delay: Long, callback: () -> Unit): () -> Unit {
        val id = startDelay(delay, callback)
        return { stopDelay(id) }
    }

    private class Timer(val delay: Long, val interval: Long, val callback: (Int) -> Unit) {
        var hasDelayed = false
        var timeLeft = delay
        var lastTime = System.currentTimeMillis()

        init {
            if (delay == 0L) {
                hasDelayed = true
                timeLeft = interval
            }
        }
    }

    companion object {
        val IS_DEV = System.getProperty("elementa.dev")?.toBoolean() ?: false
        val IS_DEBUG = System.getProperty("elementa.debug")?.toBoolean() ?: false
        val DEBUG_OUTLINE_WIDTH = System.getProperty("elementa.debug.width")?.toDoubleOrNull() ?: 2.0

        private fun getDebugColor(depth: Int, offset: Double): Color {
            val step = depth.toDouble() / PI + offset

            val red = ((sin((step)) + 0.75) * 170).toInt().coerceIn(0..255)
            val green = ((sin(step + 2 * Math.PI / 3) + 0.75) * 170).toInt().coerceIn(0..255)
            val blue = ((sin(step + 4 * Math.PI / 3) + 0.75) * 170).toInt().coerceIn(0..255)
            return Color(red, green, blue, 255)
        }

        /**
         * Hints a number with respect to the current GUI scale.
         */
        fun guiHint(number: Float): Float {
            val factor = UResolution.scaleFactor.toFloat()
            return round(number * factor) / factor
        }

        /**
         * Hints a number with respect to the current GUI scale.
         */
        fun guiHint(number: Double): Double {
            val factor = UResolution.scaleFactor
            return round(number * factor) / factor
        }
    }
}
