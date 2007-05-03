/* NSC -- new Scala compiler
 * Copyright 2007-2008 LAMP/EPFL
 * @author  Sean McDirmid
 */
// $Id$

package scala.tools.nsc.doc

import scala.xml._

/** This class has functionality to format source code models as XML blocks.
 *
 *  @author  Sean McDirmid, Stephane Micheloud
 */
trait ModelToXML extends ModelExtractor {
  import global._
  import DocUtil._
  // decode entity into XML.
  type Frame
  
  protected def urlFor(sym: Symbol)(implicit frame: Frame): String
  protected def anchor(sym: Symbol)(implicit frame: Frame): NodeSeq

  def aref(href: String, label: String)(implicit frame: Frame): NodeSeq

  def link(entity: Symbol)(implicit frame: Frame): NodeSeq = {
    val url = urlFor(entity)
    // nothing to do but be verbose.
    if (url == null)
      Text(entity.owner.fullNameString('.') + '.' + entity.nameString)
    else
      aref(url, entity.nameString)
  }

  def link(tpe: Type)(implicit frame: Frame): NodeSeq = {
    if (!tpe.typeArgs.isEmpty) {
      if (definitions.isFunctionType(tpe)) {
        val (args,r) = tpe.typeArgs.splitAt(tpe.typeArgs.length - 1);
        args.mkXML("(", ", ", ")")(link) ++ Text(" => ") ++ link(r.head);
      } else if (tpe.symbol == definitions.RepeatedParamClass) {
        assert(tpe.typeArgs.length == 1);
        link(tpe.typeArgs(0)) ++ Text("*");
      } else if (tpe.symbol == definitions.ByNameParamClass) {
        assert(tpe.typeArgs.length == 1);
        Text("=> ") ++ link(tpe.typeArgs(0));
      } else if (tpe.symbol.name.toString.startsWith("Tuple") &&
                 tpe.symbol.owner.name == nme.scala_.toTypeName) {
        tpe.typeArgs.mkXML("(", ", ", ")")(link);
      } else link(decode(tpe.symbol)) ++ tpe.typeArgs.surround("[", "]")(link);
    } else tpe match {
      case PolyType(tparams,result) => 
        link(result) ++ tparams.surround("[", "]")(link);
      case RefinedType(parents,_) => 
        val parents1 =
          if ((parents.length > 1) &&
              (parents.head.symbol eq definitions.ObjectClass)) parents.tail;
          else parents;
       parents1.mkXML(Text(""), <code> with </code>, Text(""))(link); 
     case _ =>
       link(decode(tpe.symbol))
    }
  }

  private def printIf[T](what: Option[T], before: String, after: String)(f: T => NodeSeq): NodeSeq =
    if (what.isEmpty) Text("")
    else Text(before) ++ f(what.get) ++ Text(after)

  def bodyFor(entity: Entity)(implicit frame: Frame): NodeSeq = {
    var seq = {entity.typeParams.surround("[", "]")(e => {
      Text(e.variance) ++ <em>{e.name}</em> ++
        {printIf(e.hi, " <: ", "")(link)} ++
        {printIf(e.lo, " >: ", "")(link)}
    })} ++ printIf(entity.hi, " <: ", "")(link) ++
           printIf(entity.lo, " >: ", "")(link);
    {entity.params.foreach(xs => {
      seq = seq ++ xs.mkXML("(", ", ", ")")(arg =>
        {
          val str = arg.flagsString.trim
          if (str.length == 0) NodeSeq.Empty
          else <code>{Text(str)} </code>
        } ++
        <em>{arg.name}</em> ++
        Text(" : ") ++ link(arg.resultType.get)
      );
      seq
    })};
    seq ++ {printIf(entity.resultType, " : ", "")(tpe => link(tpe))}
  }

  def extendsFor(entity: Entity)(implicit frame: Frame): NodeSeq = {
    if (entity.parents.isEmpty) NodeSeq.Empty
    else <code> extends </code>++
      entity.parents.mkXML(Text(""), <code> with </code>, Text(""))(link);
  }

  def parse(str: String): NodeSeq = {
    new SpecialNode {
      def label = "#PCDATA"
      def toString(sb: StringBuilder): StringBuilder = {
        sb.append(str.trim)
        sb
      }
    }
  }

  def longHeader(entity: Entity)(implicit from: Frame): NodeSeq = Group({
    anchor(entity.sym) ++ <dl>
      <dt>
        {attrsFor(entity)}
        <code>{Text(entity.flagsString)}</code>
        <code>{Text(entity.kind)}</code>
        <em>{entity.sym.nameString}</em>{bodyFor(entity)}
      </dt>
      <dd>{extendsFor(entity)}</dd>
    </dl>;
  } ++ {
    val cmnt = entity.decodeComment
    if (cmnt.isEmpty) NodeSeq.Empty
    else longComment(cmnt.get)
  } ++ (entity match {
      case entity: ClassOrObject => classBody(entity)
      case _ => NodeSeq.Empty
  }) ++ {
    val overridden = entity.overridden
    if (overridden.isEmpty)
      NodeSeq.Empty
    else {
      <dl>
        <dt style="margin:10px 0 0 20px;">
          <b>Overrides</b>
        </dt>
        <dd>
        { overridden.mkXML("",", ", "")(sym => link(decode(sym.owner)) ++ Text(".") ++ link(sym))
        }
        </dd>
      </dl>
    }
  } ++ <hr/>);

