package club.sk1er.elementa.components

import club.sk1er.elementa.UIComponent
import club.sk1er.elementa.effects.ScissorEffect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.opengl.GL11

/**
 * "Root" component. All components MUST have a Window in their hierarchy in order to do any rendering
 * or animating.
 */
class Window(val animationFPS: Int = 244) : UIComponent() {
    private var systemTime = -1L
    var scaledResolution = ScaledResolution(Minecraft.getMinecraft())

    init {
        super.parent = this
    }

    override fun draw() {
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
        GL11.glClearStencil(0)

        scaledResolution = ScaledResolution(Minecraft.getMinecraft())

        if (systemTime == -1L) systemTime = System.currentTimeMillis()

        while (this.systemTime < System.currentTimeMillis() + 1000 / animationFPS) {
            animationFrame()

            this.systemTime += 1000 / animationFPS;
        }

        super.draw()
    }

    override fun getLeft(): Float {
        return 0f
    }

    override fun getTop(): Float {
        return 0f
    }

    override fun getWidth(): Float {
        return scaledResolution.scaledWidth.toFloat()
    }

    override fun getHeight(): Float {
        return scaledResolution.scaledHeight.toFloat()
    }

    override fun getRight() = getWidth()
    override fun getBottom() = getHeight()

    fun isAreaVisible(x1: Double, y1: Double, x2: Double, y2: Double): Boolean {
        if (x2 < getLeft() || x1 > getRight() || y2 < getTop() || y1 > getBottom()) return false

        val currentScissor = ScissorEffect.currentScissorState ?: return true
        val sf = scaledResolution.scaleFactor

        val realX = currentScissor.x / sf
        val bottomY = ((scaledResolution.scaledHeight * sf) - currentScissor.y) / sf

        return x2 > realX && x1 < realX + currentScissor.width / sf && y2 >= bottomY - (currentScissor.height / sf) && y1 <= bottomY
    }

    companion object {
        fun of(component: UIComponent): Window {
            var current = component

            try {
                while (current !is Window && current.parent != current) {
                    current = current.parent
                }
            } catch (e: UninitializedPropertyAccessException) {
                throw IllegalStateException("No window parent? It's possible you haven't called Window.addChild() at this point in time.")
            }

            return current as? Window
                ?: throw IllegalStateException("No window parent? It's possible you haven't called Window.addChild() at this point in time.")
        }
    }
}