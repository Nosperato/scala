/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Sean McDirmid
 */
// $Id: $

package scala.tools.nsc.doc;

import scala.tools.nsc._;
import java.io._;
import scala.tools.nsc.models._;
import scala.collection.immutable._;
import scala.xml._;
  
abstract class DocGenerator extends Models {
  import global._;
  import DocUtil._;  
  def outdir : String;
  def contentFrame = "contentFrame";
  def classesFrame = "classesFrame";
  def modulesFrame = "modulesFrame";
  def emptyMap = ListMap.Empty[Kind,TreeSet[HasTree]];
  
  
  override def acceptPrivate = false;

  
  abstract class Frame extends UrlContext {
    def path : String; // relative to outdir
    def relative : String = {
      assert(path != null);
      var idx = 0;
      var ct = "";
      while (idx != -1) {
        idx = path.indexOf('/', idx);
        //System.err.println(path + " idx=" + idx);
        ct = ct + (if (idx != -1) "../" else "");
        idx = idx + (if (idx == -1) 0 else 1);
      }
      ct;
    }
    
    def body : NodeSeq;
    def title : String;
    def save(nodes : NodeSeq) = {
      val path0 = outdir + "/" + path + ".html";
      //System.err.println("Writing to " + path0);
      val file = new File(path0);
      val parent = file.getParentFile();
      if (!parent.exists()) parent.mkdirs();
      val writer = new FileWriter(file);
      val str = dtype + "\n" + nodes.toString();
      writer.write(str, 0, str.length());
      writer.close();
    }
    def urlFor(sym : Symbol, target : String) : NodeSeq = {
      if (sym.sourceFile == null) Text(sym.fullNameString('.'));
      else aref(urlFor(sym), target, sym.nameString);
    }


    def urlFor0(sym : Symbol, orig : Symbol) : String = {
      (if (sym == NoSymbol) {
         "XXX";
       } else if (sym.owner.isPackageClass) sym.fullNameString('/');
       else urlFor0(sym.owner, orig) + "." + Utility.escape(sym.nameString)) + (sym match {
      case msym : ModuleSymbol => "$object";
      case csym : ClassSymbol =>  
        if (csym.isModuleClass) "$object";
        else "";
      case _ => 
        //System.err.println("XXX: class or object " + orig + " not found in " + sym);
        "XXXXX";
      
      })
    }
    def urlFor(sym : Symbol) : String = {
      sym match {
      case msym : ModuleSymbol => urlFor0(sym,sym) + ".html";
      case csym : ClassSymbol =>  urlFor0(sym,sym) + ".html";
      case _ => urlFor(sym.owner) + "#" + Utility.escape(sym.nameString);
      }
    }
    def hasBody = true;
    
    save(page(title, body, hasBody));
  }

  abstract class ListModuleFrame extends Frame {
    val path  = "modules";
    val title = "List of all packages";
    def modules : TreeMap[String,ModuleClassSymbol];
    def body : NodeSeq = {
      val x = div0("Scala 2") concat 
        aref("all-classes.html", classesFrame, "All objects and classes");
      val y = <P/><B>Packages</B>
       <TABLE class="list"><TR><TD style="white-space:nowrap;">
       { {
        for (val top <- modules.elements.toList) yield 
          {br(aref(urlFor(top._2), classesFrame, top._2.fullNameString('.')))};
       } }
       </TD></TR></TABLE>;
      x.concat(y);
    }
  }
  
  abstract class ListModuleContentFrame extends Frame {
    val path  = "root-content";
    val title = "All Packages";
    def modules : TreeMap[String,ModuleClassSymbol];
    def body : NodeSeq = {
      <SPAN><DIV class="page-title">
        Scala 2
        <BR/>API Specification
        </DIV>
        This document is the API specification for Scala 2.
        <P/><HR/>
        <TABLE cellpadding="3" class="member">
        <TR><TD colspan="2" class="title">
              Package Summary
            </TD>
        </TR>{ {
          for (val top <- modules.elements.toList) yield 
            <TR><TD class="signature">
            <CODE>{Text("package")}
            {(aref(top._2.fullNameString('/') + "$content.html", "_self", top._2.fullNameString('.')))}</CODE>
            </TD></TR>;
        } }
      </TABLE>
      </SPAN>;      
    }
  }
    
  abstract class ListClassFrame extends Frame {
    def classes : ListMap[Kind,TreeSet[HasTree]];
    
    def navLabel   : String;
    
