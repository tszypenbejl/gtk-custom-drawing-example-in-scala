package io.github.tszypenbejl.gtkcustomdrawingexample

import org.gnome.gtk.{Application, Button, DrawingArea, Frame, Gtk, ShadowType, VBox, Widget, Window}
import org.gnome.gdk.{Event, EventButton, EventConfigure, EventMask, EventMotion, MouseButton}
import org.freedesktop.cairo.{Content, Context, Surface}


private object Workarounds {
  private val surfaceReleaseMethod =
    Class.forName("org.freedesktop.cairo.Surface").getDeclaredMethod("release")
  private val contextReleaseMethod =
    Class.forName("org.freedesktop.cairo.Context").getDeclaredMethod("release")
  private val gtkWidgetConnectMethod =
    Class.forName("org.gnome.gtk.GtkWidget").getDeclaredMethod("connect",
      classOf[org.gnome.gtk.Widget],
      Class.forName("org.gnome.gtk.GtkWidget$ConfigureEventSignal"),
      false.getClass)

  surfaceReleaseMethod.setAccessible(true)
  contextReleaseMethod.setAccessible(true)
  gtkWidgetConnectMethod.setAccessible(true)

  // Surface.release method is protected for some reason.
  implicit class SurfaceWorkarounds(surface: Surface) {
    def release() = surfaceReleaseMethod.invoke(surface)
  }

  // Context.release method is protected for some reason.
  implicit class ContextWorkarounds(context: Context) {
    def release() = contextReleaseMethod.invoke(context)
  }

  // For some reason there is no easy way to connect configure event handler to a Widget
  // that does not happen to be a window.
  implicit class WidgetWorkarounds(widget: Widget) {
    def connect(event: Window.ConfigureEvent) =
      gtkWidgetConnectMethod.invoke(null, widget, event, new java.lang.Boolean(false))
  }
}


object DrawingApp extends App {
  import Workarounds._

  Gtk.init(null)

  def withContext(surface: Surface)(op: Context => Unit) = {
    val cr = new Context(surface)
    try {
      op(cr)
    } finally {
      cr.release()
    }
  }

  def clearSurface(surface: Surface) = {
    withContext(surface) { cr =>
      cr.setSource(1, 1, 1)
      cr.paint()
    }
  }
  
  def drawBrush(surface: Surface, w: Widget, x: Double, y: Double) = {
    val brushSize = 6;
    val rectX = x.toInt - brushSize / 2
    val rectY = y.toInt - brushSize / 2
    withContext(surface) { cr =>
      cr.rectangle(rectX, rectY, brushSize, brushSize)
      cr.fill()
    }
    w.queueDrawArea(rectX, rectY, brushSize, brushSize)
  }

  def resizeSurface(oldSurface: Surface, width: Int, height: Int) = {
    val newSurface = oldSurface.createSimilar(Content.COLOR, width, height)
    try {
      clearSurface(newSurface)
      withContext(newSurface) { cr =>
        cr.setSource(oldSurface, 0, 0)
        cr.paint()
      }
      oldSurface.release()
    } catch {
      case e: Exception =>
        newSurface.release()
        throw e
    }
    newSurface
  }


  val gtkApp = new Application("io.github.tszypenbejl.gtkcustomdrawingexample")
  gtkApp.connect(new Application.Startup {
    override def onStartup(source: Application): Unit = {
      val window = new Window
      source.addWindow(window)
      window.setTitle("Drawing Area")
      window.setBorderWidth(8)
      window.connect(new Window.DeleteEvent {
        override def onDeleteEvent(source: Widget, event: Event): Boolean = {
          source.destroy()
          true
        }
      })

      val vbox = new VBox(false, 3)
      window.add(vbox)

      val frame = new Frame(null)
      frame.setShadowType(ShadowType.IN)
      vbox.packStart(frame, true, true, 0)

      var drawingSurface: Surface = null

      val drawingArea = new DrawingArea
      drawingArea.setSizeRequest(300, 200)
      drawingArea.connect(new Widget.Draw {
        override def onDraw(source: Widget, cr: Context) = {
          if (null == drawingSurface) {
            drawingSurface = cr.getTarget.createSimilar(Content.COLOR,
              source.getAllocatedWidth, source.getAllocatedHeight)
            clearSurface(drawingSurface)
          }
          cr.setSource(drawingSurface, 0, 0)
          cr.paint()
          false
        }
      })
      drawingArea.connect(new Window.ConfigureEvent {
        def onConfigureEvent(source: Widget, event: EventConfigure ) = {
          if (null != drawingSurface) {
            drawingSurface = resizeSurface(drawingSurface,
              source.getAllocatedWidth, source.getAllocatedHeight)
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
      frame.add(drawingArea)

      val button = new Button("Quit")
      button.connect(new Button.Clicked {
        override def onClicked(source: Button): Unit = window.destroy()
      })
      vbox.packStart(button, false, false, 0)

      window.showAll()
    }
  })
  gtkApp.connect(new Application.Activate {
    override def onActivate(source: Application): Unit = ()
  })
  System.exit(gtkApp.run(args))
}