  def longComment(cmnt: Comment): NodeSeq = {
    val attrs = <dl>{
      var seq: NodeSeq = NodeSeq.Empty
      cmnt.decodeAttributes.foreach{
      case (tag, xs) => 
        seq = seq ++ <dt style="margin:10px 0 0 20px;">
        <b>{decodeTag(tag)}</b></dt> ++ {xs.flatMap{
        case (option,body) => <dd>{
          if (option == null) NodeSeq.Empty;
          else decodeOption(tag, option);
        }{parse(body)}</dd>
        }}
      };
      seq
    }</dl>;
    <xml:group>
      <dl><dd>{parse(cmnt.body)}</dd></dl>
      {attrs}
    </xml:group>
  }
  
  def classBody(entity: ClassOrObject)(implicit from: Frame): NodeSeq =
    <xml:group>
      {categories.mkXML("","\n","")(c => shortList(entity, c)) : NodeSeq}
      {categories.mkXML("","\n","")(c =>  longList(entity, c)) : NodeSeq}
    </xml:group>;

  def longList(entity: ClassOrObject, category: Category)(implicit from: Frame): NodeSeq = {
    val xs = entity.members(category)
    if (!xs.elements.hasNext)
      NodeSeq.Empty
    else Group(
        <table cellpadding="3" class="member-detail" summary="">
          <tr><td class="title">{Text(category.label)} Details</td></tr>
        </table>
        <div>{xs.mkXML("","\n","")(m => longHeader(m))}</div>)
  }
  
  def shortList(entity: ClassOrObject, category: Category)(implicit from: Frame): NodeSeq = {
    val xs = entity.members(category)
    var seq: NodeSeq = NodeSeq.Empty
    if (xs.elements.hasNext) {
      // alphabetic
      val set = new scala.collection.jcl.TreeSet[entity.Member]()(mA => new Ordered[entity.Member] {
        def compare(mB: entity.Member): Int = {
          if (mA eq mB) return 0;
          val diff = mA.name compare mB.name;
          if (diff != 0) return diff;
          val diff0 = mA.hashCode - mB.hashCode;
          assert(diff0 != 0);
          return diff0
        }
      });
      set addAll xs;
      seq = seq ++ <table cellpadding="3" class="member" summary="">
      <tr><td colspan="2" class="title">{Text(category.label + " Summary")}</td></tr>
      {set.mkXML("","\n","")(mmbr => shortHeader(mmbr))}
      </table>
    }
    // list inherited members...if any.
    for ((tpe,members) <- entity.inherited) {
      val members0 = members.filter(m => category.f(m.sym));
      if (!members0.isEmpty) seq = seq ++ <table cellpadding="3" class="inherited" summary="">
        <tr><td colspan="2" class="title">
          {Text(category.plural + " inherited from ") ++ link(tpe)}
        </td></tr>
        <tr><td colspan="2" class="signature">
          {members0.mkXML((""), (", "), (""))(m => {
            link(decode(m.sym)) ++
              (if (m.sym.hasFlag(symtab.Flags.ABSTRACT) || m.sym.hasFlag(symtab.Flags.DEFERRED)) {
                Text(" (abstract)");
              } else NodeSeq.Empty);
           })}
        </td></tr>
      </table>
    }
    seq;
  }

  protected def decodeOption(tag: String, string: String): NodeSeq =
    <code>{Text(string + " - ")}</code>;

  protected def decodeTag(tag: String): String = 
    "" + Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
  
  def shortHeader(entity: Entity)(implicit from: Frame): NodeSeq =
    <tr>
      <td valign="top" class="modifiers"> 
        <code>{Text(entity.flagsString)} {Text(entity.kind)}</code>
      </td>
      <td class="signature">
        <em>{link(decode(entity.sym))}</em>
        {bodyFor(entity) ++ extendsFor(entity)}
        {
          entity.resultType match {
            case Some(PolyType(_, ConstantType(v))) => Text(" = " + v.escapedStringValue)
            case _ => NodeSeq.Empty
          }
        }
        {
          val cmnt = entity.decodeComment
          if (cmnt.isEmpty) NodeSeq.Empty
          else <div>{parse(cmnt.get.body)}</div>;
        }
      </td>
    </tr>

  def attrsFor(entity: Entity)(implicit from: Frame): NodeSeq = {
    def attrFor(attr: AnnotationInfo[Constant]): Node = {
      val buf = new StringBuilder
      val AnnotationInfo(tpe, args, nvPairs) = attr
      val name = link(decode(tpe.symbol))
      if (!args.isEmpty)
        buf.append(args.map(.escapedStringValue).mkString("(", ",", ")"))
      if (!nvPairs.isEmpty)
        for (((name, value), index) <- nvPairs.zipWithIndex) {
          if (index > 0)
            buf.append(", ")
          buf.append(name).append(" = ").append(value)
        }
      Group(name ++ Text(buf.toString))
    }
    if (entity.sym.hasFlag(symtab.Flags.CASE)) NodeSeq.Empty;
    else {
      val sep = Text("@")
      for (attr <- entity.attributes)
        yield Group({(sep ++ attrFor(attr) ++ <br/>)})
    }
  }
}