     def body : NodeSeq = {
      val nav = <TABLE class="navigation"><TR><TD valign="top" class="navigation-links">
                {aref(path + "$content.html", contentFrame, navLabel)}
                </TD></TR></TABLE><P/>; 
                      
       
      val body = <SPAN> { { for (val kind <- KINDS; classes.contains(kind)) yield {
        val x = <B>{Text(pluralFor(kind))}</B>;
        
        val y = <TABLE class="list"><TR><TD style="white-space;nowrap;">
                { {
                  for (val mmbr <- classes(kind).toList) yield 
                    br(urlFor(mmbr.tree.symbol, contentFrame));  
                } }
                </TD></TR></TABLE>;
        val ret :NodeSeq = x.concat(y);
        ret;
      } } } </SPAN>;
      
      nav.concat(body);       
    }
  }
  
  abstract class ContentFrame0 extends Frame {
    def extendsFor(mmbr : HasTree) : NodeSeq = mmbr match {
    case mmbr : ImplMod =>
      if (!mmbr.treey.impl.parents.isEmpty) 
        <SPAN><dd><code>{Text(" extends ")}</code>
            {forType(mmbr.treey.impl.parents.head.tpe)}</dd>
        { { for (val parent <- mmbr.treey.impl.parents.tail) 
          yield <dd><code>{Text(" with ")}</code>
                    {forType(parent.tpe)}</dd>;
        } } </SPAN>;
      else NodeSeq.Empty; 
      case _ => NodeSeq.Empty;
    }
    def fullHeader(mmbr : HasTree) : NodeSeq = <SPAN>{ {
        if (!mmbr.isInstanceOf[ImplMod]) {
            <a name = {Utility.escape(mmbr.tree.symbol.nameString)}></a>;
        } else NodeSeq.Empty;
      } }<DL><DT> 
      { { for (val str <- stringsFor(mmbr.mods)) yield (Text(str + " ")) } }
      <CODE>{ Text(codeFor(mmbr.kind)) }</CODE>
      <EM>{ Text(mmbr.tree.symbol.nameString) }</EM>
      { typesFor(mmbr) }{  argsFor(mmbr)}{resultFor(mmbr) }
      </DT> { extendsFor(mmbr) }
      </DL> { fullComment(mmbr) } <HR/>
            { lists(mmbr) }  </SPAN>;
  
    def lists(mmbr : HasTree) = mmbr match {
    case cmod : ImplMod => <SPAN>{ listMembersShort(mmbr) }
                                 { listMembersFull (mmbr) }</SPAN>
    case _ => NodeSeq.Empty;  
    }
            
  
    def listMembersShort(mmbr : HasTree) : NodeSeq = if (mmbr.isInstanceOf[Composite]) {
      val map = organize(mmbr.asInstanceOf[Composite], emptyMap);
      <SPAN> { { 
        for (val kind <- KINDS; map.contains(kind)) yield {
          val x = <TABLE cellpadding="3" class="member">
          <TR><TD colspan="2" class="title">{Text(labelFor(kind))} Summary</TD></TR>
           { { 
            for (val mmbr <- map(kind).toList) yield shortHeader(mmbr);
           } }
          </TABLE>;
          br(x);
        }
      } } </SPAN>
    } else NodeSeq.Empty;
    
    def listMembersFull(mmbr : HasTree) : NodeSeq = if (mmbr.isInstanceOf[Composite]) {
      val map = organize(mmbr.asInstanceOf[Composite], emptyMap);
      val mmbrx = mmbr;
      val pathx = path;
      for (val kind0 <- OBJECT :: CLASS :: Nil; map.contains(kind0)) for (val mmbr <- map(kind0)) {
        new ContentFrame {
          def clazz = mmbr.asInstanceOf[ImplMod];
          def kind = kind0;
          def title = labelFor(kind0) + " " + mmbr.tree.symbol.nameString + " in " + codeFor(mmbrx.kind) + " " + mmbr.tree.symbol.owner.fullNameString('.');
        }
      }
      <SPAN> { {
        for (val kind <- KINDS; map.contains(kind) && kind != OBJECT && kind != CLASS) yield {
          val header = <table cellpadding="3" class="member-detail">
            <tr><td class="member-title">{Text(labelFor(kind))} Detail</td></tr>
          </table>;
          val body = for (val mmbr <- map(kind).toList) yield <SPAN>{fullHeader(mmbr)}</SPAN>;
          header.concat(body);
        }        
      } } </SPAN>;
    } else NodeSeq.Empty;
    
