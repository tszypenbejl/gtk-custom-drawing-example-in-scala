package io.github.tszypenbejl.gtkcustomdrawingexample

import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget
import org.gnome.gtk.Frame
import org.gnome.gtk.Window
import org.gnome.gtk.DrawingArea
import org.gnome.gtk.ShadowType
import org.gnome.gdk.Event
import org.gnome.gdk.EventButton
import org.gnome.gdk.EventMotion
import org.gnome.gdk.MouseButton
import org.gnome.gdk.EventMask
import org.gnome.gdk.EventConfigure
import org.freedesktop.cairo.Context
import org.freedesktop.cairo.Content
import org.freedesktop.cairo.Surface


private object Workarounds {
  val surfaceReleaseMethod =
    Class.forName("org.freedesktop.cairo.Surface").getDeclaredMethod("release")
  surfaceReleaseMethod setAccessible true

  def releaseSurface(surface: Surface) = {
    // Cannot just call surface.release because it is protected for some reason
    surfaceReleaseMethod invoke surface
  }


  val contextReleaseMethod =
    Class.forName("org.freedesktop.cairo.Context").getDeclaredMethod("release")
  contextReleaseMethod setAccessible true

  def releaseContext(context: Context) = {
    // Cannot just call context.release because it is protected for some reason
    contextReleaseMethod invoke context
  }


  val gtkWidgetConnectMethod =
    Class.forName("org.gnome.gtk.GtkWidget").getDeclaredMethod("connect",
      classOf[org.gnome.gtk.Widget],
      Class.forName("org.gnome.gtk.GtkWidget$ConfigureEventSignal"),
      false.getClass)
  gtkWidgetConnectMethod setAccessible true

  def connect(widget: Widget, event: Window.ConfigureEvent) = {
    // For some reason there is no easy way to connect configure event handler
    // to a Widget that does not happen to be a window
    gtkWidgetConnectMethod.invoke(
        null, widget, event, new java.lang.Boolean(false))
  }
}


object DrawingApp extends App {

  def withContext(surface: Surface)(op: Context => Unit) = {
    val cr = new Context(surface)
    try {
      op(cr)
    } finally {
      Workarounds releaseContext cr
    }
  }

  def clearSurface(surface: Surface) = {
      withContext(surface) { cr =>
        cr.setSource(1, 1, 1)
        cr.paint()
      }
  }
  
  def drawBrush(surface: Surface, w: Widget, x: Double, y: Double) = {
      withContext(surface) { cr =>
        cr.rectangle(x - 3, y - 3, 6, 6)
        cr.fill()
      }
      w.queueDrawArea(x.toInt - 3, y.toInt - 3, 6, 6)
  }


  Gtk.init(args);

  val window = new Window
  window setTitle "Drawing Area"
  window.connect(new Window.DeleteEvent {
    override def onDeleteEvent(source: Widget, event: Event) = {
        Gtk.mainQuit
        false
    }
  })
  window.setBorderWidth(8)

  val frame = new Frame(null)
  frame.setShadowType(ShadowType.IN)
  window add frame

  var drawingSurface: Surface = null

  val drawingArea = new DrawingArea
  drawingArea.setSizeRequest(100, 100)
  frame add drawingArea
  drawingArea.connect(new Widget.Draw {
    override def onDraw(source: Widget, cr: Context) = {
      if (null == drawingSurface) {
        drawingSurface = cr.getTarget.createSimilar(Content.COLOR,
            source.getAllocatedWidth, source.getAllocatedHeight)
        clearSurface(drawingSurface)
      }
      cr.setSource(drawingSurface, 0.0, 0.0)
      cr.paint()
      false
    }
  })
  Workarounds.connect(drawingArea, new Window.ConfigureEvent {
    def onConfigureEvent(source: Widget, event: EventConfigure ) = {
      if (null != drawingSurface) {
        Workarounds releaseSurface drawingSurface
        drawingSurface = null
      }
      true
    }
  })
  drawingArea.connect(new Widget.MotionNotifyEvent {
    def onMotionNotifyEvent(source: Widget, event: EventMotion) = {
        if (null != drawingSurface)
          drawBrush(drawingSurface, source, event.getX, event.getY)
        null != drawingSurface
    }
  })
  drawingArea.connect(new Widget.ButtonPressEvent {
    def onButtonPressEvent(source: Widget, event: EventButton) = {
      if (null != drawingSurface) {
        event.getButton match {
          case MouseButton.LEFT => drawBrush(drawingSurface, source, event.getX, event.getY)
          case MouseButton.RIGHT => { clearSurface(drawingSurface); source.queueDraw() }
          case _ => ()
        }
      }
      null != drawingSurface
    }
  })
  drawingArea.addEvents(EventMask.BUTTON_PRESS)
  drawingArea.addEvents(EventMask.LEFT_BUTTON_MOTION)

  window.showAll()
  Gtk.main()
}
