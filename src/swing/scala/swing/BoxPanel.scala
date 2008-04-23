package scala.swing

/**
 * @see javax.swing.BoxLayout
 */
class BoxPanel(orientation: Orientation.Value) extends Panel with SequentialContainer.Wrapper {
  override lazy val peer = {
    val p = new javax.swing.JPanel
    val l = new javax.swing.BoxLayout(p, orientation.id)
    p.setLayout(l)
    p
  }
}
