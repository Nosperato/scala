/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scala.tools.util;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;


/** This class represents a single source file. */
public class SourceFile {

    //########################################################################
    // Public Constants

    /** Constants used for source parsing */
    public static final byte LF = 0x0A;
    public static final byte FF = 0x0C;
    public static final byte CR = 0x0D;
    public static final byte SU = 0x1A;

    //########################################################################
    // Private Fields

    /** The underlying file */
    private final AbstractFile file;

    /** The content of this source file */
    private final byte[] content;

    /** The encoding of this source file or null if unspecified */
    private String encoding;

    /** The position of the last line returned by getLine */
    private int lineNumber = 0;
    private int lineStart  = 0;
    private int lineLength = 0;
    private int nextIndex  = 0;

    //########################################################################
    // Public Constructors

    /** Initializes this instance with given name and content. */
    public SourceFile(String sourcename, byte[] content) {
        this(new ByteArrayFile(sourcename, content), content);
    }

    /** Initializes this instance with given file and content. */
    public SourceFile(AbstractFile file, byte[] content) {
        this.file = file;
        this.content = normalize(content);
    }

    //########################################################################
    // Public Methods

    /** Returns the underlying file. */
    public AbstractFile getFile() {
        return file;
    }

    /** Returns the content of this source file. */
    public byte[] getContent() {
        return content;
    }

    /** Sets the encoding of the file. */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    /**
     * Returns an instance of Position representing the given line and
     * column of this source file.
     */
    public Position getPosition(int line, int column) {
        return new Position(this, line, column);
    }

    /** Returns the specified line. */
    public String getLine(int line) {
        int index = lineNumber <= line ? nextIndex : (lineNumber = 0);
        for (; index < content.length && lineNumber < line; lineNumber++) {
            lineStart = index;
            for (; index < content.length; index++) {
                if (content[index] == CR) break;
                if (content[index] == LF) break;
                if (content[index] == FF) break;
            }
            lineLength = index - lineStart;
            if (index < content.length)
                index++;
            if (index < content.length)
                if (content[index - 1] == CR && content[index] == LF) index++;
        }
        nextIndex = index;
        try {
            return encoding != null ?
                new String(content, lineStart, lineLength, encoding) :
                new String(content, lineStart, lineLength);
        } catch (UnsupportedEncodingException exception) {
            throw new Error(exception); // !!! use ApplicationError
        }
    }

    /** Returns the path of the underlying file. */
    public String toString() {
        return file.getPath();
    }

    //########################################################################
    // Private Functions

    /** Ensures that the last byte of the array is SU. */
    private static byte[] normalize(byte[] input) {
        if (input.length > 0 && input[input.length - 1] == SU)
                return input;
        byte[] content = new byte[input.length + 1];
        System.arraycopy(input, 0, content, 0, input.length);
        content[input.length] = SU;
        return content;
    }

    //########################################################################
}
