package io.github.welingtonmonteiro.multiplerun.ui

import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon

import io.github.welingtonmonteiro.multiplerun.MultiplerunIcons

/**
 * Shows the number of running applications as a badge on the "Multiple Run Monitor" tool window
 * stripe button - so the count is visible without opening the monitor. The badge follows the same
 * set the monitor lists (every running process, grouped or standalone); it grows as apps start,
 * shrinks as they stop, and disappears entirely when nothing is running.
 *
 * Driven by process start/stop events ([ExecutionListener]) so it stays correct even while the
 * monitor tool window is closed - the monitor's own refresh timer only runs while it is visible.
 */
object MultiplerunMonitorBadge {

    /** Recomputes the running count and repaints the tool-window icon (safe from any thread). */
    fun refresh(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(MultiplerunMonitorToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater
            val count = runningCount(project)
            toolWindow.setIcon(if (count <= 0) MultiplerunIcons.OpenDashboard else countBadgeIcon(count))
        }
    }

    /** Every live process the monitor would list: a non-null, not-yet-terminated handler. */
    private fun runningCount(project: Project): Int {
        var count = 0
        for (descriptor in RunContentManager.getInstance(project).allDescriptors) {
            val handler = descriptor.processHandler
            if (handler != null && !handler.isProcessTerminated) {
                count++
            }
        }
        return count
    }

    /**
     * The tool-window icon with a small count bubble layered over its bottom-right corner. Built
     * with [LayeredIcon] rather than a hand-rolled [Icon]: the New UI stripe button hard-casts a
     * tool window's icon to `ScalableIcon` (`SquareStripeButton.updatePresentation`) - a plain
     * `Icon` throws `ClassCastException` there. `LayeredIcon` already implements it (it extends
     * `JBCachingScalableIcon`), the same mechanism the platform itself uses to compose badges/
     * overlays onto icons everywhere else, so it behaves correctly across the icon-handling
     * machinery in general, not just this one call site.
     */
    private fun countBadgeIcon(count: Int): Icon {
        val base = MultiplerunIcons.OpenDashboard
        val layered = LayeredIcon(2)
        layered.setIcon(base, 0)
        layered.setIcon(CountBubbleIcon(base.iconWidth, base.iconHeight, count), 1)
        return layered
    }

    /** Paints just the count bubble; sized to the base icon so it overlays at the same origin. */
    private class CountBubbleIcon(private val width: Int, private val height: Int, private val count: Int) : Icon {
        override fun getIconWidth(): Int = width
        override fun getIconHeight(): Int = height

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val text = if (count > 9) "9+" else count.toString()
                val diameter = Math.round(width * 0.68f)
                val bx = x + width - diameter
                val by = y + height - diameter

                g2.color = BADGE_BG
                g2.fillOval(bx, by, diameter, diameter)

                g2.color = JBColor.WHITE
                g2.font = g2.font.deriveFont(Font.BOLD, diameter * 0.72f)
                val fm = g2.fontMetrics
                val tx = bx + (diameter - fm.stringWidth(text)) / 2f
                val ty = by + (diameter - fm.height) / 2f + fm.ascent
                g2.drawString(text, tx, ty)
            } finally {
                g2.dispose()
            }
        }

        companion object {
            // brand primary (light / dark), the "live/action" accent from the Lanes guidelines
            private val BADGE_BG = JBColor(0x3574F0, 0x548AF7)
        }
    }
}

/** Subscribes each open project to run start/stop events and keeps the monitor badge in sync. */
class MultiplerunMonitorBadgeActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
                    MultiplerunMonitorBadge.refresh(project)

                override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
                    MultiplerunMonitorBadge.refresh(project)

                override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) =
                    MultiplerunMonitorBadge.refresh(project)
            })
        MultiplerunMonitorBadge.refresh(project)
    }
}