    def shortHeader(mmbr : HasTree) : NodeSeq = {
      <TR>
        <TD valign="top" class="modifiers">
          { { for (val str <- stringsFor(mmbr.mods)) yield <CODE>{(Text(str + " "))}</CODE>; } }
        </TD>
        <TD class="signature">
          <CODE>{Text(codeFor(mmbr.kind))}</CODE>
          <EM>{urlFor(mmbr.tree.symbol, contentFrame)}</EM>
          { typesFor(mmbr) }
          {  argsFor(mmbr) }
          {resultFor(mmbr) }
          <BR>{shortComment(mmbr)}</BR>
        </TD>
      </TR>;
    }
    
    
    def fullComment(mmbr : HasTree) : NodeSeq = {
      if (comments.contains(mmbr.tree.symbol)) 
        comment(comments(mmbr.tree.symbol), false) else NodeSeq.Empty;  
    };
    def shortComment(mmbr : HasTree) : NodeSeq = {
      if (comments.contains(mmbr.tree.symbol)) 
        comment(comments(mmbr.tree.symbol), true) else NodeSeq.Empty;  
    };
    def ifT (cond : Boolean, nodes : NodeSeq) = if (cond) nodes else NodeSeq.Empty;
    def ifT (tree : Tree, nodes : NodeSeq, before : Boolean) = {
      if (tree != EmptyTree && 
        tree.tpe.symbol != definitions.AnyClass && 
          tree.tpe.symbol != definitions.AllClass) {
        if (before) nodes.concat(forTree(tree));
        else forTree(tree).concat(nodes);
      } else NodeSeq.Empty;
    }
    
    def forType(tpe : Type) : NodeSeq = {
      urlFor(tpe.symbol, contentFrame);
    }
    
    def forTree(tree : Tree) : NodeSeq = tree match {
      case vdef : ValDef => 
        Text(vdef.symbol.name.toString()).concat(Text(" : ")).concat(forTree(vdef.tpt));
      case sel : Select => forTree(sel.qualifier).concat(Text(sel.symbol.nameString));
      case tree : AbsTypeDef => 
        ifT(tree.lo, Text(" <: "), false).
          concat(Text(tree.symbol.nameString)).concat(ifT(tree.hi, Text(" <: "), true));
      case tpt : TypeTree => urlFor(tpt.tpe.symbol, contentFrame);
      case id  : Ident  => Text("YY: " + id.symbol.nameString);
      case EmptyTree => NodeSeq.Empty;    
      case _ => Text("XX=" + tree.getClass() + " " + tree.toString());
    }
    def forTrees(trees : List[Tree]) : NodeSeq = {
      if (trees.isEmpty) NodeSeq.Empty;
      else {
        val head = forTree(trees.head);
        head.concat(if (trees.tail.isEmpty) NodeSeq.Empty 
                    else Text(", ")).concat(forTrees(trees.tail));
      }
    }
    
    def surround(open : String, close : String, node : NodeSeq) : NodeSeq = 
      Text(open).concat(node).concat(Text(close));
    
    def typesFor(ht : HasTree) : NodeSeq = {
      val tparams = ht.tree match {
        case cdef : ClassDef => cdef.tparams;
        case ddef : DefDef   => ddef.tparams;
        case adef : AliasTypeDef => adef.tparams;
        case _ => Nil;
      }
      if (tparams.isEmpty) Text("");
      else surround("[", "]", forTrees(tparams));
    }
    def  argsFor(ht : HasTree) : NodeSeq = ht.tree match {
      case ddef : DefDef =>
        if (!ddef.vparamss.isEmpty && 
            (!ddef.vparamss.tail.isEmpty  || !ddef.vparamss.head.isEmpty)) {
          val nodes = for (val vparams <- ddef.vparamss) 
            yield surround("(", ")", forTrees(vparams));
          nodes.flatMap(x => x.asList);
        } else NodeSeq.Empty;
      case _ => NodeSeq.Empty;
    }
    def resultFor(ht : HasTree) : NodeSeq = ht.tree match {
      case vdef : ValOrDefDef =>
        if (!vdef.symbol.nameString.equals("this"))
          Text(" : ").concat(forTree(vdef.tpt));
        else NodeSeq.Empty;
      case _ => NodeSeq.Empty;
    } 
  }
  
