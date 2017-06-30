package io.github.tszypenbejl.gtkcustomdrawingexample

import org.gnome.gtk.{Application, Button, DrawingArea, Frame, Gtk, ShadowType, VBox, Widget, Window}
import org.gnome.gdk.{Event, EventButton, EventConfigure, EventMask, EventMotion, MouseButton}
import org.freedesktop.cairo.{Content, Context, Surface}


private object Workarounds {
  val surfaceReleaseMethod =
    Class.forName("org.freedesktop.cairo.Surface").getDeclaredMethod("release")
  surfaceReleaseMethod.setAccessible(true)

  def releaseSurface(surface: Surface) = {
    // Cannot just call surface.release because it is protected for some reason
    surfaceReleaseMethod invoke surface
  }


  val contextReleaseMethod =
    Class.forName("org.freedesktop.cairo.Context").getDeclaredMethod("release")
  contextReleaseMethod.setAccessible(true)

  def releaseContext(context: Context) = {
    // Cannot just call context.release because it is protected for some reason
    contextReleaseMethod invoke context
  }


  val gtkWidgetConnectMethod =
    Class.forName("org.gnome.gtk.GtkWidget").getDeclaredMethod("connect",
      classOf[org.gnome.gtk.Widget],
      Class.forName("org.gnome.gtk.GtkWidget$ConfigureEventSignal"),
      false.getClass)
  gtkWidgetConnectMethod.setAccessible(true)

  def connect(widget: Widget, event: Window.ConfigureEvent) = {
    // For some reason there is no easy way to connect configure event handler
    // to a Widget that does not happen to be a window
    gtkWidgetConnectMethod.invoke(
        null, widget, event, new java.lang.Boolean(false))
  }
}


object DrawingApp extends App {
  Gtk.init(null)

  def withContext(surface: Surface)(op: Context => Unit) = {
    val cr = new Context(surface)
    try {
      op(cr)
    } finally {
      Workarounds.releaseContext(cr)
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
      Workarounds releaseSurface oldSurface
    } catch {
      case e: Exception =>
        Workarounds releaseSurface newSurface
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
      Workarounds.connect(drawingArea, new Window.ConfigureEvent {
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
