package scala.xml;

/** unprefixed attributes have the null namespace
 */
class UnprefixedAttribute(val key: String, val value: String, val next: MetaData) extends MetaData {

  /** returns a copy of this unprefixed attribute with the given next field*/
  def copy(next: MetaData) = 
    new UnprefixedAttribute(key, value, next);

  def equals1(m:MetaData) = !m.isPrefixed && (m.key == key) && (m.value == value);

  /** returns null */
  final def getNamespace(owner: Node): String = 
    null;

  /** gets value of unqualified (unprefixed) attribute with given key */
  def getValue(key: String): String = 
    if(key == this.key)
      value
    else 
      next.getValue(key);

  /** forwards the call to next */
  def getValue(namespace: String, scope: NamespaceBinding, key: String): String = 
    next.getValue(namespace, scope, key);

  override def hashCode() = 
    key.hashCode() * 7 + value.hashCode() * 53 + next.hashCode();
  
  /** returns false */
  final def isPrefixed = false;

  def toString1(sb:StringBuffer): Unit = {
    sb.append(key);
    sb.append('=');
    Utility.appendQuoted(value, sb);
  }

  def wellformed(scope: NamespaceBinding): Boolean = 
    (null == next.getValue(null, scope, key)) && next.wellformed(scope);
 
}