  abstract class ListClassContentFrame extends ContentFrame0 {
    def classes : ListMap[Kind,TreeSet[HasTree]];
    def module : ModuleClassSymbol;
    
    def path  = module.fullNameString('/') + "$content";
    def title = "All Classes and Objects in " + module.fullNameString('.');
    
    def body : NodeSeq = {
      <SPAN><DIV class="page-title">
        Scala 2
        <BR/>API Specification
        </DIV>
        This document is the API specification for Scala 2.
        <P/>
        { {
          for (val kind <- KINDS; classes.contains(kind)) yield {
            <SPAN><HR/><TABLE cellpadding="3" class="member">
            <TR><TD colspan="2" class="title">
                {labelFor(kind)} Summary
             </TD></TR>{ {
              for (val mmbr <- classes(kind).toList) yield shortHeader(mmbr);
              } }
            </TABLE></SPAN>
          }
        } }
      </SPAN>;      
    }
  }

  abstract class ContentFrame extends ContentFrame0 {
    def clazz : ImplMod;
    def kind  : Kind;
    def body : NodeSeq = <SPAN>{navigation}{header0}{fullHeader(clazz)}</SPAN>;
    
    final def path = urlFor0(clazz.tree.symbol,clazz.tree.symbol);

    // <TD class="navigation-enabled">{aref("help.html"     , "_self", "Help"    )}</TD>
    def navigation : NodeSeq = 
    <TABLE class="navigation">
      <TR>
        <TD valign="top" class="navigation-links">
          <TABLE><TR>
          <TD class="navigation-enabled">{aref("root-page.html", "_self", "Overview")}</TD>
          <TD class="navigation-enabled">{aref("index.html"    , "_self", "Index"   )}</TD>
          </TR></TABLE>
        </TD>
        <TD align="right" valign="top" style="white-space:nowrap;" rowspan="2">
        {div0("Scala 2")}
        </TD>
      </TR>
      <TR><TD></TD></TR>
    </TABLE>;
        
    def header0 : NodeSeq = <SPAN>
      <HR/> in {aref(urlFor(clazz.tree.symbol.owner), "_self", clazz.tree.symbol.owner.fullNameString('.'))}
      <DIV class="entity">
        {Text(codeFor(kind))} 
        <SPAN class="entity">{Text(clazz.tree.symbol.nameString)}</SPAN>
      </DIV><HR/>
    </SPAN>;    
        
        
    
    
  }

  def process(units : Iterator[CompilationUnit]) : Unit = {
    var members = emptyMap;
    
    var topLevel = ListMap.Empty[ModuleClassSymbol,ListMap[Kind,TreeSet[HasTree]]];
    for (val unit <- units) {
      val sourceMod = new SourceMod(unit);
      for (val mmbr <- sourceMod.members) mmbr.tree match {
      case cdef:  ImplDef => 
        assert(cdef.symbol.owner != NoSymbol);
        val sym = cdef.symbol.owner.asInstanceOf[ModuleClassSymbol];
        if (!topLevel.contains(sym)) topLevel = topLevel.update(sym, emptyMap);
        topLevel = topLevel.update(sym, organize0(mmbr, topLevel(sym))); 
      case _ => throw new Error("unknown: " + mmbr.tree + " " + mmbr.tree.getClass());
      }
    }

    val modules0 = {
      var modules0 = new TreeMap[String,ModuleClassSymbol];
      for (val top <- topLevel.elements) 
        modules0 = modules0.insert(top._1.fullNameString, top._1);
      modules0;
    };
    
    new ListModuleFrame {
      def modules = modules0;
    };
    new ListModuleContentFrame {
      def modules = modules0;
    };
    
    new ListClassFrame {
      def classes = {
        var allClasses = emptyMap;
        for (val top <- topLevel.elements) 
          allClasses = merge(allClasses, top._2);
        allClasses;
      }
      def title = "List of all classes and objects";
      def path = "all-classes";
      def navLabel = "root-page";
    };
    // class from for each module.
    for (val top <- topLevel.elements) {
      val module = top._1;
      val members = top._2;
      
      new ListClassFrame {
        def title = "List of classes and objects in package " + module.fullNameString('.');
        def classes = top._2;
        def path = module.fullNameString('/');
        def navLabel = module.fullNameString('.');
      };
      val module0 = module;
      new ListClassContentFrame {
        def classes = top._2;
        def module = module0;
      };
      
      // do root frame for each class and object
      for (val kind <- members.elements) for (val mmbr <- kind._2.toList) {
        val kind0 = kind._1;
        new ContentFrame {
          def title = labelFor(kind0) + " " + mmbr.tree.symbol.nameString + " in package " + mmbr.tree.symbol.owner.fullNameString('.');
          def clazz = mmbr.asInstanceOf[ImplMod];
          def kind = kind0;
        }
      }

    }
    
    new Frame {
      def title = "Scala Library Documentation";
      def body = index;
      def path = "index";
      override def hasBody = false;
    };
    for (val base <- "style.css" :: "script.js" :: Nil) {
      val input = getClass().getClassLoader().getResourceAsStream("scala/tools/nsc/doc/" + base);
      if (input != null) {
        val file  = new File(outdir + "/" + base);
        val output = new FileOutputStream(file);
        var break = false;
        val bytes = new Array[byte](1024);
        while (!break) {
          val read = input.read(bytes);
          if (read == -1) {
            break = true;
          } else {
            output.write(bytes, 0, read);
          }
        }
        input.close();
        output.close();
      }
    }
  }
  
  
  
  
    
  
  

