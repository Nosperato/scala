package scala.swing.test

import swing._
import event._
import java.util.Date
import java.awt.Color
import java.text.SimpleDateFormat
import javax.swing.{Icon, ImageIcon}

/**
 * Demonstrates how to use combo boxes and custom item renderers.
 * 
 * TODO: clean up layout
 */
object ComboBoxes extends SimpleGUIApplication {
  import ComboBox._
  val ui = new FlowPanel {
   	contents += new ComboBox(List(1,2,3,4))
        
    val patterns = List("dd MMMMM yyyy",
                        "dd.MM.yy",
                        "MM/dd/yy",
                        "yyyy.MM.dd G 'at' hh:mm:ss z",
                        "EEE, MMM d, ''yy",
                        "h:mm a",
                        "H:mm:ss:SSS",
                        "K:mm a,z",
                        "yyyy.MMMMM.dd GGG hh:mm aaa")
    val dateBox = new ComboBox(patterns) { makeEditable() }
    contents += dateBox
    val field = new TextField(20) { editable = false }
    contents += field
    
    reactions += {
      case SelectionChanged(`dateBox`) => reformat()
    }
    listenTo(dateBox.selection)
    
    def reformat() {
      try {
        val today = new Date
        val formatter = new SimpleDateFormat(dateBox.selection.item)
        val dateString = formatter.format(today)
        field.foreground = Color.black
        field.text = dateString
      } catch {
        case e: IllegalArgumentException =>
          field.foreground = Color.red
          field.text = "Error: " + e.getMessage
      }
    }
        
    val icons = List(new ImageIcon(resourceFromUserDirectory("swing/images/margarita1.jpg").toURL), 
                     new ImageIcon(resourceFromUserDirectory("swing/images/margarita2.jpg").toURL), 
                     new ImageIcon(resourceFromUserDirectory("swing/images/rose.jpg").toURL),
                     new ImageIcon(resourceFromUserDirectory("swing/images/banana.jpg").toURL))

    val iconBox = new ComboBox(icons) {
      renderer = new ListView.DefaultRenderer[Icon, Label](new Label) {
        def configure(list: ListView[_<:Icon], isSelected: Boolean, hasFocus: Boolean, icon: Icon, index: Int) {
  	      component.icon = icon
          component.xAlignment = Alignment.Center
          if(isSelected) {
            component.border = Border.Line(list.selectionBackground, 3)
          } else {
            component.border = Border.Empty(3)
          }
        }
      }
    }
    contents += iconBox
  }
    
  def top = new MainFrame {
    title = "ComboBoxes Demo"
   	contents = ui
  }
}

