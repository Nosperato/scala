package scala.swing.event

import java.awt.Point
import javax.swing.JComponent

sealed abstract class MouseEvent extends InputEvent {
  def peer: java.awt.event.MouseEvent
  def point: Point
}

sealed abstract class MouseButtonEvent extends MouseEvent {
  def clicks: Int
  def triggersPopup: Boolean
}
case class MouseClicked(val source: Component, point: Point, val modifiers: Int, 
                     clicks: Int, triggersPopup: Boolean)(val peer: java.awt.event.MouseEvent)
           extends MouseButtonEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx, e.getClickCount, e.isPopupTrigger)(e)             
}
case class MousePressed(val source: Component, point: Point, val modifiers: Int, 
                        clicks: Int, triggersPopup: Boolean)(val peer: java.awt.event.MouseEvent)
           extends MouseButtonEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx, e.getClickCount, e.isPopupTrigger)(e)             
}
case class MouseReleased(val source: Component, point: Point, val modifiers: Int, 
                        clicks: Int, triggersPopup: Boolean)(val peer: java.awt.event.MouseEvent)
           extends MouseButtonEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx, e.getClickCount, e.isPopupTrigger)(e)             
}

sealed abstract class MouseMotionEvent extends MouseEvent
case class MouseMoved(val source: Component, point: Point, val modifiers: Int)(val peer: java.awt.event.MouseEvent)
           extends MouseMotionEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx)(e)             
}
case class MouseDragged(val source: Component, point: Point, val modifiers: Int)(val peer: java.awt.event.MouseEvent)
           extends MouseMotionEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx)(e)             
}
case class MouseEntered(val source: Component, point: Point, val modifiers: Int)(val peer: java.awt.event.MouseEvent)
           extends MouseMotionEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx)(e)             
}
case class MouseExited(val source: Component, point: Point, val modifiers: Int)(val peer: java.awt.event.MouseEvent)
           extends MouseMotionEvent {
  def this(e: java.awt.event.MouseEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx)(e)             
}

case class MouseWheelMoved(val source: Component, point: Point, val modifiers: Int, rotation: Int)(val peer: java.awt.event.MouseEvent)
           extends MouseEvent {
  def this(e: java.awt.event.MouseWheelEvent) = this(UIElement.cachedWrapper(e.getSource.asInstanceOf[JComponent]), 
                               e.getPoint, e.getModifiersEx, e.getWheelRotation)(e)             
}