  def organize(c : Composite, map0 : ListMap[Kind,TreeSet[HasTree]]) = {
    var map = map0;
    //System.err.println("MEMBERS: " + c.members.toList);
    for (val mmbr <- c.members.toList) map = organize0(mmbr, map);
    map;
  }
  def organize0(mmbr : HasTree, map0 : ListMap[Kind,TreeSet[HasTree]]) = {
    var map = map0;
    if (!map.contains(mmbr.kind))
      map = map.update(mmbr.kind, new TreeSet[HasTree]);
    val sz = map(mmbr.kind).size;
    map = map.update(mmbr.kind, map(mmbr.kind) + mmbr);
    if (map(mmbr.kind).size == sz)
      System.err.println(""+mmbr + " not added");
    map;    
  }
  
  
  def parse(str : String) : NodeSeq = {
    new SpecialNode {
      def label = "#PCDATA";
      def toString(sb:StringBuffer): StringBuffer = {
        sb.append(str.trim());
        sb;
      }

    }
    /*
    import java.io.StringReader;
    import org.xml.sax.InputSource;
    val isrc1       = new InputSource(new StringReader(str));
    val parsedxml1  = XML.load(isrc1);
    if (parsedxml1 == null) Text("BAD_COMMENT???");
    else parsedxml1;
    */
  };
  
  def comment(comment : String, isShort : Boolean) : NodeSeq = {
    var ret : List[Node] = Nil;
    assert(comment != null);
    // strip out any stars.
    var comment0 = comment.trim();
    assert(comment0.startsWith(JDOC_START));
    comment0 = comment0.substring(JDOC_START.length());
    assert(comment0.endsWith(JDOC_END));
    comment0 = comment0.substring(0, comment0.length() - JDOC_END.length());
    var idx = 0;
    while (idx != -1) {
      idx = comment0.indexOf('*', idx);
      if (idx != -1) 
        comment0 = comment0.substring(0, idx) +
          comment0.substring(idx + 1, comment0.length());
    }
    val tokenizer = new java.util.StringTokenizer(comment0, "@");
    val body = tokenizer.nextToken();
    var attributes : List[Tuple2[String,String]] = Nil;
    if (!isShort) while (tokenizer.hasMoreElements()) {
      val attr = tokenizer.nextToken();
      val div = attr.indexOf(' ');
      val tuple = if (div == -1) new Tuple2(attr,"");
      else new Tuple2(attr.substring(0, div), attr.substring(div + 1, attr.length()));
      attributes = attributes ::: (tuple :: Nil);
    }
    if (isShort) <SPAN>{parse(body)}</SPAN>;    
    else <SPAN><DL><DD>{parse(body)}</DD></DL><DL>
    { { 
      for (val attr <- attributes) yield
        <DT style="margin-top:10px;"><B>{Text(attr._1 + ":")}</B></DT>
        <DD>{(parse(attr._2))}</DD>;
    } } </DL></SPAN>;
  };
  

  val index = {
    <frameset cols="25%, 75%">
    <frameset rows="50%, 50%">
      <frame src="modules.html" name={modulesFrame}></frame>
      <frame src="all-classes.html" name={classesFrame}></frame>
    </frameset>
    <frame src="root-content.html" name={contentFrame}></frame>
  </frameset>;
  }
  
  val root = <b></b>;
  
  
  

  private val JDOC_START = "/**";
  private val JDOC_END   = "*/";
}
