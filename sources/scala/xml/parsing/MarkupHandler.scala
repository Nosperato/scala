package scala.xml.parsing;

import scala.xml.dtd._ ;

/** class that handles markup - provides callback methods to MarkupParser */
abstract class MarkupHandler {
    
  /** returns true is this markup handler is validing */
  val isValidating: Boolean = false;

  /** if true, does not remove surplus whitespace */
  val preserveWS: Boolean;

  var decls: List[scala.xml.dtd.Decl] = Nil;

  /** callback method invoked by MarkupParser after parsing an element.
   *
   *  @param pos      the position in the sourcefile
   *  @param pre      the prefix
   *  @param label    the local name
   *  @param attrs    the attributes (metadata)
   *  @param args     the children of this element
   */
  def elem(pos: int, pre: String, label: String, attrs: MetaData, scope:NamespaceBinding, args: NodeSeq): NodeSeq;

  /** callback method invoked by MarkupParser after parsing PI.
   */
  def procInstr(pos: Int, target: String, txt: String): NodeSeq;

  /** callback method invoked by MarkupParser after parsing comment.
   */
  def comment(pos: Int, comment: String ): NodeSeq;

  /** callback method invoked by MarkupParser after parsing entity ref.
   */
  def entityRef(pos: Int, n: String): NodeSeq;

  /** callback method invoked by MarkupParser after parsing text.
   */
  def text(pos: Int, txt:String): NodeSeq;

  def elemDecl(n: String, cmstr: String): Unit = {}
    
  def attListDecl(name: String, attList: List[AttrDecl]): Unit = {}

  def parameterEntityDecl(name: String, edef: EntityDef): Unit =
    decls = ParameterEntityDecl(name, edef) :: decls;

  def parsedEntityDecl(name: String, edef: EntityDef): Unit =
    decls = ParsedEntityDecl(name, edef) :: decls;

  def unparsedEntityDecl(name: String, extID: ExternalID, notat: String): Unit =
    {}

  def notationDecl(notat: String, extID: ExternalID): Unit =
    {}

  def peReference(name: String): Unit =
    decls = PEReference( name ) :: decls;

  /** report a syntax error */
  def reportSyntaxError(str: String): Unit;

}

