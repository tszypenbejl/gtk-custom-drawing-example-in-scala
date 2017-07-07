package io.github.tszypenbejl.gtkcustomdrawingexample

import org.gnome.gtk.{Application, Button, ColorButton, DrawingArea, Frame, Gtk, HBox, ShadowType, VBox, Widget, Window}
import org.gnome.gdk.{Event, EventButton, EventConfigure, EventMask, EventMotion, MouseButton, RGBA}
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

  def drawBrush(surface: Surface, color: RGBA, w: Widget, x: Double, y: Double) = {
    val brushSize = 6;
    val rectX = x.toInt - brushSize / 2
    val rectY = y.toInt - brushSize / 2
    withContext(surface) { cr =>
      cr.setSource(color)
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
      val vBox = new VBox(false, 3)
      val frame = new Frame(null)
      val drawingArea = new DrawingArea
      val hBox = new HBox(true, 1)
      val leftColorButton = new ColorButton
      val rightColorButton = new ColorButton
      val clearButton = new Button("Clear")

      var drawingSurface: Surface = null
      var lastColor = RGBA.BLACK

      window.setTitle("Drawing Area")
      window.setBorderWidth(8)
      window.connect(new Window.DeleteEvent {
        override def onDeleteEvent(source: Widget, event: Event): Boolean = {
          source.destroy()
          true
        }
      })

      frame.setShadowType(ShadowType.IN)
      leftColorButton.setRGBA(RGBA.BLACK)
      rightColorButton.setRGBA(RGBA.WHITE)
      clearButton.connect(new Button.Clicked {
        override def onClicked(source: Button): Unit = { clearSurface(drawingSurface); drawingArea.queueDraw() }
      })

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
          if (null != drawingSurface) {
            drawBrush(drawingSurface, lastColor, source, event.getX, event.getY)
          }
          null != drawingSurface
        }
      })
      drawingArea.connect(new Widget.ButtonPressEvent {
        def onButtonPressEvent(source: Widget, event: EventButton) = {
          val doDraw = drawBrush(drawingSurface, _: RGBA, source, event.getX, event.getY)
          if (null != drawingSurface) event.getButton match {
            case MouseButton.LEFT => { lastColor = leftColorButton.getRGBA; doDraw(lastColor) }
            case MouseButton.RIGHT => { lastColor = rightColorButton.getRGBA; doDraw(lastColor) }
            case _ => ()
          }
          null != drawingSurface
        }
      })
      drawingArea.addEvents(EventMask.BUTTON_PRESS)
      drawingArea.addEvents(EventMask.LEFT_BUTTON_MOTION)
      drawingArea.addEvents(EventMask.RIGHT_BUTTON_MOTION)

      hBox.add(leftColorButton)
      hBox.add(rightColorButton)
      hBox.add(clearButton)
      frame.add(drawingArea)
      vBox.packStart(frame, true, true, 0)
      vBox.packStart(hBox, false, false, 0)
      window.add(vBox)
      source.addWindow(window)

      window.showAll()
    }
  })

  gtkApp.connect(new Application.Activate {
    override def onActivate(source: Application): Unit = ()
  })

  System.exit(gtkApp.run(args))
}
