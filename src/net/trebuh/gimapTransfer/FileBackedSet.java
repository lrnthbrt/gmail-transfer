/**
 * 
 */
package net.trebuh.gimapTransfer;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Laurent Hubert-Vaillant
 */
public class FileBackedSet {

    private final HashSet<Long> set;
    private final File file;
    private ObjectOutputStream outFile;

    /**
     * Construct a Set backed by the given file. If the file exist, it is used
     * to initialized the current set. It will then be used to contain a copy of
     * the current set.
     * 
     * @param file
     * @throws IOException
     *             if the file exists but cannot be read and written to, or if
     *             the file does not exist and cannot be created.
     */
    public FileBackedSet(File file) throws IOException {
        this.file = file;
        set = new HashSet<>();

        if (file.exists() && file.length() != 0) {
            if (file.canRead() && file.canWrite()) {
                try (InputStream in = new FileInputStream(file);
                        InputStream bis = new BufferedInputStream(in);
                        ObjectInputStream ois = new ObjectInputStream(bis)) {
                    try {
                        while (true)
                            set.add(ois.readLong());
                    } catch (EOFException e) {
                        // end of loading
                    }
                }
            } else {
                throw new IOException("The file " + file.toString() + " exists but it cannot be read and written.");
            }
        }
        outFile = new ObjectOutputStream(new FileOutputStream(file, false));
        for (Long el : set)
            outFile.writeLong(el);
    }

    /**
     * 
     * @param e
     * @return <code>true</code> if this set contains the given element,
     *         <code>false</code> otherwise.
     */
    synchronized public boolean contains(Long e) {
        return set.contains(e);
    }

    /**
     * 
     * @param e
     *            an element to add to the set
     * @return <code>true</code> if this set did not already contain the
     *         specified element, <code>false</code> otherwise
     * @throws IOException
     *             if writing to the file failed
     */
    synchronized public boolean add(Long e) throws IOException {
        boolean r = set.add(e);
        if (r) {
            outFile.writeLong(e);
            outFile.flush();
        }
        return r;
    }

    /**
     * @return a copy of the current set
     */
    synchronized Set<Long> getContents() {
        return new HashSet<>(set);
    }

    /**
     * @return the size of the set
     */
    public int size() {
        return set.size();
    }

    /**
     * Clear the set (and the corresponding file)
     * 
     * @throws IOException
     */
    synchronized public void clear() throws IOException {
        set.clear();
        outFile.close();
        outFile = new ObjectOutputStream(new FileOutputStream(file, false));
        outFile.flush();
    }
}
