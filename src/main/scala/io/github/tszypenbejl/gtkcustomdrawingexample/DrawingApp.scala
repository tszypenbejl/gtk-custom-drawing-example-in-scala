package io.github.tszypenbejl.gtkcustomdrawingexample

import org.gnome.gtk.{Application, Button, ColorButton, DrawingArea, Frame, Gtk, HBox, ShadowType, VBox, Widget, Window}
import org.gnome.gdk.{Event, EventButton, EventConfigure, EventMask, EventMotion, MouseButton, RGBA}
import org.freedesktop.cairo.{Antialias, Content, Context, Surface}


private object Workarounds {
  private val contextReleaseMethod =
    Class.forName("org.freedesktop.cairo.Context").getDeclaredMethod("release")
  private val gtkWidgetConnectMethod =
    Class.forName("org.gnome.gtk.GtkWidget").getDeclaredMethod("connect",
      classOf[org.gnome.gtk.Widget],
      Class.forName("org.gnome.gtk.GtkWidget$ConfigureEventSignal"),
      false.getClass)

  contextReleaseMethod.setAccessible(true)
  gtkWidgetConnectMethod.setAccessible(true)

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


private object Decorations {
  import Workarounds._

  implicit class WidgetDecorator(widget: Widget) {
    import Widget._

    def connectDraw(callback: (Widget, Context) => Boolean) = widget.connect(
      new Draw {
        override def onDraw(source: Widget, cr: Context): Boolean = callback(source, cr)
      })

    def connectButtonPressEvent(callback: (Widget, EventButton) => Boolean) = widget.connect(
      new ButtonPressEvent {
        override def onButtonPressEvent(source: Widget, event: EventButton): Boolean = callback(source, event)
      })

    def connectMotionNotifyEvent(callback: (Widget, EventMotion) => Boolean) = widget.connect(
      new MotionNotifyEvent {
        override def onMotionNotifyEvent(source: Widget, event: EventMotion): Boolean = callback(source, event)
      })

    def connectConfigureEvent(callback: (Widget, EventConfigure) => Boolean) = widget.connect(
      new Window.ConfigureEvent {
        override def onConfigureEvent(source: Widget, event: EventConfigure): Boolean = callback(source, event)
      })
  }


  implicit class ButtonDecorator(button: Button) {
    import Button._
    def connectClicked(callback: (Button) => Unit) = button.connect(new Clicked {
      override def onClicked(source: Button): Unit = callback(source)
    })
  }


  implicit class WindowDecorator(window: Window) {
    import Window._
    def connectDeleteEvent(callback: (Widget, Event) => Boolean) = window.connect(new DeleteEvent {
      override def onDeleteEvent(source: Widget, event: Event): Boolean = callback(source, event)
    })
  }


  implicit class ApplicationDecorator(application: Application) {
    import Application._

    def connectStartup(callback: (Application) => Unit) = application.connect(
      new Startup {
        override def onStartup(source: Application): Unit = callback(source)
      })

    def connectActivate(callback: (Application) => Unit) = application.connect(
      new Activate {
        override def onActivate(source: Application): Unit = callback(source)
      }
    )
  }
}


object DrawingApp extends App {
  import Workarounds._
  import Decorations._

  Gtk.init(null)


  private class Picture {
    private val brushSize = 6;
    private val halfBrushSize = brushSize / 2;

    private var mySurface: Surface = null
    private var previousPoint: (Int, Int) = null

    private def withContext(s: Surface)(op: Context => Unit) = {
      val cr = new Context(s)
      try {
        op(cr)
      } finally {
        cr.release()
      }
    }

    private def clearSurface(s: Surface) = {
      withContext(s) { cr =>
        cr.setSource(1, 1, 1)
        cr.paint()
      }
    }

    private def initialize(widgetCr: Context, width: Int, height: Int) = {
      mySurface = widgetCr.getTarget.createSimilar(Content.COLOR, width, height)
      clearSurface(mySurface)
    }

    def initialized: Boolean = mySurface ne null

    def drawTo(widgetCr: Context, width: Int, height: Int): Unit = {
      if (mySurface eq null) initialize(widgetCr, width, height)
      widgetCr.setSource(mySurface, 0, 0)
      widgetCr.paint()
    }

    def clear(): Unit = { if (initialized) clearSurface(mySurface) }

