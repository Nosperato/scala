package scala.tools.nsc.util;

import symtab.Names;
import symtab.classfile.{PickleBuffer, PickleFormat};
import symtab.Flags;
import java.io._;
import java.lang.{Integer, Float, Double}

object ShowPickled extends Names {

  import PickleFormat._;

  def tag2string(tag: int): String = tag match {
    case TERMname => "TERMname";
    case TYPEname => "TYPEname";
    case NONEsym => "NONEsym";
    case TYPEsym => "TYPEsym";
    case ALIASsym => "ALIASsym";
    case CLASSsym => "CLASSsym";
    case MODULEsym => "MODULEsym";
    case VALsym => "VALsym";
    case EXTref => "EXTref";
    case EXTMODCLASSref => "EXTMODCLASSref";
    case NOtpe => "NOtpe";
    case NOPREFIXtpe => "NOPREFIXtpe";
    case THIStpe => "THIStpe";
    case SINGLEtpe => "SINGLEtpe";
    case CONSTANTtpe => "CONSTANTtpe";
    case TYPEREFtpe => "TYPEREFtpe";
    case TYPEBOUNDStpe => "TYPEBOUNDStpe";
    case REFINEDtpe => "REFINEDtpe";
    case CLASSINFOtpe => "CLASSINFOtpe";
    case CLASSINFOtpe => "CLASSINFOtpe";
    case METHODtpe => "METHODtpe";
    case POLYtpe => "POLYtpe";
    case LITERALunit => "LITERALunit";
    case LITERALboolean => "LITERALboolean";
    case LITERALbyte => "LITERALbyte";
    case LITERALshort => "LITERALshort";
    case LITERALchar => "LITERALchar";
    case LITERALint => "LITERALint";
    case LITERALlong => "LITERALlong";
    case LITERALfloat => "LITERALfloat";
    case LITERALdouble => "LITERALdouble";
    case LITERALstring => "LITERALstring";
    case LITERALnull => "LITERALnull";
    case LITERALzero => "LITERALzero";
    case _ => "***BAD TAG***(" + tag + ")";
  }

  def printFile(buf: PickleBuffer, out: PrintStream): unit = {
    out.println("Version "+buf.readNat()+"."+buf.readNat());
    val index = buf.createIndex;

    def printNameRef() = {
      val x = buf.readNat();
      val savedIndex = buf.readIndex;
      buf.readIndex = index(x);
      val tag = buf.readByte();
      val len = buf.readNat();
      out.print(" " + x + "(" + newTermName(buf.bytes, buf.readIndex, len) + ")");
      buf.readIndex = savedIndex
    }

    def printNat() = out.print(" " + buf.readNat());
    def printSymbolRef() = printNat();
    def printTypeRef() = printNat();
    def printConstantRef() = printNat();

    def printSymInfo() = {
      printNameRef();
      printSymbolRef();
      val flags = buf.readNat();
      out.print(" " + Integer.toHexString(flags) + "[" + Flags.flagsToString(flags) + "] ");
      printTypeRef();
    }
    
    def printEntry(i: int): unit = {
      buf.readIndex = index(i);
      out.print(i + "," + buf.readIndex + ": ");
      val tag = buf.readByte();
      out.print(tag2string(tag));
      val len = buf.readNat();
      val end = len + buf.readIndex;
      out.print(" " + len + ":");
      tag match {
        case TERMname => 
          out.print(" ");
          out.print(newTermName(buf.bytes, buf.readIndex, len).toString());
          buf.readIndex = end;
	case TYPEname => 
          out.print(" ");
          out.print(newTypeName(buf.bytes, buf.readIndex, len));
          buf.readIndex = end;
	case TYPEsym | ALIASsym | CLASSsym | MODULEsym | VALsym =>
          printSymInfo();
          if (tag == CLASSsym && (buf.readIndex < end)) printTypeRef(); 
        case EXTref | EXTMODCLASSref =>
          printNameRef();
          if (buf.readIndex < end) { printSymbolRef() }
	case THIStpe =>
	  printSymbolRef()
	case SINGLEtpe =>
	  printTypeRef(); printSymbolRef();
	case CONSTANTtpe =>
	  printTypeRef(); printConstantRef();
	case TYPEREFtpe =>
          printTypeRef(); printSymbolRef(); buf.until(end, printTypeRef)
	case TYPEBOUNDStpe =>
          printTypeRef(); printTypeRef(); 
	case REFINEDtpe =>
	  printSymbolRef(); buf.until(end, printTypeRef)
	case CLASSINFOtpe =>
	  printSymbolRef(); buf.until(end, printTypeRef)
	case METHODtpe =>
	  printTypeRef(); buf.until(end, printTypeRef)
	case POLYtpe =>
	  printTypeRef(); buf.until(end, printSymbolRef)
	case LITERALboolean => 
          out.print(if (buf.readLong(len) == 0) " false" else " true")
	case LITERALbyte    => 
          out.print(" " + buf.readLong(len).asInstanceOf[byte])
	case LITERALshort   => 
          out.print(" " + buf.readLong(len).asInstanceOf[short])
	case LITERALchar    => 
          out.print(" " + buf.readLong(len).asInstanceOf[char])
	case LITERALint     => 
          out.print(" " + buf.readLong(len).asInstanceOf[int])
	case LITERALlong    => 
          out.print(" " + buf.readLong(len))
	case LITERALfloat   => 
          out.print(" " + Float.intBitsToFloat(buf.readLong(len).asInstanceOf[int]))
	case LITERALdouble  => 
          out.print(" " + Double.longBitsToDouble(buf.readLong(len)))
	case LITERALstring  => 
          printNameRef();
	case LITERALnull    => 
          out.print(" <null>")
	case _ => 
      }
      out.println();
      if (buf.readIndex != end)
        out.println("BAD ENTRY END: , computed = " + end + ", factual = " + buf.readIndex);
    }

    for (val i <- Iterator.range(0, index.length)) 
      printEntry(i);
  }

  def main(args: Array[String]): unit = {
    val file = new File(args(0));
    try {
      val stream = new FileInputStream(file);
      val data = new Array[byte](stream.available());
      stream.read(data);
      val pickle = new PickleBuffer(data, 0, data.length);
      printFile(pickle, System.out);
    } catch {
      case ex: IOException => System.out.println("cannot read " + file + ": " + ex.getMessage());
    }
  }
}
