/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id$
\*                                                                      */

package scalac.util;

import java.io.*;


public class AbstractFileReader {

    /** the buffer containing the file
     */
    public byte[] buf;

    /** the current input pointer
     */
    public int bp;

    /** the file path name
     */
    public final String path;
    
    /** constructor
     */
    public AbstractFileReader(AbstractFile f) throws IOException {
        buf = f.read();
        bp = 0;
	path = f.getPath();
    }
    
    /** return byte at offset 'pos'
     */
    public byte byteAt(int pos) {
        return buf[pos];
    }

    /** read a byte
     */
    public byte nextByte() {
        return buf[bp++];
    }
    
    /** read some bytes
     */
    public byte[] nextBytes(int len) {
        byte[] res = new byte[len];
        System.arraycopy(buf, bp, res, 0, len);
        bp += len;
        return res;
    }
    
    /** read a character
     */
    public char nextChar() {
        return
            (char)(((buf[bp++] & 0xff) << 8) +
                    (buf[bp++] & 0xff));
    }

    /** read an integer
     */
    public int nextInt() {
        return  ((buf[bp++] & 0xff) << 24) +
                ((buf[bp++] & 0xff) << 16) +
                ((buf[bp++] & 0xff) <<  8) +
                (buf[bp++] & 0xff);
    }

    /** extract a character at position bp from buf
     */
    public char getChar(int mybp) {
        return (char)(((buf[mybp] & 0xff) << 8) + (buf[mybp+1] & 0xff));
    }

    /** extract an integer at position bp from buf
     */
    public int getInt(int mybp) {
        return  ((buf[mybp  ] & 0xff) << 24) +
                ((buf[mybp+1] & 0xff) << 16) +
                ((buf[mybp+2] & 0xff) << 8) +
                 (buf[mybp+3] & 0xff);
    }

    /** extract a long integer at position bp from buf
     */
    public long getLong(int mybp) {
        return ((long)(getInt(mybp)) << 32) + (getInt(mybp + 4) & 0xffffffffL);
    }

    /** extract a float at position bp from buf
     */
    public strictfp float getFloat(int mybp) {
        return Float.intBitsToFloat(getInt(mybp));
    }

    /** extract a double at position bp from buf
     */
    public strictfp double getDouble(int mybp) {
        return Double.longBitsToDouble(getLong(mybp));
    }

   /** skip next 'n' bytes
    */
    public void skip(int n) {
        bp += n;
    }
}