    def drawDot(color: RGBA, x: Int, y: Int): (Int, Int, Int, Int) = {
      if (initialized) {
        withContext(mySurface) { cr =>
          cr.setAntialias(Antialias.NONE)
          cr.setSource(color)
          cr.arc(x.toInt, y.toInt, halfBrushSize, 0, 2 * Math.PI)
          cr.fill()
        }
        previousPoint = (x.toInt, y.toInt)
        (x - halfBrushSize, y - halfBrushSize, brushSize, brushSize)
      } else {
        (0, 0, 0, 0)
      }
    }

    def drawLine(color: RGBA, x: Int, y: Int): (Int, Int, Int, Int) = {
      if (initialized && (previousPoint ne null)) {
        withContext(mySurface) { cr =>
          cr.setAntialias(Antialias.NONE)
          cr.setSource(color)
          cr.arc(x.toInt, y.toInt, halfBrushSize, 0, 2 * Math.PI)
          cr.fill()
          cr.setLineWidth(brushSize)
          cr.moveTo(x.toInt, y.toInt)
          cr.lineTo(previousPoint._1, previousPoint._2)
          cr.stroke()
        }
        val leftX :: rightX :: _ = List(x, previousPoint._1).sorted
        val topY :: bottomY :: _ = List(y, previousPoint._2).sorted
        val deltaX = rightX - leftX
        val deltaY = bottomY - topY
        previousPoint = (x.toInt, y.toInt)
        (leftX - halfBrushSize, topY - halfBrushSize, deltaX + brushSize, deltaY + brushSize)
      } else {
        (0, 0, 0, 0)
      }
    }

    def resize(width: Int, height: Int): Unit = {
      if (initialized) {
        val newSurface = mySurface.createSimilar(Content.COLOR, width, height)
        try {
          clearSurface(newSurface)
          withContext(newSurface) { drawTo(_, width, height) }
          mySurface.finish()
          mySurface = newSurface
        } finally {
          if (newSurface ne mySurface) newSurface.finish()
        }
      }
    }
  }


  private val gtkApp = new Application("io.github.tszypenbejl.gtkcustomdrawingexample")

  gtkApp.connectActivate((sourceApp) => {
    val window = new Window
    val vBox = new VBox(false, 3)
    val frame = new Frame(null)
    val drawingArea = new DrawingArea
    val hBox = new HBox(true, 1)
    val leftColorButton = new ColorButton
    val rightColorButton = new ColorButton
    val clearButton = new Button("Clear")

    val picture = new Picture

    var lastColor = RGBA.BLACK

    window.setTitle("Drawing Area")
    window.setBorderWidth(8)
    window.connectDeleteEvent((source, _) => { source.destroy(); true })

    frame.setShadowType(ShadowType.IN)
    leftColorButton.setRGBA(RGBA.BLACK)
    rightColorButton.setRGBA(RGBA.WHITE)
    clearButton.connectClicked(_ => { picture.clear(); drawingArea.queueDraw() })

    drawingArea.setSizeRequest(300, 200)
    drawingArea.connectDraw((source, cr) => {
      picture.drawTo(cr, source.getAllocatedWidth, source.getAllocatedHeight)
      cr.release()
      // Strangely, I really need the line above. Without the release() my shared memory usage reported by 'free'
      // (the linux/unix command) keeps growing infinitely with every widget redraw.
      // Apparently there is some bug in reference counting for Cairo context that this extra release() fixes.
      // I am getting a SIGABRT on program exit because of this release, but that only happens if I explicitly call
      // System.gc() during program lifetime.
      false
    })
    drawingArea.connectConfigureEvent((source, _) => {
      picture.resize(source.getAllocatedWidth, source.getAllocatedHeight)
      true
    })
    drawingArea.connectMotionNotifyEvent((source, event) => {
      val updatedRect = picture.drawLine(lastColor, event.getX.toInt, event.getY.toInt)
      (source.queueDrawArea _).tupled(updatedRect)
      true
    })
    drawingArea.connectButtonPressEvent((source, event) => {
      val doDraw = picture.drawDot(_: RGBA, event.getX.toInt, event.getY.toInt)
      val doQueueDraw = (source.queueDrawArea _).tupled
      event.getButton match {
        case MouseButton.LEFT => { lastColor = leftColorButton.getRGBA; doQueueDraw(doDraw(lastColor)) }
        case MouseButton.RIGHT => { lastColor = rightColorButton.getRGBA; doQueueDraw(doDraw(lastColor)) }
        case _ => ()
      }
      true
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
    sourceApp.addWindow(window)

    window.showAll()
  })

  System.exit(gtkApp.run(args))
}
